package com.pedidos360.messagingadmin.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Body of {@code POST /api/admin/kafka/topics/{name}/partitions}. */
public record IncreasePartitionsRequest(@Min(1) @Max(1000) int partitions) {
}
