package com.pedidos360.messagingadmin.kafka;

public class TopicAlreadyExistsException extends RuntimeException {

    public TopicAlreadyExistsException(String name) {
        super("El tópico " + name + " ya existe");
    }
}
