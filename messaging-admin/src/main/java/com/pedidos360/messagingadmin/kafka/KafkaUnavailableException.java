package com.pedidos360.messagingadmin.kafka;

/** Kafka could not complete the operation now: connection, timeout or broker refusal. */
public class KafkaUnavailableException extends RuntimeException {

    public KafkaUnavailableException(String message) {
        super(message);
    }
}
