package com.promo.engine.application.port.in;

import com.promo.engine.domain.model.AuthenticatedPrincipal;
import com.promo.engine.domain.model.CartItem;
import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.Money;

import java.util.List;

public interface EvaluatePromotionsUseCase {

    EvaluationQuote evaluate(EvaluateCommand command);

    record EvaluateCommand(
            AuthenticatedPrincipal principal,
            String targetUserId,
            Money grossAmount,
            Money eligibleAmount,
            List<CartItem> items,
            ConflictPolicy conflictPolicyOverride,
            String correlationId,
            String traceId,
            String channel
    ) {}
}
