package com.pedidos360.messagingadmin.kafka;

public class InvalidTopicSpecException extends RuntimeException {

    public InvalidTopicSpecException(String message) {
        super(message);
    }
}
