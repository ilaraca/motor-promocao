package com.promo.engine.domain.model;

import java.util.Objects;

public record AppliedPromotion(
        String promotionId,
        int ruleVersion,
        BenefitType benefitType,
        String matchedTierId,
        String tierRange,
        int precedenceOrder,
        CalculationBase calculationBase,
        Money calculatedAmount
) {
    public AppliedPromotion {
        Objects.requireNonNull(promotionId, "promotionId must not be null");
        Objects.requireNonNull(benefitType, "benefitType must not be null");
        Objects.requireNonNull(matchedTierId, "matchedTierId must not be null");
        Objects.requireNonNull(tierRange, "tierRange must not be null");
        Objects.requireNonNull(calculationBase, "calculationBase must not be null");
        Objects.requireNonNull(calculatedAmount, "calculatedAmount must not be null");
    }

    public CashbackGrantEvent.GrantItem toGrantItem() {
        return new CashbackGrantEvent.GrantItem(promotionId, ruleVersion, tierRange, calculatedAmount);
    }
}
