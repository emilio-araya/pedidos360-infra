package com.pedidos360.messagingadmin.admin;

import com.pedidos360.messagingadmin.config.MessagingAdminProperties;
import java.util.Properties;
import org.springframework.amqp.AmqpIOException;
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
        if (amqpAdmin.getQueueProperties(spec.name()) != null) {
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

        String deadLetterQueue = null;
        if (spec.deadLetter()) {
            deadLetterQueue = spec.name() + properties.deadLetterSuffix();
            DirectExchange deadLetterExchange = new DirectExchange(properties.deadLetterExchange(), true, false);
            amqpAdmin.declareExchange(deadLetterExchange);
            amqpAdmin.declareQueue(QueueBuilder.durable(deadLetterQueue).build());
            amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(deadLetterQueue))
                    .to(deadLetterExchange)
                    .with(spec.name()));
            builder.deadLetterExchange(properties.deadLetterExchange())
                    .deadLetterRoutingKey(spec.name());
        }

        declare(() -> amqpAdmin.declareQueue(builder.build()), "La cola " + spec.name());
        return new QueueInfo(spec.name(), deadLetterQueue, 0, 0);
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

    public void deleteQueue(String name) {
        requireNotReserved(name);
        requireQueue(name);
        amqpAdmin.deleteQueue(name);
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
        declare(() -> amqpAdmin.declareExchange(exchange), "El exchange " + spec.name());
    }

    public void deleteExchange(String name) {
        requireNotReserved(name);
        amqpAdmin.deleteExchange(name);
    }

    public void bind(BindingSpec spec) {
        requireBindable(spec);
        requireQueue(spec.queue());
        declare(() -> amqpAdmin.declareBinding(binding(spec)),
                "El binding de " + spec.queue() + " a " + spec.exchange());
    }

    public void unbind(BindingSpec spec) {
        requireBindable(spec);
        amqpAdmin.removeBinding(binding(spec));
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
        Properties queue = amqpAdmin.getQueueProperties(name);
        if (queue == null) {
            throw new QueueNotFoundException(name);
        }
        return queue;
    }

    /** The broker closes the channel when a name exists with other settings: report it as a conflict. */
    private void declare(Runnable declaration, String subject) {
        try {
            declaration.run();
        } catch (AmqpIOException exception) {
            throw new ResourceConflictException(subject + " ya existe con otra configuración");
        }
    }

    private static int count(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
