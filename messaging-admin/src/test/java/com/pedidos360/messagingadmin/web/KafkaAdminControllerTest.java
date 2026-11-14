package com.pedidos360.messagingadmin.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.messagingadmin.admin.ProtectedResourceException;
import com.pedidos360.messagingadmin.kafka.ConsumerGroupInfo;
import com.pedidos360.messagingadmin.kafka.InvalidTopicSpecException;
import com.pedidos360.messagingadmin.kafka.KafkaAdminService;
import com.pedidos360.messagingadmin.kafka.KafkaUnavailableException;
import com.pedidos360.messagingadmin.kafka.OffsetResetResult;
import com.pedidos360.messagingadmin.kafka.PartitionView;
import com.pedidos360.messagingadmin.kafka.TopicInfo;
import com.pedidos360.messagingadmin.kafka.TopicNotFoundException;
import com.pedidos360.messagingadmin.kafka.TopicPartitionLag;
import com.pedidos360.messagingadmin.kafka.TopicSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the Kafka admin API: status codes, validation and error bodies. */
@WebMvcTest(KafkaAdminController.class)
class KafkaAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaAdminService adminService;

    @Test
    void createsATopic() throws Exception {
        when(adminService.createTopic(any())).thenReturn(topicInfo("tickets.events"));

        mockMvc.perform(post("/api/admin/kafka/topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tickets.events\",\"partitions\":3,\"replicationFactor\":1,"
                                + "\"configs\":{\"retention.ms\":\"60000\"}}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/kafka/topics/tickets.events"))
                .andExpect(jsonPath("$.name").value("tickets.events"))
                .andExpect(jsonPath("$.partitions[0].id").value(0));

        verify(adminService).createTopic(new TopicSpec(
                "tickets.events", 3, (short) 1, Map.of("retention.ms", "60000")));
    }

    @Test
    void rejectsABlankTopicName() throws Exception {
        mockMvc.perform(post("/api/admin/kafka/topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());

        verifyNoInteractions(adminService);
    }

    @Test
    void rejectsNamesWithInvalidCharacters() throws Exception {
        for (String name : new String[] {"topic con espacios", "../etc", "a".repeat(250)}) {
            mockMvc.perform(post("/api/admin/kafka/topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
        }
        verifyNoInteractions(adminService);
    }

    @Test
    void protectsSystemTopics() throws Exception {
        doThrow(new ProtectedResourceException("pedidos360.order-events"))
                .when(adminService).deleteTopic("pedidos360.order-events");

        mockMvc.perform(delete("/api/admin/kafka/topics/pedidos360.order-events"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto"));
    }

    @Test
    void returnsNotFoundForAMissingTopic() throws Exception {
        when(adminService.describeTopic("missing")).thenThrow(new TopicNotFoundException("missing"));

        mockMvc.perform(get("/api/admin/kafka/topics/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("No encontrado"));
    }

    @Test
    void returnsBadRequestForAnInvalidConfig() throws Exception {
        when(adminService.alterConfigs(any(), any()))
                .thenThrow(new InvalidTopicSpecException("retention.ms debe ser -1 o mayor o igual a 1000"));

        mockMvc.perform(patch("/api/admin/kafka/topics/tickets.events/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configs\":{\"retention.ms\":\"0\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("retention.ms debe ser -1 o mayor o igual a 1000"));
    }

    @Test
    void returnsServiceUnavailableWhenKafkaIsDown() throws Exception {
        when(adminService.describeTopic("tickets.events"))
                .thenThrow(new KafkaUnavailableException("Kafka no respondió en 10000 ms"));

        mockMvc.perform(get("/api/admin/kafka/topics/tickets.events"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Kafka no disponible"));
    }

    @Test
    void deletesATopic() throws Exception {
        mockMvc.perform(delete("/api/admin/kafka/topics/tickets.events"))
                .andExpect(status().isNoContent());

        verify(adminService).deleteTopic("tickets.events");
    }

    @Test
    void listsConsumerGroups() throws Exception {
        when(adminService.listConsumerGroups())
                .thenReturn(List.of(new ConsumerGroupInfo("catalog-sales-projection", "STABLE", 1, 7)));

        mockMvc.perform(get("/api/admin/kafka/consumer-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value("catalog-sales-projection"))
                .andExpect(jsonPath("$[0].totalLag").value(7));
    }

    @Test
    void resetsConsumerGroupOffsets() throws Exception {
        when(adminService.resetOffsets(any())).thenReturn(new OffsetResetResult(
                "catalog-sales-projection", "pedidos360.order-events",
                List.of(new TopicPartitionLag("pedidos360.order-events", 0, 0, 4, 4))));

        mockMvc.perform(post("/api/admin/kafka/consumer-groups/catalog-sales-projection/offsets/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"pedidos360.order-events\",\"strategy\":\"EARLIEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value("catalog-sales-projection"))
                .andExpect(jsonPath("$.offsets[0].currentOffset").value(0));
    }

    private static TopicInfo topicInfo(String name) {
        return new TopicInfo(name, false, Map.of("retention.ms", "60000"),
                List.of(new PartitionView(0, 1, List.of(1), List.of(1))));
    }
}
