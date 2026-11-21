package com.promo.engine.application.port.in;

import com.promo.engine.domain.model.AuthenticatedPrincipal;
import com.promo.engine.domain.model.DecisionSnapshot;

import java.util.Optional;

public interface GetOrderSnapshotUseCase {
    Optional<DecisionSnapshot> findByOrderId(AuthenticatedPrincipal principal, String orderId);
}
