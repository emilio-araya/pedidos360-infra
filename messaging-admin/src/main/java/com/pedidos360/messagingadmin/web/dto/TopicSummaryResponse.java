package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.kafka.TopicInfo;

/** One row of {@code GET /api/admin/kafka/topics}. */
public record TopicSummaryResponse(String name, int partitions, int replicationFactor, boolean internal) {

    public static TopicSummaryResponse from(TopicInfo info) {
        return new TopicSummaryResponse(
                info.name(), info.partitionCount(), info.replicationFactor(), info.internal());
    }
}
