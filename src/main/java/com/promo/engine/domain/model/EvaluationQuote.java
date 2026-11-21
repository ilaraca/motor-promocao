package com.promo.engine.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EvaluationQuote(
        String evaluationId,
        String userId,
        Instant evaluatedAt,
        Instant expiresAt,
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
    public EvaluationQuote {
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        appliedPromotions = List.copyOf(appliedPromotions);
        discardedEligiblePromotions = List.copyOf(discardedEligiblePromotions);
        candidateDecisions = List.copyOf(candidateDecisions);
        frozenRuleRefs = List.copyOf(frozenRuleRefs);
    }

    public boolean isExpiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    public DecisionSnapshot toDecisionSnapshot(String orderId, String checkoutKey, Instant committedAt) {
        return new DecisionSnapshot(
                orderId,
                userId,
                evaluationId,
                checkoutKey,
                evaluatedAt,
                committedAt,
                grossAmount,
                eligibleAmount,
                totalDiscountAmount,
                totalCashbackAmount,
                finalPayableAmount,
                appliedPromotions,
                discardedEligiblePromotions
        );
    }
}
