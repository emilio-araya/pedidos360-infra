package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.kafka.TopicNames;
import com.pedidos360.messagingadmin.kafka.TopicSpec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/** Body of {@code POST /api/admin/kafka/topics}. */
public record CreateTopicRequest(
        @NotBlank
        @Pattern(regexp = TopicNames.PATTERN, message = TopicNames.MESSAGE)
        String name,
        @Min(1) @Max(1000) Integer partitions,
        @Min(1) Short replicationFactor,
        Map<String, String> configs
) {

    public TopicSpec toSpec() {
        return new TopicSpec(name, partitions, replicationFactor, configs);
    }
}
