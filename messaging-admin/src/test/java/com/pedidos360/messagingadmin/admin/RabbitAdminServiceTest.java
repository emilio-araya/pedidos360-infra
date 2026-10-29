package com.pedidos360.messagingadmin.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedidos360.messagingadmin.config.MessagingAdminProperties;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/** What the service asks the broker to do. AmqpAdmin is a double: no broker needed. */
class RabbitAdminServiceTest {

    private AmqpAdmin amqpAdmin;
    private RabbitAdminService service;

    @BeforeEach
    void setUp() {
        amqpAdmin = mock(AmqpAdmin.class);
        MessagingAdminProperties properties = new MessagingAdminProperties(
                "admin.dlx", ".dlq", List.of("stock.", "orders.", "pedidos360."), List.of("pedidos360.events"));
        service = new RabbitAdminService(amqpAdmin, properties);
    }

    @Test
    void createsTheQueueWithItsDeadLetterExchangeQueueAndBinding() {
        QueueInfo created = service.createQueue(new QueueSpec("tickets.generate", true, true, QueueSpec.Type.CLASSIC, 60000L, 500));

        ArgumentCaptor<Exchange> exchange = ArgumentCaptor.forClass(Exchange.class);
        verify(amqpAdmin).declareExchange(exchange.capture());
        assertThat(exchange.getValue()).isInstanceOf(DirectExchange.class);
        assertThat(exchange.getValue().getName()).isEqualTo("admin.dlx");
        assertThat(exchange.getValue().isDurable()).isTrue();

        ArgumentCaptor<Queue> queues = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, org.mockito.Mockito.times(2)).declareQueue(queues.capture());
        Queue dlq = queues.getAllValues().get(0);
        Queue main = queues.getAllValues().get(1);
        assertThat(dlq.getName()).isEqualTo("tickets.generate.dlq");
        assertThat(main.getName()).isEqualTo("tickets.generate");
        assertThat(main.isDurable()).isTrue();
        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "admin.dlx")
                .containsEntry("x-dead-letter-routing-key", "tickets.generate")
                .containsEntry("x-message-ttl", 60000L)
                .containsEntry("x-max-length", 500);

        ArgumentCaptor<Binding> binding = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(binding.capture());
        assertThat(binding.getValue().getExchange()).isEqualTo("admin.dlx");
        assertThat(binding.getValue().getDestination()).isEqualTo("tickets.generate.dlq");
        assertThat(binding.getValue().getRoutingKey()).isEqualTo("tickets.generate");

        assertThat(created.name()).isEqualTo("tickets.generate");
        assertThat(created.deadLetterQueue()).isEqualTo("tickets.generate.dlq");
    }

    @Test
    void createsAQuorumQueueWithoutDeadLetter() {
        service.createQueue(new QueueSpec("tickets.audit", true, false, QueueSpec.Type.QUORUM, null, null));

        ArgumentCaptor<Queue> queue = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(queue.capture());
        assertThat(queue.getValue().getArguments())
                .containsEntry("x-queue-type", "quorum")
                .doesNotContainKey("x-dead-letter-exchange");
        verify(amqpAdmin, never()).declareExchange(any());
    }

    @Test
    void refusesToCreateAQueueThatAlreadyExists() {
        when(amqpAdmin.getQueueProperties("tickets")).thenReturn(new Properties());

        assertThatThrownBy(() -> service.createQueue(new QueueSpec("tickets", true, true, QueueSpec.Type.CLASSIC, null, null)))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessage("La cola tickets ya existe");
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void refusesToTouchSystemQueuesAndExchanges() {
        assertThatThrownBy(() -> service.deleteQueue("stock.reserve")).isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.createQueue(new QueueSpec("orders.nueva", true, true, QueueSpec.Type.CLASSIC, null, null)))
                .isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.deleteExchange("pedidos360.events")).isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.unbind(new BindingSpec("pedidos360.events", "stock.reserve", "pedido.aceptado")))
                .isInstanceOf(ProtectedResourceException.class);

        verify(amqpAdmin, never()).deleteQueue(anyString());
        verify(amqpAdmin, never()).deleteExchange(anyString());
        verify(amqpAdmin, never()).removeBinding(any());
    }

    @Test
    void deletesAnExistingQueueAndReportsAMissingOne() {
        when(amqpAdmin.getQueueProperties("tickets")).thenReturn(new Properties());

        service.deleteQueue("tickets");
        verify(amqpAdmin).deleteQueue("tickets");
        verify(amqpAdmin, never()).deleteQueue("tickets.dlq");

        when(amqpAdmin.getQueueProperties("tickets.dlq")).thenReturn(new Properties());
        service.deleteQueue("tickets");
        verify(amqpAdmin).deleteQueue("tickets.dlq");

        assertThatThrownBy(() -> service.deleteQueue("no.existe"))
                .isInstanceOf(QueueNotFoundException.class)
                .hasMessage("La cola no.existe no existe");
    }

    @Test
    void readsMessageAndConsumerCounts() {
        Properties properties = new Properties();
        properties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 4);
        properties.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 2);
        when(amqpAdmin.getQueueProperties("stock.reserve")).thenReturn(properties);

        QueueInfo info = service.getQueue("stock.reserve");

        assertThat(info.messageCount()).isEqualTo(4);
        assertThat(info.consumerCount()).isEqualTo(2);
    }

    @Test
    void declaresTheExchangeType() {
        service.createExchange(new ExchangeSpec("tickets.events", ExchangeSpec.Type.TOPIC, true));

        ArgumentCaptor<Exchange> exchange = ArgumentCaptor.forClass(Exchange.class);
        verify(amqpAdmin).declareExchange(exchange.capture());
        assertThat(exchange.getValue()).isInstanceOf(TopicExchange.class);
        assertThat(exchange.getValue().getName()).isEqualTo("tickets.events");
    }

    @Test
    void bindsOnlyToAnExistingQueue() {
        when(amqpAdmin.getQueueProperties("tickets")).thenReturn(new Properties());

        service.bind(new BindingSpec("pedidos360.events", "tickets", "pedido.*"));

        ArgumentCaptor<Binding> binding = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(binding.capture());
        assertThat(binding.getValue().getExchange()).isEqualTo("pedidos360.events");
        assertThat(binding.getValue().getDestination()).isEqualTo("tickets");
        assertThat(binding.getValue().getRoutingKey()).isEqualTo("pedido.*");

        assertThatThrownBy(() -> service.bind(new BindingSpec("pedidos360.events", "no.existe", "x")))
                .isInstanceOf(QueueNotFoundException.class);
    }
}
