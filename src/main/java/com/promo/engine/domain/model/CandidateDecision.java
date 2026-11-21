package com.promo.engine.domain.model;

import java.util.Objects;

public record CandidateDecision(
        String promotionId,
        int ruleVersion,
        CandidateStatus status,
        ReasonCode reasonCode,
        Money calculatedAmount
) {
    public CandidateDecision {
        Objects.requireNonNull(promotionId, "promotionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(calculatedAmount, "calculatedAmount must not be null");
    }

    public static CandidateDecision applied(String promotionId, int ruleVersion, Money amount) {
        return new CandidateDecision(promotionId, ruleVersion, CandidateStatus.APPLIED, ReasonCode.APPLIED_SUCCESS, amount);
    }

    public static CandidateDecision rejected(String promotionId, int ruleVersion, ReasonCode reasonCode) {
        return new CandidateDecision(promotionId, ruleVersion, CandidateStatus.REJECTED, reasonCode, Money.ZERO);
    }
}
