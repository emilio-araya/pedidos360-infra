package com.pedidos360.messagingadmin.kafka;

import java.util.List;
import java.util.Map;

/** A topic as this API returns it: metadata, overridden configs and partitions. */
public record TopicInfo(
        String name,
        boolean internal,
        Map<String, String> configs,
        List<PartitionView> partitions
) {

    public TopicInfo {
        configs = configs == null ? Map.of() : Map.copyOf(configs);
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
    }

    public int partitionCount() {
        return partitions.size();
    }

    public int replicationFactor() {
        return partitions.isEmpty() ? 0 : partitions.get(0).replicas().size();
    }
}
