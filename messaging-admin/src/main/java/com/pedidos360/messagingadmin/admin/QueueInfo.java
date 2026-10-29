package com.pedidos360.messagingadmin.admin;

/** {@code deadLetterQueue} is null when the queue was created without one or is only being read. */
public record QueueInfo(String name, String deadLetterQueue, int messageCount, int consumerCount) {
}
