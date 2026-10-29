package com.pedidos360.messagingadmin.admin;

public class QueueNotFoundException extends RuntimeException {

    public QueueNotFoundException(String name) {
        super("La cola " + name + " no existe");
    }
}
