package com.pedidos360.messagingadmin.kafka;

public class PartitionsCannotDecreaseException extends RuntimeException {

    public PartitionsCannotDecreaseException(String topic, int current, int requested) {
        super("El tópico " + topic + " tiene " + current
                + " particiones: Kafka solo permite aumentarlas, no reducirlas a " + requested);
    }
}
