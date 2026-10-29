package com.pedidos360.messagingadmin.admin;

import com.pedidos360.messagingadmin.config.MessagingAdminProperties;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

/**
 * Creates, inspects and removes queues, exchanges and bindings. It is the only
 * class that talks to {@link AmqpAdmin}: controllers call these high-level
 * operations and never see RabbitMQ arguments or channel errors.
 *
 * <p>Names under {@link MessagingAdminProperties#reservedPrefixes()} belong to the
 * running system (for example {@code stock.reserve}) and are never touched, so
 * this API cannot break order processing.
 */
@Service
public class RabbitAdminService {

    private final AmqpAdmin amqpAdmin;
    private final MessagingAdminProperties properties;

    public RabbitAdminService(AmqpAdmin amqpAdmin, MessagingAdminProperties properties) {
        this.amqpAdmin = amqpAdmin;
        this.properties = properties;
    }

    /** Creates the queue and, when asked, its dead-letter queue bound to the dead-letter exchange. */
    public QueueInfo createQueue(QueueSpec spec) {
        requireNotReserved(spec.name());
        if (queueProperties(spec.name()) != null) {
            throw new ResourceConflictException("La cola " + spec.name() + " ya existe");
        }

        QueueBuilder builder = spec.durable() ? QueueBuilder.durable(spec.name()) : QueueBuilder.nonDurable(spec.name());
        if (spec.type() == QueueSpec.Type.QUORUM) {
            builder.quorum();
        }
        if (spec.messageTtlMs() != null) {
            builder.withArgument("x-message-ttl", spec.messageTtlMs());
        }
        if (spec.maxLength() != null) {
            builder.withArgument("x-max-length", spec.maxLength());
        }

        String deadLetterQueue = spec.deadLetter() ? spec.name() + properties.deadLetterSuffix() : null;
        if (deadLetterQueue != null) {
            // The dead-letter exchange does not need to exist yet when the queue is declared.
            builder.deadLetterExchange(properties.deadLetterExchange())
                    .deadLetterRoutingKey(spec.name());
        }

        String subject = "La cola " + spec.name();
        call(() -> amqpAdmin.declareQueue(builder.build()), subject);
        if (deadLetterQueue != null) {
            try {
                declareDeadLetterQueue(spec.name(), deadLetterQueue);
            } catch (RuntimeException exception) {
                // No half-created queue: without its DLQ, rejected messages would be lost.
                call(() -> amqpAdmin.deleteQueue(spec.name()), subject);
                throw exception;
            }
        }
        return new QueueInfo(spec.name(), deadLetterQueue, 0, 0);
    }

    private void declareDeadLetterQueue(String queue, String deadLetterQueue) {
        String subject = "La cola " + deadLetterQueue;
        DirectExchange deadLetterExchange = new DirectExchange(properties.deadLetterExchange(), true, false);
        call(() -> amqpAdmin.declareExchange(deadLetterExchange), "El exchange " + properties.deadLetterExchange());
        call(() -> amqpAdmin.declareQueue(QueueBuilder.durable(deadLetterQueue).build()), subject);
        call(() -> amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(deadLetterQueue))
                .to(deadLetterExchange)
                .with(queue)), subject);
    }

    /** Reading is allowed for every queue, system ones included, so they can be shown in a demo. */
    public QueueInfo getQueue(String name) {
        Properties queue = requireQueue(name);
        return new QueueInfo(
                name,
                null,
                count(queue.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)),
                count(queue.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)));
    }

    /** Also deletes the queue's dead-letter queue, so no orphan DLQ is left behind. */
    public void deleteQueue(String name) {
        requireNotReserved(name);
        requireQueue(name);
        call(() -> amqpAdmin.deleteQueue(name), "La cola " + name);
        String deadLetterQueue = name + properties.deadLetterSuffix();
        if (queueProperties(deadLetterQueue) != null) {
            call(() -> amqpAdmin.deleteQueue(deadLetterQueue), "La cola " + deadLetterQueue);
        }
    }

    public void createExchange(ExchangeSpec spec) {
        requireNotReserved(spec.name());
        ExchangeBuilder builder = switch (spec.type()) {
            case DIRECT -> ExchangeBuilder.directExchange(spec.name());
            case TOPIC -> ExchangeBuilder.topicExchange(spec.name());
            case FANOUT -> ExchangeBuilder.fanoutExchange(spec.name());
            case HEADERS -> ExchangeBuilder.headersExchange(spec.name());
        };
        Exchange exchange = builder.durable(spec.durable()).build();
        call(() -> amqpAdmin.declareExchange(exchange), "El exchange " + spec.name());
    }

    public void deleteExchange(String name) {
        requireNotReserved(name);
        call(() -> amqpAdmin.deleteExchange(name), "El exchange " + name);
    }

    public void bind(BindingSpec spec) {
        requireBindable(spec);
        requireQueue(spec.queue());
        call(() -> amqpAdmin.declareBinding(binding(spec)),
                "El binding de " + spec.queue() + " a " + spec.exchange());
    }

    public void unbind(BindingSpec spec) {
        requireBindable(spec);
        call(() -> amqpAdmin.removeBinding(binding(spec)),
                "El binding de " + spec.queue() + " a " + spec.exchange());
    }

    private Binding binding(BindingSpec spec) {
        return new Binding(spec.queue(), Binding.DestinationType.QUEUE, spec.exchange(), spec.routingKey(), null);
    }

    /** A new queue may bind to an allowed system exchange, but a system queue is never rebound. */
    private void requireBindable(BindingSpec spec) {
        requireNotReserved(spec.queue());
        if (properties.isReserved(spec.exchange()) && !properties.bindableExchanges().contains(spec.exchange())) {
            throw new ProtectedResourceException(spec.exchange());
        }
    }

    private void requireNotReserved(String name) {
        if (properties.isReserved(name)) {
            throw new ProtectedResourceException(name);
        }
    }

    private Properties requireQueue(String name) {
        Properties queue = queueProperties(name);
        if (queue == null) {
            throw new QueueNotFoundException(name);
        }
        return queue;
    }

    private Properties queueProperties(String name) {
        return call(() -> amqpAdmin.getQueueProperties(name), "La cola " + name);
    }

    private void call(Runnable operation, String subject) {
        call(() -> {
            operation.run();
            return null;
        }, subject);
    }

    /**
     * Single translation point for broker failures. Only 406 PRECONDITION_FAILED
     * means "exists with other settings"; any other channel close is a refusal we
     * report with the broker's own reason, and a lost connection is transient.
     */
    private <T> T call(Supplier<T> operation, String subject) {
        try {
            return operation.get();
        } catch (AmqpException exception) {
            ShutdownSignalException signal = shutdownSignal(exception);
            if (signal != null && signal.getReason() instanceof AMQP.Channel.Close close) {
                if (close.getReplyCode() == AMQP.PRECONDITION_FAILED) {
                    throw new ResourceConflictException(subject + " ya existe con otra configuración");
                }
                throw new BrokerUnavailableException("RabbitMQ rechazó la operación: " + close.getReplyText());
            }
            throw new BrokerUnavailableException(
                    "No se pudo completar la operación en RabbitMQ; intenta de nuevo en unos segundos");
        }
    }

    private static ShutdownSignalException shutdownSignal(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException signal) {
                return signal;
            }
        }
        return null;
    }

    private static int count(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
