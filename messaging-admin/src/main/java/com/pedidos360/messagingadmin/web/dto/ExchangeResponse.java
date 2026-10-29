package com.pedidos360.messagingadmin.web.dto;

import com.pedidos360.messagingadmin.admin.ExchangeSpec;

public record ExchangeResponse(String name, ExchangeSpec.Type type, boolean durable) {

    public static ExchangeResponse from(ExchangeSpec spec) {
        return new ExchangeResponse(spec.name(), spec.type(), spec.durable());
    }
}
