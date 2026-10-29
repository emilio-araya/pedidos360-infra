package com.pedidos360.messagingadmin.admin;

/** The broker could not complete the operation now: connection lost or the request was refused. */
public class BrokerUnavailableException extends RuntimeException {

    public BrokerUnavailableException(String message) {
        super(message);
    }
}
