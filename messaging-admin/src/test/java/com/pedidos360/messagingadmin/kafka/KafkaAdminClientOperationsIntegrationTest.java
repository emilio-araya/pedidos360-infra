package com.pedidos360.messagingadmin.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pedidos360.messagingadmin.config.KafkaAdminProperties;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * The adapter against a real broker: every call is a genuine AdminClient
 * request, so the mappings (futures, exceptions, timeouts) are exercised too.
 */
class KafkaAdminClientOperationsIntegrationTest {

    private static EmbeddedKafkaKraftBroker broker;

    private AdminClient client;
    private KafkaAdminClientOperations operations;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, "it.lifecycle", "it.configs", "it.partitions");
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    @BeforeEach
    void setUp() {
        client = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
        operations = new KafkaAdminClientOperations(client, new KafkaAdminProperties(
                List.of(),
                List.of("retention.ms", "cleanup.policy", "min.insync.replicas"),
                1000, 1, (short) 1, Duration.ofSeconds(15)));
    }

    @AfterEach
    void tearDown() {
        client.close(Duration.ofSeconds(5));
    }

    @Test
    void createsDescribesAndDeletesATopic() throws Exception {
        operations.createTopic(new NewTopic("it.created", 1, (short) 1));

        assertThat(operations.listTopicNames(false)).contains("it.created");
        assertThat(operations.describeTopics(List.of("it.created")).get("it.created").partitions())
                .hasSize(1);

        operations.deleteTopics(List.of("it.created"));

        // Kafka marks a topic for deletion asynchronously: metadata disappears
        // a moment later, so wait for it with a bounded deadline.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (operations.listTopicNames(false).contains("it.created")) {
            assertThat(System.nanoTime())
                    .as("Kafka no eliminó el tópico it.created")
                    .isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    @Test
    void surfacesTheBrokerErrorWhenTheTopicExists() {
        assertThatThrownBy(() -> operations.createTopic(new NewTopic("it.lifecycle", 1, (short) 1)))
                .isInstanceOf(TopicExistsException.class);
    }

    @Test
    void growsThePartitionsOfATopic() {
        operations.createPartitions("it.partitions", 3);

        assertThat(operations.describeTopics(List.of("it.partitions")).get("it.partitions").partitions())
                .hasSize(3);
    }

    @Test
    void readsAndAltersTopicConfigs() {
        operations.alterTopicConfigs("it.configs", Map.of("retention.ms", "120000"));

        Map<String, ConfigEntry> configs = operations.describeTopicConfigs("it.configs");

        assertThat(configs.get("retention.ms").value()).isEqualTo("120000");
        assertThat(configs.get("retention.ms").isDefault()).isFalse();
    }

    @Test
    void readsTheClusterNodesAndTheTopicPartitions() {
        Collection<Node> nodes = operations.clusterNodes();
        Set<TopicPartition> partitions = operations.topicPartitions("it.lifecycle");

        assertThat(nodes).isNotEmpty();
        assertThat(partitions).containsExactly(new TopicPartition("it.lifecycle", 0));
    }

    @Test
    void commitsAndReadsGroupOffsets() {
        Map<TopicPartition, Long> endOffsets = operations.logEndOffsets(
                Set.of(new TopicPartition("it.lifecycle", 0)));
        assertThat(endOffsets).containsEntry(new TopicPartition("it.lifecycle", 0), 0L);

        operations.alterConsumerGroupOffsets("it-group", Map.of(
                new TopicPartition("it.lifecycle", 0), new OffsetAndMetadata(0)));

        assertThat(operations.listConsumerGroupOffsets("it-group"))
                .containsEntry(new TopicPartition("it.lifecycle", 0), new OffsetAndMetadata(0));
        assertThat(operations.listConsumerGroups())
                .anySatisfy(listing -> assertThat(listing.groupId()).isEqualTo("it-group"));

        ConsumerGroupDescription description = operations.describeConsumerGroup("it-group");
        assertThat(description.groupId()).isEqualTo("it-group");
        assertThat(description.state().name()).isEqualTo("EMPTY");
    }
}
