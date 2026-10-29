package com.pedidos360.messagingadmin.web.dto;

/** Shared validation rules for queue and exchange names. */
public final class ResourceNames {

    /**
     * Starts with a letter or digit, then letters, digits, dots, dashes or
     * underscores. The {@code amq.} prefix is reserved by RabbitMQ itself.
     */
    public static final String PATTERN = "^(?!amq\\.)[A-Za-z0-9][A-Za-z0-9._-]*$";
    public static final String MESSAGE =
            "debe empezar con una letra o un número y usar solo letras, números, puntos, guiones o guiones bajos; el prefijo amq. está reservado";
    public static final int MAX_LENGTH = 200;

    private ResourceNames() {
    }
}
