package com.promo.engine.domain.model;

import java.util.Objects;

public record QuotaSnapshot(int userUsageCount, Money campaignSpentAmount) {
    public static final QuotaSnapshot EMPTY = new QuotaSnapshot(0, Money.ZERO);

    public QuotaSnapshot {
        Objects.requireNonNull(campaignSpentAmount, "campaignSpentAmount must not be null");
    }
}
