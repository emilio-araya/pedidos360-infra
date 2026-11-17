package com.pedidos360.messagingadmin.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One shared {@link AdminClient} built from {@code spring.kafka.*}. */
@Configuration(proxyBeanMethods = false)
class KafkaAdminConfiguration {

    @Bean(destroyMethod = "close")
    AdminClient kafkaAdminClient(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        config.putAll(properties.getAdmin().getProperties());
        return AdminClient.create(config);
    }
}
