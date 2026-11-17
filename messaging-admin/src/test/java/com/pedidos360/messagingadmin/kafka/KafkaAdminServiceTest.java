package com.pedidos360.messagingadmin.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedidos360.messagingadmin.admin.ProtectedResourceException;
import com.pedidos360.messagingadmin.config.KafkaAdminProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.MemberAssignment;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Business rules of the Kafka admin API; the gateway is a double, no broker needed. */
class KafkaAdminServiceTest {

    private static final Node BROKER = new Node(1, "kafka", 9092);

    private KafkaOperations operations;
    private KafkaAdminService service;

    @BeforeEach
    void setUp() {
        operations = org.mockito.Mockito.mock(KafkaOperations.class);
        KafkaAdminProperties properties = new KafkaAdminProperties(
                List.of("pedidos360.", "orders.", "stock.", "__"),
                List.of("retention.ms", "retention.bytes", "cleanup.policy", "segment.ms",
                        "max.message.bytes", "min.insync.replicas"),
                1000, 3, (short) 1, Duration.ofSeconds(5));
        service = new KafkaAdminService(operations, properties);

        when(operations.clusterNodes()).thenReturn(List.of(BROKER));
        when(operations.describeTopicConfigs(anyString())).thenReturn(Map.of());
        when(operations.describeTopics(anyCollection())).thenAnswer(invocation -> {
            Collection<String> names = invocation.getArgument(0);
            Map<String, TopicDescription> descriptions = new HashMap<>();
            names.forEach(name -> descriptions.put(name, description(name, 3)));
            return descriptions;
        });
    }

    @Test
    void createsATopicWithTheDefaultsAndReturnsItsDetail() {
        TopicInfo created = service.createTopic(new TopicSpec("tickets.events", null, null, Map.of()));

        ArgumentCaptor<NewTopic> topic = ArgumentCaptor.forClass(NewTopic.class);
        verify(operations).createTopic(topic.capture());
        assertThat(topic.getValue().name()).isEqualTo("tickets.events");
        assertThat(topic.getValue().numPartitions()).isEqualTo(3);
        assertThat(topic.getValue().replicationFactor()).isEqualTo((short) 1);
        assertThat(created.name()).isEqualTo("tickets.events");
        assertThat(created.partitionCount()).isEqualTo(3);
    }

    @Test
    void rejectsReservedNamesBeforeTalkingToKafka() {
        assertThatThrownBy(() -> service.createTopic(new TopicSpec("pedidos360.order-events", 1, (short) 1, Map.of())))
                .isInstanceOf(ProtectedResourceException.class);
        verify(operations, never()).createTopic(any());
    }

    @Test
    void rejectsAReplicationFactorAboveTheBrokerCount() {
        assertThatThrownBy(() -> service.createTopic(new TopicSpec("tickets.events", 1, (short) 3, Map.of())))
                .isInstanceOf(InvalidTopicSpecException.class)
                .hasMessageContaining("factor de replicación");
    }

    @Test
    void rejectsAnInvalidTopicName() {
        assertThatThrownBy(() -> service.createTopic(new TopicSpec("../etc", 1, (short) 1, Map.of())))
                .isInstanceOf(InvalidTopicSpecException.class);
    }

    @Test
    void rejectsAConfigKeyOutsideTheAllowlist() {
        assertThatThrownBy(() -> service.createTopic(new TopicSpec(
                "tickets.events", 1, (short) 1, Map.of("unclean.leader.election.enable", "true"))))
                .isInstanceOf(InvalidTopicSpecException.class)
                .hasMessageContaining("no permitida");
    }

    @Test
    void rejectsMinInSyncReplicasAboveTheReplicationFactor() {
        assertThatThrownBy(() -> service.createTopic(new TopicSpec(
                "tickets.events", 1, (short) 1, Map.of("min.insync.replicas", "2"))))
                .isInstanceOf(InvalidTopicSpecException.class)
                .hasMessageContaining("min.insync.replicas");
    }

