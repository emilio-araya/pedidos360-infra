package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.kafka.OffsetResetResult;
import com.pedidos360.messagingadmin.kafka.OffsetResetSpec;
import com.pedidos360.messagingadmin.kafka.TopicNames;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

/** Body of {@code POST /api/admin/kafka/consumer-groups/{groupId}/offsets/reset}. */
public record OffsetResetRequest(
        @NotBlank
        @Pattern(regexp = TopicNames.PATTERN, message = TopicNames.MESSAGE)
        String topic,
        List<Integer> partitions,
        @NotNull OffsetResetSpec.Strategy strategy,
        Long offset,
        Instant timestamp
) {

    public OffsetResetSpec toSpec(String groupId) {
        return new OffsetResetSpec(groupId, topic, partitions, strategy, offset, timestamp);
    }
}
