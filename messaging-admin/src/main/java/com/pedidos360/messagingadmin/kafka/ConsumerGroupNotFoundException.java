package com.pedidos360.messagingadmin.kafka;

public class ConsumerGroupNotFoundException extends RuntimeException {

    public ConsumerGroupNotFoundException(String groupId) {
        super("El grupo de consumidores " + groupId + " no existe");
    }
}
