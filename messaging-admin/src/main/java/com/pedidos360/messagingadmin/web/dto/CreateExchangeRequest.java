package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.admin.ExchangeSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/admin/exchanges}. */
public record CreateExchangeRequest(
        @NotBlank(message = "El nombre del exchange es obligatorio")
        @Size(max = ResourceNames.MAX_LENGTH, message = "El nombre no puede superar 200 caracteres")
        @Pattern(regexp = ResourceNames.PATTERN, message = "El nombre " + ResourceNames.MESSAGE)
        String name,
        @NotNull(message = "El tipo es obligatorio: DIRECT, TOPIC, FANOUT o HEADERS")
        ExchangeSpec.Type type,
        Boolean durable
) {

    public ExchangeSpec toSpec() {
        return new ExchangeSpec(name, type, durable == null || durable);
    }
}
