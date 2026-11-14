package com.pedidos360.messagingadmin.kafka;

public class GroupNotEmptyException extends RuntimeException {

    public GroupNotEmptyException(String groupId, String state) {
        super("El grupo " + groupId + " está en estado " + state
                + "; detén sus consumidores antes de reiniciar los offsets");
    }
}
