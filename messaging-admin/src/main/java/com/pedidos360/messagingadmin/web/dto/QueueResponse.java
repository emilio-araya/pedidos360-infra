package com.pedidos360.messagingadmin.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pedidos360.messagingadmin.admin.QueueInfo;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueResponse(String name, String deadLetterQueue, int messageCount, int consumerCount) {

    public static QueueResponse from(QueueInfo info) {
        return new QueueResponse(info.name(), info.deadLetterQueue(), info.messageCount(), info.consumerCount());
    }
}
