package com.pedidos360.messagingadmin.kafka;

import com.pedidos360.messagingadmin.config.KafkaAdminProperties;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.stereotype.Component;

/**
 * The only class that talks to {@link AdminClient}. Every call is bounded by
 * {@code app.messaging.admin.kafka.operation-timeout} and Kafka failures are
 * translated to {@link KafkaUnavailableException}, except the typed exceptions
 * the service already knows how to interpret.
 */
@Component
public class KafkaAdminClientOperations implements KafkaOperations {

    private final AdminClient adminClient;
    private final long timeoutMs;

    public KafkaAdminClientOperations(AdminClient adminClient, KafkaAdminProperties properties) {
        this.adminClient = adminClient;
        this.timeoutMs = properties.operationTimeout().toMillis();
    }

    @Override
    public Set<String> listTopicNames(boolean includeInternal) {
        return await(adminClient.listTopics(new ListTopicsOptions().listInternal(includeInternal)).names());
    }

    @Override
    public Map<String, TopicDescription> describeTopics(Collection<String> names) {
        return await(adminClient.describeTopics(names).all());
    }

    @Override
    public Collection<Node> clusterNodes() {
        return await(adminClient.describeCluster().nodes());
    }

    @Override
    public void createTopic(NewTopic topic) {
        await(adminClient.createTopics(List.of(topic)).all());
    }

    @Override
    public void deleteTopics(Collection<String> names) {
        await(adminClient.deleteTopics(names).all());
    }

    @Override
    public void createPartitions(String topic, int totalCount) {
        await(adminClient.createPartitions(Map.of(topic, NewPartitions.increaseTo(totalCount))).all());
    }

    @Override
    public Map<String, ConfigEntry> describeTopicConfigs(String topic) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config config = await(adminClient.describeConfigs(List.of(resource)).all()).get(resource);
        if (config == null) {
            throw new TopicNotFoundException(topic);
        }
        Map<String, ConfigEntry> entries = new LinkedHashMap<>();
        config.entries().forEach(entry -> entries.put(entry.name(), entry));
        return entries;
    }

    @Override
    public void alterTopicConfigs(String topic, Map<String, String> configs) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        List<AlterConfigOp> operations = configs.entrySet().stream()
                .map(entry -> new AlterConfigOp(
                        new ConfigEntry(entry.getKey(), entry.getValue()), AlterConfigOp.OpType.SET))
                .toList();
        await(adminClient.incrementalAlterConfigs(Map.of(resource, operations)).all());
    }

    @Override
    public Collection<ConsumerGroupListing> listConsumerGroups() {
        return await(adminClient.listConsumerGroups().all());
    }

    @Override
    public ConsumerGroupDescription describeConsumerGroup(String groupId) {
        ConsumerGroupDescription description = await(
                adminClient.describeConsumerGroups(List.of(groupId)).all()).get(groupId);
        if (description == null) {
            throw new ConsumerGroupNotFoundException(groupId);
        }
        return description;
    }

    @Override
    public Map<TopicPartition, OffsetAndMetadata> listConsumerGroupOffsets(String groupId) {
        return await(adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata());
    }

    @Override
    public Map<TopicPartition, Long> logEndOffsets(Collection<TopicPartition> partitions) {
        return offsets(partitions, OffsetSpec.latest());
    }

    @Override
    public Map<TopicPartition, Long> offsetsForSpec(Collection<TopicPartition> partitions, OffsetSpec spec) {
        return offsets(partitions, spec);
    }

    private Map<TopicPartition, Long> offsets(Collection<TopicPartition> partitions, OffsetSpec spec) {
        if (partitions.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> request = partitions.stream()
                .collect(Collectors.toMap(partition -> partition, partition -> spec));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> described =
                await(adminClient.listOffsets(request).all());
        Map<TopicPartition, Long> offsets = new LinkedHashMap<>();
        described.forEach((partition, info) -> offsets.put(partition, info.offset()));
        return offsets;
    }

    @Override
    public Set<TopicPartition> topicPartitions(String topic) {
        TopicDescription description = await(
                adminClient.describeTopics(List.of(topic)).all()).get(topic);
        if (description == null) {
            throw new TopicNotFoundException(topic);
        }
        Set<TopicPartition> partitions = new LinkedHashSet<>();
        description.partitions().forEach(partition -> partitions.add(new TopicPartition(topic, partition.partition())));
        return partitions;
    }

    @Override
    public void alterConsumerGroupOffsets(String groupId, Map<TopicPartition, OffsetAndMetadata> offsets) {
        await(adminClient.alterConsumerGroupOffsets(groupId, offsets).all());
    }

    private <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new KafkaUnavailableException("Kafka no respondió en " + timeoutMs + " ms");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KafkaUnavailableException("Interrumpido esperando a Kafka");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new KafkaUnavailableException("Kafka rechazó la operación: " + exception.getCause());
        }
    }
}
