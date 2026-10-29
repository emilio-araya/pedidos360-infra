package com.pedidos360.messagingadmin.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.messagingadmin.admin.BindingSpec;
import com.pedidos360.messagingadmin.admin.ExchangeSpec;
import com.pedidos360.messagingadmin.admin.ProtectedResourceException;
import com.pedidos360.messagingadmin.admin.QueueInfo;
import com.pedidos360.messagingadmin.admin.QueueNotFoundException;
import com.pedidos360.messagingadmin.admin.QueueSpec;
import com.pedidos360.messagingadmin.admin.RabbitAdminService;
import com.pedidos360.messagingadmin.admin.ResourceConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the admin API: status codes, validation and error bodies. */
@WebMvcTest(MessagingAdminController.class)
class MessagingAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RabbitAdminService adminService;

    @Test
    void createsAQueueWithItsDeadLetterQueue() throws Exception {
        when(adminService.createQueue(any())).thenReturn(new QueueInfo("tickets.generate", "tickets.generate.dlq", 0, 0));

        mockMvc.perform(post("/api/admin/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tickets.generate\",\"deadLetter\":true,\"messageTtlMs\":60000}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/queues/tickets.generate"))
                .andExpect(jsonPath("$.name").value("tickets.generate"))
                .andExpect(jsonPath("$.deadLetterQueue").value("tickets.generate.dlq"));

        verify(adminService).createQueue(new QueueSpec("tickets.generate", true, true, QueueSpec.Type.CLASSIC, 60000L, null));
    }

    @Test
    void rejectsABlankQueueName() throws Exception {
        mockMvc.perform(post("/api/admin/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());

        verifyNoInteractions(adminService);
    }

    @Test
    void rejectsNamesWithInvalidCharactersOrTheReservedAmqPrefix() throws Exception {
        for (String name : new String[] {"cola con espacios", "amq.mia", "-empieza-con-guion", "a".repeat(201)}) {
            mockMvc.perform(post("/api/admin/queues")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
        }
        verifyNoInteractions(adminService);
    }

    @Test
    void rejectsInvalidQueueSettings() throws Exception {
        mockMvc.perform(post("/api/admin/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tickets\",\"messageTtlMs\":-1,\"maxLength\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'messageTtlMs')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'maxLength')]").exists());

        mockMvc.perform(post("/api/admin/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tickets\",\"durable\":false,\"type\":\"QUORUM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'durableWhenRequired')]").exists());

        verifyNoInteractions(adminService);
    }

    @Test
    void rejectsAnUnknownExchangeTypeWithAClearMessage() throws Exception {
        mockMvc.perform(post("/api/admin/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tickets.events\",\"type\":\"rapido\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("DIRECT, TOPIC, FANOUT, HEADERS")));

        verifyNoInteractions(adminService);
    }

    @Test
    void createsADirectOrTopicExchange() throws Exception {
        mockMvc.perform(post("/api/admin/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tickets.events\",\"type\":\"TOPIC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("tickets.events"))
                .andExpect(jsonPath("$.type").value("TOPIC"));

        verify(adminService).createExchange(new ExchangeSpec("tickets.events", ExchangeSpec.Type.TOPIC, true));
    }

    @Test
    void bindsAQueueAndValidatesTheRoutingKey() throws Exception {
        mockMvc.perform(post("/api/admin/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"pedidos360.events\",\"queue\":\"tickets.generate\",\"routingKey\":\"pedido.*\"}"))
                .andExpect(status().isCreated());
        verify(adminService).bind(new BindingSpec("pedidos360.events", "tickets.generate", "pedido.*"));

        mockMvc.perform(post("/api/admin/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"\",\"queue\":\"tickets.generate\",\"routingKey\":\"con espacio\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'exchange')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'routingKey')]").exists());
    }

    @Test
    void reportsQueueDetails() throws Exception {
        when(adminService.getQueue("tickets.generate")).thenReturn(new QueueInfo("tickets.generate", null, 3, 1));

        mockMvc.perform(get("/api/admin/queues/tickets.generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCount").value(3))
                .andExpect(jsonPath("$.consumerCount").value(1));
    }

    @Test
    void deletesAQueue() throws Exception {
        mockMvc.perform(delete("/api/admin/queues/tickets.generate"))
                .andExpect(status().isNoContent());

        verify(adminService).deleteQueue("tickets.generate");
    }

    @Test
    void answersNotFoundForAnUnknownQueue() throws Exception {
        doThrow(new QueueNotFoundException("no.existe")).when(adminService).deleteQueue("no.existe");

        mockMvc.perform(delete("/api/admin/queues/no.existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La cola no.existe no existe"));
    }

    @Test
    void refusesToDeleteASystemQueue() throws Exception {
        doThrow(new ProtectedResourceException("stock.reserve")).when(adminService).deleteQueue("stock.reserve");

        mockMvc.perform(delete("/api/admin/queues/stock.reserve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "stock.reserve pertenece al sistema y no se puede crear, modificar ni eliminar desde esta API"));
    }

    @Test
    void answersConflictWhenTheQueueAlreadyExists() throws Exception {
        when(adminService.createQueue(any())).thenThrow(new ResourceConflictException("La cola tickets ya existe"));

        mockMvc.perform(post("/api/admin/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tickets\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La cola tickets ya existe"));
    }

    @Test
    void rejectsAnInvalidNameInThePath() throws Exception {
        mockMvc.perform(delete("/api/admin/queues/{name}", "amq.gen-123"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminService);
    }

    @Test
    void unbindsWithQueryParameters() throws Exception {
        mockMvc.perform(delete("/api/admin/bindings")
                        .param("exchange", "pedidos360.events")
                        .param("queue", "tickets.generate")
                        .param("routingKey", "pedido.*"))
                .andExpect(status().isNoContent());

        verify(adminService).unbind(eq(new BindingSpec("pedidos360.events", "tickets.generate", "pedido.*")));
    }
}
