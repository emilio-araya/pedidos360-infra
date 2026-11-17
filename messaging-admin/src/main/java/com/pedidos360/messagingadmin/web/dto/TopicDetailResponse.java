package com.pedidos360.messagingadmin.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pedidos360.messagingadmin.kafka.PartitionView;
import com.pedidos360.messagingadmin.kafka.TopicInfo;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicDetailResponse(
        String name,
        boolean internal,
        Map<String, String> configs,
        List<PartitionResponse> partitions
) {

    public static TopicDetailResponse from(TopicInfo info) {
        return new TopicDetailResponse(
                info.name(),
                info.internal(),
                info.configs(),
                info.partitions().stream().map(PartitionResponse::from).toList());
    }

    public record PartitionResponse(int id, int leaderId, List<Integer> replicas, List<Integer> isr) {

        static PartitionResponse from(PartitionView view) {
            return new PartitionResponse(view.id(), view.leaderId(), view.replicas(), view.isr());
        }
    }
}
