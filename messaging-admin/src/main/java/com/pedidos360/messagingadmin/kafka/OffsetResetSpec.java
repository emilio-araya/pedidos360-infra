package com.pedidos360.messagingadmin.kafka;

import java.time.Instant;
import java.util.List;

/**
 * Where to move a consumer group's offsets. {@code partitions} is nullable:
 * null means every partition of the topic.
 */
public record OffsetResetSpec(
        String groupId,
        String topic,
        List<Integer> partitions,
        Strategy strategy,
        Long offset,
        Instant timestamp
) {

    public enum Strategy { EARLIEST, LATEST, OFFSET, TIMESTAMP }

    public OffsetResetSpec {
        partitions = partitions == null ? null : List.copyOf(partitions);
    }
}
