package com.pedidos360.messagingadmin.kafka;

import java.util.List;

/** Offsets committed after a reset. */
public record OffsetResetResult(String groupId, String topic, List<TopicPartitionLag> offsets) {

    public OffsetResetResult {
        offsets = offsets == null ? List.of() : List.copyOf(offsets);
    }
}
