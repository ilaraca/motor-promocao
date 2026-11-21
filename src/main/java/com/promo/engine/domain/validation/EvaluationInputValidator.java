package com.promo.engine.domain.validation;

import com.promo.engine.domain.exception.InvalidEvaluationRequestException;
import com.promo.engine.domain.model.CartItem;
import com.promo.engine.domain.model.EvaluationContext;

/**
 * Layer 2 (Read-Time) validator checking cart/request monetary invariants.
 */
public final class EvaluationInputValidator {

    public void validate(EvaluationContext context) {
        if (context.userId().isBlank()) {
            throw new InvalidEvaluationRequestException("userId must not be blank");
        }
        if (context.grossAmount().isNegative()) {
            throw new InvalidEvaluationRequestException("grossAmount must be >= 0");
        }
        if (context.eligibleAmount().isNegative()) {
            throw new InvalidEvaluationRequestException("eligibleAmount must be >= 0");
        }
        if (context.eligibleAmount().isGreaterThan(context.grossAmount())) {
            throw new InvalidEvaluationRequestException("eligibleAmount cannot exceed grossAmount");
        }
        if (context.maxStackingLimit() <= 0) {
            throw new InvalidEvaluationRequestException("maxStackingLimit must be > 0");
        }
        for (CartItem item : context.items()) {
            if (item.sku().isBlank()) {
                throw new InvalidEvaluationRequestException("item sku must not be blank");
            }
            if (item.amount().isNegative()) {
                throw new InvalidEvaluationRequestException("item amount must be >= 0 for sku " + item.sku());
            }
        }
    }
}
