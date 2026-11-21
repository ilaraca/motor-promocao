package com.promo.engine.domain.model;

import java.util.Objects;

public record DiscardedEligiblePromotion(
        String promotionId,
        int ruleVersion,
        ReasonCode reasonCode
) {
    public DiscardedEligiblePromotion {
        Objects.requireNonNull(promotionId, "promotionId must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
    }
}
