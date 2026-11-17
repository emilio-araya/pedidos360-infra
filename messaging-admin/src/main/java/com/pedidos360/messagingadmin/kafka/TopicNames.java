package com.pedidos360.messagingadmin.kafka;

/** Shared validation rules for Kafka topic and consumer-group names. */
public final class TopicNames {

    /**
     * Kafka allows letters, digits, dots, dashes and underscores, up to 249
     * characters. A name made only of dots is reserved by Kafka itself.
     */
    public static final String PATTERN = "^(?!\\.$)(?!\\.\\.$)[A-Za-z0-9._-]{1,249}$";
    public static final String MESSAGE =
            "debe tener entre 1 y 249 caracteres y usar solo letras, números, puntos, guiones o guiones bajos";

    private TopicNames() {
    }
}