    @Test
    void rejectsAnInvalidCleanupPolicy() {
        assertThatThrownBy(() -> service.createTopic(new TopicSpec(
                "tickets.events", 1, (short) 1, Map.of("cleanup.policy", "delete,shred"))))
                .isInstanceOf(InvalidTopicSpecException.class)
                .hasMessageContaining("cleanup.policy");
    }

    @Test
    void translatesAnExistingTopic() {
        doThrow(new TopicExistsException("exists")).when(operations).createTopic(any());

        assertThatThrownBy(() -> service.createTopic(new TopicSpec("tickets.events", 1, (short) 1, Map.of())))
                .isInstanceOf(TopicAlreadyExistsException.class);
    }

    @Test
    void translatesAMissingTopicWhenDescribing() {
        when(operations.describeTopics(anyCollection())).thenThrow(new UnknownTopicOrPartitionException("missing"));

        assertThatThrownBy(() -> service.describeTopic("missing"))
                .isInstanceOf(TopicNotFoundException.class);
    }

    @Test
    void growsPartitionsButNeverShrinksThem() {
        assertThatThrownBy(() -> service.increasePartitions("tickets.events", 2))
                .isInstanceOf(PartitionsCannotDecreaseException.class);

        service.increasePartitions("tickets.events", 5);

        verify(operations).createPartitions("tickets.events", 5);
    }

    @Test
    void validatesConfigsBeforeAlteringThem() {
        assertThatThrownBy(() -> service.alterConfigs("tickets.events", Map.of("retention.ms", "0")))
                .isInstanceOf(InvalidTopicSpecException.class)
                .hasMessageContaining("retention.ms");
        verify(operations, never()).alterTopicConfigs(anyString(), any());
    }

    @Test
    void translatesAnInvalidBrokerConfiguration() {
        doThrow(new InvalidConfigurationException("invalid")).when(operations)
                .alterTopicConfigs(anyString(), any());

        assertThatThrownBy(() -> service.alterConfigs("tickets.events", Map.of("retention.ms", "60000")))
                .isInstanceOf(InvalidTopicSpecException.class);
    }

