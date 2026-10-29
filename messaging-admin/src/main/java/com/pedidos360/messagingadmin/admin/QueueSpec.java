package com.pedidos360.messagingadmin.admin;

/** What to create: already validated, free of HTTP concerns. */
public record QueueSpec(
        String name,
        boolean durable,
        boolean deadLetter,
        Type type,
        Long messageTtlMs,
        Integer maxLength
) {

    public enum Type { CLASSIC, QUORUM }
}
