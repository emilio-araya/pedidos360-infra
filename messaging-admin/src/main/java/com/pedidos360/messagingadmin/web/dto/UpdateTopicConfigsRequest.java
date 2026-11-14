package com.pedidos360.messagingadmin.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/** Body of {@code PATCH /api/admin/kafka/topics/{name}/configs}. */
public record UpdateTopicConfigsRequest(@NotEmpty Map<String, String> configs) {
}
