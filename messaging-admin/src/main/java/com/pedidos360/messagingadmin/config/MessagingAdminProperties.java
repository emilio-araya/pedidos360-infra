package com.pedidos360.messagingadmin.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every RabbitMQ name this service relies on, in one place ({@code app.messaging.admin.*}).
 *
 * @param deadLetterExchange exchange that receives rejected messages of queues created here
 * @param deadLetterSuffix   suffix of the dead-letter queue created next to each queue
 * @param reservedPrefixes   name prefixes owned by the running system: never created,
 *                           changed or deleted through this API
 * @param bindableExchanges  system exchanges that new queues may still bind to
 */
@ConfigurationProperties(prefix = "app.messaging.admin")
public record MessagingAdminProperties(
        String deadLetterExchange,
        String deadLetterSuffix,
        List<String> reservedPrefixes,
        List<String> bindableExchanges
) {

    public MessagingAdminProperties {
        reservedPrefixes = reservedPrefixes == null ? List.of() : List.copyOf(reservedPrefixes);
        bindableExchanges = bindableExchanges == null ? List.of() : List.copyOf(bindableExchanges);
    }

    public boolean isReserved(String name) {
        return reservedPrefixes.stream().anyMatch(name::startsWith);
    }
}
