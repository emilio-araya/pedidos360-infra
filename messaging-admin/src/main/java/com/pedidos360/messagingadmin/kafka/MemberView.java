package com.pedidos360.messagingadmin.kafka;

import java.util.List;

/** One group member and the topic-partitions it was assigned. */
public record MemberView(String memberId, String clientId, String host, List<String> assignments) {

    public MemberView {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }
}
