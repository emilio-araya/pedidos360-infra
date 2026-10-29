package com.pedidos360.messagingadmin.admin;

public class ProtectedResourceException extends RuntimeException {

    public ProtectedResourceException(String name) {
        super(name + " pertenece al sistema y no se puede crear, modificar ni eliminar desde esta API");
    }
}
