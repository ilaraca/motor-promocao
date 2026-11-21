package com.promo.engine.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

public record ProgressiveTier(
        String tierId,
        Money minAmount,
        Money maxAmount,
        TierBenefitMode benefitMode,
        BigDecimal benefitValue
) {
    public ProgressiveTier {
        Objects.requireNonNull(tierId, "tierId must not be null");
        Objects.requireNonNull(minAmount, "minAmount must not be null");
        Objects.requireNonNull(benefitMode, "benefitMode must not be null");
        Objects.requireNonNull(benefitValue, "benefitValue must not be null");
    }

    public boolean matches(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        boolean meetsMin = amount.isGreaterThanOrEqual(minAmount);
        boolean meetsMax = maxAmount == null || amount.isLessThanOrEqual(maxAmount);
        return meetsMin && meetsMax;
    }

    public String tierRangeLabel() {
        if (maxAmount == null) {
            return minAmount + "+";
        }
        return minAmount + "-" + maxAmount;
    }
}
