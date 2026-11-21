package com.promo.engine.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DecisionSnapshot(
        String orderId,
        String userId,
        String evaluationId,
        String checkoutKey,
        Instant evaluatedAt,
        Instant committedAt,
        Money grossAmount,
        Money eligibleAmount,
        Money totalDiscountAmount,
        Money totalCashbackAmount,
        Money finalPayableAmount,
        List<AppliedPromotion> appliedPromotions,
        List<DiscardedEligiblePromotion> discardedEligiblePromotions
) {
    public DecisionSnapshot {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        Objects.requireNonNull(checkoutKey, "checkoutKey must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(committedAt, "committedAt must not be null");
        appliedPromotions = List.copyOf(appliedPromotions);
        discardedEligiblePromotions = List.copyOf(discardedEligiblePromotions);
    }

    public boolean hasCashback() {
        return totalCashbackAmount != null && totalCashbackAmount.isPositive();
    }
}
