package com.promo.engine.application.service;

import com.promo.engine.application.port.in.GetOrderSnapshotUseCase;
import com.promo.engine.application.port.out.CommitTransactionPort;
import com.promo.engine.domain.model.DecisionSnapshot;

import java.util.Optional;

public final class GetOrderSnapshotService implements GetOrderSnapshotUseCase {

    private final CommitTransactionPort commitTransactionPort;

    public GetOrderSnapshotService(CommitTransactionPort commitTransactionPort) {
        this.commitTransactionPort = commitTransactionPort;
    }

    @Override
    public Optional<DecisionSnapshot> findByOrderId(String orderId) {
        return commitTransactionPort.findOrderSnapshot(orderId);
    }
}
