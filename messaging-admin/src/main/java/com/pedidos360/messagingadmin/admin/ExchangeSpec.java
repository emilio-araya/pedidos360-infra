package com.pedidos360.messagingadmin.admin;

public record ExchangeSpec(String name, Type type, boolean durable) {

    public enum Type { DIRECT, TOPIC, FANOUT, HEADERS }
}
