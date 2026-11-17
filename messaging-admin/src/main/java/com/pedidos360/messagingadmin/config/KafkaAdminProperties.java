package com.pedidos360.messagingadmin.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka settings of the admin API ({@code app.messaging.admin.kafka.*}).
 *
 * @param reservedPrefixes prefixes owned by the running system: readable, never
 *                         created, changed or deleted through this API
 * @param allowedConfigs   topic configuration keys this API may change
 * @param maxPartitions    upper bound accepted when creating or growing a topic
 * @param defaultPartitions partitions used when the request omits them
 * @param defaultReplicationFactor replication factor used when the request omits it
 * @param operationTimeout how long one AdminClient call may take
 */
@ConfigurationProperties(prefix = "app.messaging.admin.kafka")
public record KafkaAdminProperties(
        List<String> reservedPrefixes,
        List<String> allowedConfigs,
        int maxPartitions,
        int defaultPartitions,
        short defaultReplicationFactor,
        Duration operationTimeout
) {

    public KafkaAdminProperties {
        reservedPrefixes = reservedPrefixes == null ? List.of() : List.copyOf(reservedPrefixes);
        allowedConfigs = allowedConfigs == null ? List.of() : List.copyOf(allowedConfigs);
        maxPartitions = maxPartitions <= 0 ? 1000 : maxPartitions;
        defaultPartitions = defaultPartitions <= 0 ? 3 : defaultPartitions;
        defaultReplicationFactor = defaultReplicationFactor <= 0 ? (short) 1 : defaultReplicationFactor;
        operationTimeout = operationTimeout == null ? Duration.ofSeconds(10) : operationTimeout;
    }

    public boolean isReserved(String name) {
        return name != null && reservedPrefixes.stream().anyMatch(name::startsWith);
    }
}