    @Test
    void listsConsumerGroupsWithTheirTotalLag() {
        TopicPartition partition = new TopicPartition("pedidos360.order-events", 0);
        when(operations.listConsumerGroups()).thenReturn(List.of(new ConsumerGroupListing("catalog-sales-projection", false)));
        when(operations.describeConsumerGroup("catalog-sales-projection")).thenReturn(
                new ConsumerGroupDescription("catalog-sales-projection", false, List.of(), "range",
                        ConsumerGroupState.STABLE, BROKER));
        when(operations.listConsumerGroupOffsets("catalog-sales-projection"))
                .thenReturn(Map.of(partition, new OffsetAndMetadata(5)));
        when(operations.logEndOffsets(anyCollection())).thenReturn(Map.of(partition, 8L));

        List<ConsumerGroupInfo> groups = service.listConsumerGroups();

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.groupId()).isEqualTo("catalog-sales-projection");
            assertThat(group.state()).isEqualTo("STABLE");
            assertThat(group.totalLag()).isEqualTo(3);
        });
    }

    @Test
    void describesAGroupWithMembersAndLag() {
        TopicPartition partition = new TopicPartition("pedidos360.order-events", 0);
        MemberDescription member = new MemberDescription(
                "consumer-1", "client-1", "/10.0.0.2", new MemberAssignment(Set.of(partition)));
        when(operations.describeConsumerGroup("catalog-sales-projection")).thenReturn(
                new ConsumerGroupDescription("catalog-sales-projection", false, List.of(member), "range",
                        ConsumerGroupState.STABLE, BROKER));
        when(operations.listConsumerGroupOffsets("catalog-sales-projection"))
                .thenReturn(Map.of(partition, new OffsetAndMetadata(5)));
        when(operations.logEndOffsets(anyCollection())).thenReturn(Map.of(partition, 9L));

        ConsumerGroupDetailInfo detail = service.describeConsumerGroup("catalog-sales-projection");

        assertThat(detail.state()).isEqualTo("STABLE");
        assertThat(detail.coordinator()).isEqualTo("kafka:9092");
        assertThat(detail.members()).singleElement().satisfies(view -> {
            assertThat(view.clientId()).isEqualTo("client-1");
            assertThat(view.assignments()).containsExactly("pedidos360.order-events-0");
        });
        assertThat(detail.offsets()).singleElement()
                .satisfies(lag -> assertThat(lag.lag()).isEqualTo(4));
    }

    @Test
    void refusesToResetOffsetsOfALiveGroup() {
        when(operations.describeConsumerGroup("catalog-sales-projection")).thenReturn(
                new ConsumerGroupDescription("catalog-sales-projection", false, List.of(), "range",
                        ConsumerGroupState.STABLE, BROKER));

        assertThatThrownBy(() -> service.resetOffsets(new OffsetResetSpec(
                "catalog-sales-projection", "pedidos360.order-events", null,
                OffsetResetSpec.Strategy.EARLIEST, null, null)))
                .isInstanceOf(GroupNotEmptyException.class);
        verify(operations, never()).alterConsumerGroupOffsets(anyString(), any());
    }

    @Test
    void requiresAnOffsetForTheExplicitStrategy() {
        emptyGroupWithOnePartition();

        assertThatThrownBy(() -> service.resetOffsets(new OffsetResetSpec(
                "catalog-sales-projection", "pedidos360.order-events", null,
                OffsetResetSpec.Strategy.OFFSET, null, null)))
                .isInstanceOf(InvalidTopicSpecException.class)
                .hasMessageContaining("offset");
    }

    @Test
    void resetsOffsetsToEarliestForAnEmptyGroup() {
        TopicPartition partition = emptyGroupWithOnePartition();
        when(operations.offsetsForSpec(anyCollection(), any(OffsetSpec.class)))
                .thenReturn(Map.of(partition, 0L));
        when(operations.logEndOffsets(anyCollection())).thenReturn(Map.of(partition, 4L));

        OffsetResetResult result = service.resetOffsets(new OffsetResetSpec(
                "catalog-sales-projection", "pedidos360.order-events", null,
                OffsetResetSpec.Strategy.EARLIEST, null, null));

        verify(operations).alterConsumerGroupOffsets("catalog-sales-projection",
                Map.of(partition, new OffsetAndMetadata(0)));
        assertThat(result.offsets()).singleElement().satisfies(lag -> {
            assertThat(lag.currentOffset()).isZero();
            assertThat(lag.logEndOffset()).isEqualTo(4);
        });
    }

    @Test
    void resetsOffsetsToATimestampWithEarliestFallback() {
        TopicPartition partition = emptyGroupWithOnePartition();
        when(operations.offsetsForSpec(anyCollection(), any(OffsetSpec.class)))
                .thenReturn(Map.of(partition, -1L), Map.of(partition, 0L));
        when(operations.logEndOffsets(anyCollection())).thenReturn(Map.of(partition, 4L));

        service.resetOffsets(new OffsetResetSpec(
                "catalog-sales-projection", "pedidos360.order-events", null,
                OffsetResetSpec.Strategy.TIMESTAMP, null, Instant.parse("2026-11-01T00:00:00Z")));

        verify(operations).alterConsumerGroupOffsets("catalog-sales-projection",
                Map.of(partition, new OffsetAndMetadata(0)));
    }

    private TopicPartition emptyGroupWithOnePartition() {
        TopicPartition partition = new TopicPartition("pedidos360.order-events", 0);
        when(operations.describeConsumerGroup("catalog-sales-projection")).thenReturn(
                new ConsumerGroupDescription("catalog-sales-projection", false, List.of(), "range",
                        ConsumerGroupState.EMPTY, BROKER));
        when(operations.topicPartitions("pedidos360.order-events")).thenReturn(Set.of(partition));
        return partition;
    }

    private static TopicDescription description(String name, int partitions) {
        List<TopicPartitionInfo> info = IntStream.range(0, partitions)
                .mapToObj(index -> new TopicPartitionInfo(index, BROKER, List.of(BROKER), List.of(BROKER)))
                .toList();
        return new TopicDescription(name, false, info);
    }
}
