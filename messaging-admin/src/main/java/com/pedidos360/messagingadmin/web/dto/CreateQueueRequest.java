package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.admin.QueueSpec;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/admin/queues}. Only {@code name} is required: the queue is
 * durable, classic and gets a dead-letter queue unless told otherwise.
 */
public record CreateQueueRequest(
        @NotBlank(message = "El nombre de la cola es obligatorio")
        @Size(max = ResourceNames.MAX_LENGTH, message = "El nombre no puede superar 200 caracteres")
        @Pattern(regexp = ResourceNames.PATTERN, message = "El nombre " + ResourceNames.MESSAGE)
        String name,
        Boolean durable,
        Boolean deadLetter,
        QueueSpec.Type type,
        @Positive(message = "El TTL debe ser mayor que 0 milisegundos")
        @Max(value = 604_800_000L, message = "El TTL no puede superar 7 días (604800000 ms)")
        Long messageTtlMs,
        @Positive(message = "El largo máximo debe ser mayor que 0")
        @Max(value = 1_000_000, message = "El largo máximo no puede superar 1000000 mensajes")
        Integer maxLength
) {

    /** RabbitMQ rejects non-durable quorum queues: catch it before reaching the broker. */
    @AssertTrue(message = "Una cola QUORUM debe ser durable")
    public boolean isDurableWhenRequired() {
        return resolvedType() != QueueSpec.Type.QUORUM || resolvedDurable();
    }

    public QueueSpec toSpec() {
        return new QueueSpec(name, resolvedDurable(), deadLetter == null || deadLetter, resolvedType(), messageTtlMs, maxLength);
    }

    private boolean resolvedDurable() {
        return durable == null || durable;
    }

    private QueueSpec.Type resolvedType() {
        return type == null ? QueueSpec.Type.CLASSIC : type;
    }
}
