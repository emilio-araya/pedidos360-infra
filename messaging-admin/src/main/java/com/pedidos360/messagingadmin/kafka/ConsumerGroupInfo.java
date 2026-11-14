package com.pedidos360.messagingadmin.kafka;

/** Consumer group summary: state, member count and accumulated lag. */
public record ConsumerGroupInfo(String groupId, String state, int members, long totalLag) {
}
