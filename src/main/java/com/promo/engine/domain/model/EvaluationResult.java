package com.promo.engine.domain.model;

import java.util.List;
import java.util.Objects;

public record EvaluationResult(
        Money grossAmount,
        Money eligibleAmount,
        Money totalDiscountAmount,
        Money totalCashbackAmount,
        Money finalPayableAmount,
        List<AppliedPromotion> appliedPromotions,
        List<DiscardedEligiblePromotion> discardedEligiblePromotions,
        List<CandidateDecision> candidateDecisions,
        List<FrozenRuleRef> frozenRuleRefs
) {
    public EvaluationResult {
        Objects.requireNonNull(grossAmount, "grossAmount must not be null");
        Objects.requireNonNull(eligibleAmount, "eligibleAmount must not be null");
        Objects.requireNonNull(totalDiscountAmount, "totalDiscountAmount must not be null");
        Objects.requireNonNull(totalCashbackAmount, "totalCashbackAmount must not be null");
        Objects.requireNonNull(finalPayableAmount, "finalPayableAmount must not be null");
        appliedPromotions = List.copyOf(appliedPromotions);
        discardedEligiblePromotions = List.copyOf(discardedEligiblePromotions);
        candidateDecisions = List.copyOf(candidateDecisions);
        frozenRuleRefs = List.copyOf(frozenRuleRefs);
    }
}
