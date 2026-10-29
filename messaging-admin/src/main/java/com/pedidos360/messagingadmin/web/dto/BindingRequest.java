package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.admin.BindingSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/admin/bindings}. The routing key may be empty (fanout)
 * and may use the topic wildcards {@code *} and {@code #}.
 */
public record BindingRequest(
        @NotBlank(message = "El exchange es obligatorio")
        @Pattern(regexp = ResourceNames.PATTERN, message = "El exchange " + ResourceNames.MESSAGE)
        String exchange,
        @NotBlank(message = "La cola es obligatoria")
        @Pattern(regexp = ResourceNames.PATTERN, message = "La cola " + ResourceNames.MESSAGE)
        String queue,
        @NotNull(message = "La routing key es obligatoria; usa \"\" para un exchange fanout")
        @Size(max = 255, message = "La routing key no puede superar 255 caracteres")
        @Pattern(regexp = "^[A-Za-z0-9._*#-]*$",
                message = "La routing key solo admite letras, números, puntos, guiones, guiones bajos, * y #")
        String routingKey
) {

    public BindingSpec toSpec() {
        return new BindingSpec(exchange, queue, routingKey);
    }
}
