package com.pedidos360.messagingadmin.kafka;

import java.util.List;

/** One partition: leader and replica brokers by id. */
public record PartitionView(int id, int leaderId, List<Integer> replicas, List<Integer> isr) {

    public PartitionView {
        replicas = replicas == null ? List.of() : List.copyOf(replicas);
        isr = isr == null ? List.of() : List.copyOf(isr);
    }
}
