package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.kafka.OffsetResetResult;
import java.util.List;

/** Result of a consumer-group offset reset. */
public record OffsetResetResponse(String groupId, String topic, List<ConsumerGroupDetailResponse.TopicPartitionLagResponse> offsets) {

    public static OffsetResetResponse from(OffsetResetResult result) {
        return new OffsetResetResponse(
                result.groupId(),
                result.topic(),
                result.offsets().stream()
                        .map(ConsumerGroupDetailResponse.TopicPartitionLagResponse::from)
                        .toList());
    }
}
