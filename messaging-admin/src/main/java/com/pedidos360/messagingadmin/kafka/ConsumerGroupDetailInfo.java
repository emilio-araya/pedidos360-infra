package com.pedidos360.messagingadmin.kafka;

import java.util.List;

/** Consumer group detail: members, assignments and per-partition lag. */
public record ConsumerGroupDetailInfo(
        String groupId,
        String state,
        String coordinator,
        List<MemberView> members,
        List<TopicPartitionLag> offsets
) {

    public ConsumerGroupDetailInfo {
        members = members == null ? List.of() : List.copyOf(members);
        offsets = offsets == null ? List.of() : List.copyOf(offsets);
    }
}
