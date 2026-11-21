package com.promo.engine.application.port.in;

import com.promo.engine.domain.model.AuthenticatedPrincipal;
import com.promo.engine.domain.model.DecisionSnapshot;

public interface CommitPromotionsUseCase {

    CommitResult commit(CommitCommand command);

    record CommitCommand(
            AuthenticatedPrincipal principal,
            String orderId,
            String evaluationId,
            String checkoutKey,
            String correlationId,
            String traceId,
            String channel
    ) {}

    record CommitResult(
            DecisionSnapshot snapshot,
            boolean idempotentReplay
    ) {}
}
