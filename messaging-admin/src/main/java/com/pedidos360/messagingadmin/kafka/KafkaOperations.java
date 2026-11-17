package com.pedidos360.messagingadmin.kafka;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;

/**
 * Everything this service asks Kafka to do. The implementation is the only
 * class that touches {@link org.apache.kafka.clients.admin.AdminClient}, so the
 * business rules can be tested without a broker.
 */
public interface KafkaOperations {

    Set<String> listTopicNames(boolean includeInternal);

    Map<String, TopicDescription> describeTopics(Collection<String> names);

    Collection<Node> clusterNodes();

    void createTopic(NewTopic topic);

    void deleteTopics(Collection<String> names);

    void createPartitions(String topic, int totalCount);

    Map<String, ConfigEntry> describeTopicConfigs(String topic);

    void alterTopicConfigs(String topic, Map<String, String> configs);

    Collection<ConsumerGroupListing> listConsumerGroups();

    ConsumerGroupDescription describeConsumerGroup(String groupId);

    Map<TopicPartition, OffsetAndMetadata> listConsumerGroupOffsets(String groupId);

    Map<TopicPartition, Long> logEndOffsets(Collection<TopicPartition> partitions);

    Map<TopicPartition, Long> offsetsForSpec(Collection<TopicPartition> partitions, OffsetSpec spec);

    Set<TopicPartition> topicPartitions(String topic);

    void alterConsumerGroupOffsets(String groupId, Map<TopicPartition, OffsetAndMetadata> offsets);
}
