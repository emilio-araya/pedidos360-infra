package com.pedidos360.messagingadmin.kafka;

import com.pedidos360.messagingadmin.admin.ProtectedResourceException;
import com.pedidos360.messagingadmin.config.KafkaAdminProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Kafka administration: topics, their configuration and consumer groups.
 *
 * <p>Names under {@link KafkaAdminProperties#reservedPrefixes()} belong to the
 * running system (for example {@code pedidos360.order-events}) and are never
 * created, changed or deleted, so this API cannot break order processing.
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);

    private static final Set<String> CLEANUP_POLICIES = Set.of("delete", "compact");

    private final KafkaOperations kafka;
    private final KafkaAdminProperties properties;

    public KafkaAdminService(KafkaOperations kafka, KafkaAdminProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    public TopicInfo createTopic(TopicSpec spec) {
        requireNotReserved(spec.name());
        validateTopicName(spec.name());
        int partitions = spec.partitions() == null ? properties.defaultPartitions() : spec.partitions();
        short replicationFactor = spec.replicationFactor() == null
                ? properties.defaultReplicationFactor()
                : spec.replicationFactor();
        requireValidPartitionCount(partitions);
        requireValidReplicationFactor(replicationFactor);
        validateConfigs(spec.configs(), replicationFactor);

        try {
            kafka.createTopic(new NewTopic(spec.name(), partitions, replicationFactor).configs(spec.configs()));
        } catch (TopicExistsException exception) {
            throw new TopicAlreadyExistsException(spec.name());
        } catch (InvalidReplicationFactorException exception) {
            throw new InvalidTopicSpecException(exception.getMessage());
        }
        log.info("Tópico {} creado con {} particiones y factor de replicación {}",
                spec.name(), partitions, replicationFactor);
        return describeTopic(spec.name());
    }

    public List<TopicInfo> listTopics(boolean includeInternal) {
        Set<String> names = kafka.listTopicNames(includeInternal);
        if (names.isEmpty()) {
            return List.of();
        }
        Map<String, TopicDescription> descriptions = describe(names);
        return names.stream()
                .sorted()
                .map(name -> toTopicInfo(descriptions.get(name), Map.of()))
                .toList();
    }

    public TopicInfo describeTopic(String name) {
        TopicDescription description;
        try {
            description = describe(List.of(name)).get(name);
        } catch (UnknownTopicOrPartitionException exception) {
            throw new TopicNotFoundException(name);
        }
        if (description == null) {
            throw new TopicNotFoundException(name);
        }
        Map<String, String> configs = kafka.describeTopicConfigs(name).values().stream()
                .filter(entry -> !entry.isDefault() && !entry.isSensitive())
                .collect(Collectors.toMap(
                        entry -> entry.name(),
                        entry -> entry.value() == null ? "" : entry.value(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        return toTopicInfo(description, configs);
    }

    public TopicInfo increasePartitions(String name, int partitions) {
        requireNotReserved(name);
        requireValidPartitionCount(partitions);
        TopicInfo current = describeTopic(name);
        int currentCount = current.partitionCount();
        if (partitions < currentCount) {
            throw new PartitionsCannotDecreaseException(name, currentCount, partitions);
        }
        if (partitions == currentCount) {
            return current;
        }
        try {
            kafka.createPartitions(name, partitions);
        } catch (InvalidPartitionsException exception) {
            throw new InvalidTopicSpecException(exception.getMessage());
        } catch (UnknownTopicOrPartitionException exception) {
            throw new TopicNotFoundException(name);
        }
        log.info("Tópico {} ampliado de {} a {} particiones", name, currentCount, partitions);
        return describeTopic(name);
    }

    public TopicInfo alterConfigs(String name, Map<String, String> configs) {
        requireNotReserved(name);
        if (configs == null || configs.isEmpty()) {
            throw new InvalidTopicSpecException("Debes indicar al menos una configuración");
        }
        TopicInfo current = describeTopic(name);
        validateConfigs(configs, (short) current.replicationFactor());
        try {
            kafka.alterTopicConfigs(name, configs);
        } catch (InvalidConfigurationException exception) {
            throw new InvalidTopicSpecException(exception.getMessage());
        } catch (UnknownTopicOrPartitionException exception) {
            throw new TopicNotFoundException(name);
        }
        log.info("Configuración del tópico {} actualizada: {}", name, configs.keySet());
        return describeTopic(name);
    }

    public void deleteTopic(String name) {
        requireNotReserved(name);
        try {
            kafka.deleteTopics(List.of(name));
        } catch (UnknownTopicOrPartitionException exception) {
            throw new TopicNotFoundException(name);
        }
        log.info("Tópico {} eliminado", name);
    }

    public List<ConsumerGroupInfo> listConsumerGroups() {
        return kafka.listConsumerGroups().stream()
                .sorted(Comparator.comparing(ConsumerGroupListing::groupId))
                .map(this::summarize)
                .toList();
    }

    private ConsumerGroupInfo summarize(ConsumerGroupListing listing) {
        ConsumerGroupDescription description = describeGroup(listing.groupId());
        long lag = totalLag(kafka.listConsumerGroupOffsets(listing.groupId()));
        return new ConsumerGroupInfo(
                listing.groupId(), description.state().name(), description.members().size(), lag);
    }

    public ConsumerGroupDetailInfo describeConsumerGroup(String groupId) {
        ConsumerGroupDescription description = describeGroup(groupId);
        Map<TopicPartition, OffsetAndMetadata> committed = kafka.listConsumerGroupOffsets(groupId);
        Map<TopicPartition, Long> endOffsets = committed.isEmpty()
                ? Map.of()
                : kafka.logEndOffsets(committed.keySet());
        List<TopicPartitionLag> offsets = committed.entrySet().stream()
                .map(entry -> lag(entry.getKey(), entry.getValue().offset(), endOffsets))
                .sorted(Comparator.comparing(TopicPartitionLag::topic).thenComparingInt(TopicPartitionLag::partition))
                .toList();
        List<MemberView> members = description.members().stream()
                .map(member -> new MemberView(
                        member.consumerId(),
                        member.clientId(),
                        member.host(),
                        member.assignment().topicPartitions().stream()
                                .map(partition -> partition.topic() + "-" + partition.partition())
                                .sorted()
                                .toList()))
                .toList();
        String coordinator = description.coordinator() == null
                ? null
                : description.coordinator().host() + ":" + description.coordinator().port();
        return new ConsumerGroupDetailInfo(
                groupId, description.state().name(), coordinator, members, offsets);
    }

    public OffsetResetResult resetOffsets(OffsetResetSpec spec) {
        ConsumerGroupDescription description = describeGroup(spec.groupId());
        if (description.state() != ConsumerGroupState.EMPTY) {
            throw new GroupNotEmptyException(spec.groupId(), description.state().name());
        }
        Set<TopicPartition> allPartitions;
        try {
            allPartitions = kafka.topicPartitions(spec.topic());
        } catch (UnknownTopicOrPartitionException exception) {
            throw new TopicNotFoundException(spec.topic());
        }
        List<TopicPartition> selected = selectPartitions(allPartitions, spec);
        Map<TopicPartition, Long> targets = resolveTargets(selected, spec);
        Map<TopicPartition, OffsetAndMetadata> assignment = new LinkedHashMap<>();
        targets.forEach((partition, offset) -> assignment.put(partition, new OffsetAndMetadata(offset)));
        kafka.alterConsumerGroupOffsets(spec.groupId(), assignment);

        Map<TopicPartition, Long> endOffsets = kafka.logEndOffsets(targets.keySet());
        List<TopicPartitionLag> result = targets.entrySet().stream()
                .map(entry -> lag(entry.getKey(), entry.getValue(), endOffsets))
                .sorted(Comparator.comparing(TopicPartitionLag::topic).thenComparingInt(TopicPartitionLag::partition))
                .toList();
        log.info("Offsets del grupo {} reiniciados en {} particiones del tópico {}",
                spec.groupId(), result.size(), spec.topic());
        return new OffsetResetResult(spec.groupId(), spec.topic(), result);
    }

    private ConsumerGroupDescription describeGroup(String groupId) {
        try {
            return kafka.describeConsumerGroup(groupId);
        } catch (GroupIdNotFoundException exception) {
            throw new ConsumerGroupNotFoundException(groupId);
        }
    }

    private long totalLag(Map<TopicPartition, OffsetAndMetadata> committed) {
        if (committed.isEmpty()) {
            return 0;
        }
        Map<TopicPartition, Long> endOffsets = kafka.logEndOffsets(committed.keySet());
        return committed.entrySet().stream()
                .mapToLong(entry -> computeLag(entry.getValue().offset(), endOffsets.get(entry.getKey())))
                .sum();
    }

    private TopicPartitionLag lag(TopicPartition partition, long currentOffset, Map<TopicPartition, Long> endOffsets) {
        long endOffset = endOffsets.getOrDefault(partition, Math.max(currentOffset, 0));
        return new TopicPartitionLag(
                partition.topic(), partition.partition(), currentOffset, endOffset,
                computeLag(currentOffset, endOffset));
    }

    private static long computeLag(long currentOffset, Long endOffset) {
        long end = endOffset == null ? Math.max(currentOffset, 0) : endOffset;
        return Math.max(0, end - Math.max(currentOffset, 0));
    }

    private List<TopicPartition> selectPartitions(Set<TopicPartition> allPartitions, OffsetResetSpec spec) {
        if (spec.partitions() == null || spec.partitions().isEmpty()) {
            return allPartitions.stream().sorted(Comparator.comparingInt(TopicPartition::partition)).toList();
        }
        Set<Integer> existing = allPartitions.stream()
                .map(TopicPartition::partition)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Integer partition : spec.partitions()) {
            if (!existing.contains(partition)) {
                throw new InvalidTopicSpecException(
                        "La partición " + partition + " no existe en " + spec.topic());
            }
        }
        return spec.partitions().stream()
                .distinct()
                .sorted()
                .map(partition -> new TopicPartition(spec.topic(), partition))
                .toList();
    }

    private Map<TopicPartition, Long> resolveTargets(List<TopicPartition> partitions, OffsetResetSpec spec) {
        return switch (spec.strategy()) {
            case EARLIEST -> new HashMap<>(kafka.offsetsForSpec(partitions, OffsetSpec.earliest()));
            case LATEST -> new HashMap<>(kafka.offsetsForSpec(partitions, OffsetSpec.latest()));
            case TIMESTAMP -> targetsForTimestamp(partitions, spec.timestamp());
            case OFFSET -> targetsForExplicitOffset(partitions, spec.offset());
        };
    }

    private Map<TopicPartition, Long> targetsForTimestamp(List<TopicPartition> partitions, Instant timestamp) {
        if (timestamp == null) {
            throw new InvalidTopicSpecException("La estrategia TIMESTAMP requiere un timestamp");
        }
        Map<TopicPartition, Long> offsets = new HashMap<>(
                kafka.offsetsForSpec(partitions, OffsetSpec.forTimestamp(timestamp.toEpochMilli())));
        List<TopicPartition> withoutMatch = offsets.entrySet().stream()
                .filter(entry -> entry.getValue() < 0)
                .map(Map.Entry::getKey)
                .toList();
        if (!withoutMatch.isEmpty()) {
            Map<TopicPartition, Long> earliest = kafka.offsetsForSpec(withoutMatch, OffsetSpec.earliest());
            withoutMatch.forEach(partition -> offsets.put(partition, earliest.getOrDefault(partition, 0L)));
        }
        return offsets;
    }

    private Map<TopicPartition, Long> targetsForExplicitOffset(List<TopicPartition> partitions, Long offset) {
        if (offset == null || offset < 0) {
            throw new InvalidTopicSpecException("La estrategia OFFSET requiere un offset mayor o igual a 0");
        }
        Map<TopicPartition, Long> targets = new LinkedHashMap<>();
        partitions.forEach(partition -> targets.put(partition, offset));
        return targets;
    }

    private Map<String, TopicDescription> describe(Collection<String> names) {
        try {
            return kafka.describeTopics(names);
        } catch (UnknownTopicOrPartitionException exception) {
            throw new TopicNotFoundException(String.join(", ", names));
        }
    }

    private static TopicInfo toTopicInfo(TopicDescription description, Map<String, String> configs) {
        List<PartitionView> partitions = description.partitions().stream()
                .map(partition -> new PartitionView(
                        partition.partition(),
                        partition.leader() == null ? -1 : partition.leader().id(),
                        partition.replicas().stream().map(node -> node.id()).toList(),
                        partition.isr().stream().map(node -> node.id()).toList()))
                .toList();
        return new TopicInfo(description.name(), description.isInternal(), configs, partitions);
    }

    private void validateTopicName(String name) {
        if (name == null || !name.matches(TopicNames.PATTERN)) {
            throw new InvalidTopicSpecException("El nombre del tópico " + TopicNames.MESSAGE);
        }
    }

    private void requireValidPartitionCount(int partitions) {
        if (partitions < 1 || partitions > properties.maxPartitions()) {
            throw new InvalidTopicSpecException(
                    "Las particiones deben estar entre 1 y " + properties.maxPartitions());
        }
    }

    private void requireValidReplicationFactor(short replicationFactor) {
        int brokers = kafka.clusterNodes().size();
        if (replicationFactor < 1 || replicationFactor > brokers) {
            throw new InvalidTopicSpecException(
                    "El factor de replicación debe estar entre 1 y " + brokers + " (brokers disponibles)");
        }
    }

    private void validateConfigs(Map<String, String> configs, short replicationFactor) {
        if (configs == null || configs.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!properties.allowedConfigs().contains(key)) {
                throw new InvalidTopicSpecException("Configuración no permitida: " + key
                        + "; permitidas: " + String.join(", ", properties.allowedConfigs()));
            }
            if (value == null) {
                throw new InvalidTopicSpecException("La configuración " + key + " requiere un valor");
            }
            switch (key) {
                case "retention.ms" -> requireLong(key, value, -1, 1000);
                case "retention.bytes" -> requireLong(key, value, -1, 0);
                case "segment.ms" -> requireLong(key, value, -1, 60000);
                case "max.message.bytes" -> requireLong(key, value, -1, 1024);
                case "min.insync.replicas" -> requireMinInsyncReplicas(value, replicationFactor);
                case "cleanup.policy" -> requireCleanupPolicy(value);
                default -> throw new InvalidTopicSpecException("Configuración no permitida: " + key);
            }
        }
    }

    /** {@code minimum} applies to every value except {@code -1} (Kafka's "unlimited"). */
    private static void requireLong(String key, String value, long unlimited, long minimum) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidTopicSpecException(key + " debe ser un número entero");
        }
        if (parsed != unlimited && parsed < minimum) {
            throw new InvalidTopicSpecException(key + " debe ser " + unlimited + " o mayor o igual a " + minimum);
        }
    }

    private static void requireMinInsyncReplicas(String value, short replicationFactor) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidTopicSpecException("min.insync.replicas debe ser un número entero");
        }
        if (parsed < 1 || parsed > replicationFactor) {
            throw new InvalidTopicSpecException("min.insync.replicas debe estar entre 1 y "
                    + replicationFactor + " (factor de replicación del tópico)");
        }
    }

    private static void requireCleanupPolicy(String value) {
        List<String> policies = Arrays.stream(value.split(","))
                .map(policy -> policy.trim().toLowerCase(Locale.ROOT))
                .filter(policy -> !policy.isEmpty())
                .toList();
        if (policies.isEmpty() || !CLEANUP_POLICIES.containsAll(policies)) {
            throw new InvalidTopicSpecException("cleanup.policy debe ser delete, compact o compact,delete");
        }
    }

    private void requireNotReserved(String name) {
        if (properties.isReserved(name)) {
            throw new ProtectedResourceException(name);
        }
    }
}
