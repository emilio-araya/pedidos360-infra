package com.pedidos360.messagingadmin.kafka;

public class TopicNotFoundException extends RuntimeException {

    public TopicNotFoundException(String name) {
        super("El tópico " + name + " no existe");
    }
}
