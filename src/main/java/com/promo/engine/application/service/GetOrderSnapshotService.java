package com.promo.engine.application.service;

import com.promo.engine.application.port.in.GetOrderSnapshotUseCase;
import com.promo.engine.application.port.out.CommitTransactionPort;
import com.promo.engine.domain.exception.EvaluationOwnershipException;
import com.promo.engine.domain.model.AuthenticatedPrincipal;
import com.promo.engine.domain.model.DecisionSnapshot;

import java.util.Optional;

public final class GetOrderSnapshotService implements GetOrderSnapshotUseCase {

    private static final String S2S_SCOPE = "promotions:checkout:s2s";
    private static final String ADMIN_SCOPE = "promotions:admin:write";

    private final CommitTransactionPort commitTransactionPort;

    public GetOrderSnapshotService(CommitTransactionPort commitTransactionPort) {
        this.commitTransactionPort = commitTransactionPort;
    }

    @Override
    public Optional<DecisionSnapshot> findByOrderId(AuthenticatedPrincipal principal, String orderId) {
        Optional<DecisionSnapshot> snapshotOpt = commitTransactionPort.findOrderSnapshot(orderId);
        snapshotOpt.ifPresent(snapshot -> {
            boolean privileged = principal.hasScope(S2S_SCOPE) || principal.hasScope(ADMIN_SCOPE);
            if (!privileged && !principal.userId().equals(snapshot.userId())) {
                throw new EvaluationOwnershipException("orderId=" + orderId);
            }
        });
        return snapshotOpt;
    }
}
