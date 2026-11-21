package com.promo.engine.domain.model;

import java.time.Instant;
import java.util.Objects;

public record FrozenRuleRef(
        String promotionId,
        int ruleVersion,
        Instant validFrom,
        Instant validTo,
        Integer maxUsagePerUser,
        Money maxCampaignBudget
) {
    public FrozenRuleRef {
        Objects.requireNonNull(promotionId, "promotionId must not be null");
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        Objects.requireNonNull(validTo, "validTo must not be null");
    }

    public boolean isWithinOriginalValidity(Instant now) {
        return !now.isBefore(validFrom) && !now.isAfter(validTo);
    }
}
