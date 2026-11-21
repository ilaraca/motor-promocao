package com.promo.engine.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CashbackGrantEvent(
        String eventId,
        String orderId,
        String userId,
        String evaluationId,
        String checkoutKey,
        Money totalCashbackAmount,
        List<GrantItem> grants,
        Instant occurredAt,
        String correlationId,
        String traceId
) {
    public CashbackGrantEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        Objects.requireNonNull(checkoutKey, "checkoutKey must not be null");
        Objects.requireNonNull(totalCashbackAmount, "totalCashbackAmount must not be null");
        grants = List.copyOf(grants);
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public record GrantItem(
            String promotionId,
            int ruleVersion,
            String tierRange,
            Money amount
    ) {}
}
