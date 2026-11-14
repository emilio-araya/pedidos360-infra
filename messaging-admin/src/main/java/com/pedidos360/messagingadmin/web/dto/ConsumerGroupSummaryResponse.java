package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.kafka.ConsumerGroupInfo;

/** One row of {@code GET /api/admin/kafka/consumer-groups}. */
public record ConsumerGroupSummaryResponse(String groupId, String state, int members, long totalLag) {

    public static ConsumerGroupSummaryResponse from(ConsumerGroupInfo info) {
        return new ConsumerGroupSummaryResponse(info.groupId(), info.state(), info.members(), info.totalLag());
    }
}
