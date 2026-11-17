package com.pedidos360.messagingadmin.kafka;

import java.util.List;
import java.util.Map;

/**
 * What the API needs to create a topic. {@code partitions} and
 * {@code replicationFactor} are nullable: the service applies its defaults.
 */
public record TopicSpec(String name, Integer partitions, Short replicationFactor, Map<String, String> configs) {

    public TopicSpec {
        configs = configs == null ? Map.of() : Map.copyOf(configs);
    }
}
