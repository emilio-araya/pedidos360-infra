package com.pedidos360.messagingadmin.kafka;

/** Committed offset versus end of the log for one partition. */
public record TopicPartitionLag(String topic, int partition, long currentOffset, long logEndOffset, long lag) {
}
