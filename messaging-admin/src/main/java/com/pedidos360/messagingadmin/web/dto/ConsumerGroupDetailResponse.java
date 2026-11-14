package com.pedidos360.messagingadmin.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pedidos360.messagingadmin.kafka.ConsumerGroupDetailInfo;
import com.pedidos360.messagingadmin.kafka.MemberView;
import com.pedidos360.messagingadmin.kafka.TopicPartitionLag;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsumerGroupDetailResponse(
        String groupId,
        String state,
        String coordinator,
        List<MemberResponse> members,
        List<TopicPartitionLagResponse> offsets
) {

    public static ConsumerGroupDetailResponse from(ConsumerGroupDetailInfo info) {
        return new ConsumerGroupDetailResponse(
                info.groupId(),
                info.state(),
                info.coordinator(),
                info.members().stream().map(MemberResponse::from).toList(),
                info.offsets().stream().map(TopicPartitionLagResponse::from).toList());
    }

    public record MemberResponse(String memberId, String clientId, String host, List<String> assignments) {

        static MemberResponse from(MemberView view) {
            return new MemberResponse(view.memberId(), view.clientId(), view.host(), view.assignments());
        }
    }

    public record TopicPartitionLagResponse(
            String topic, int partition, long currentOffset, long logEndOffset, long lag) {

        static TopicPartitionLagResponse from(TopicPartitionLag lag) {
            return new TopicPartitionLagResponse(
                    lag.topic(), lag.partition(), lag.currentOffset(), lag.logEndOffset(), lag.lag());
        }
    }
}
