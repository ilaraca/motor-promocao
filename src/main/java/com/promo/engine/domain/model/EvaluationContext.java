package com.promo.engine.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record EvaluationContext(
        String userId,
        Set<String> userSegments,
        Money grossAmount,
        Money eligibleAmount,
        List<CartItem> items,
        Instant evaluatedAt,
        ConflictPolicy conflictPolicy,
        int maxStackingLimit,
        Map<String, QuotaSnapshot> quotasByPromotionId
) {
    public EvaluationContext {
        Objects.requireNonNull(userId, "userId must not be null");
        userSegments = userSegments == null ? Set.of() : Set.copyOf(userSegments);
        Objects.requireNonNull(grossAmount, "grossAmount must not be null");
        Objects.requireNonNull(eligibleAmount, "eligibleAmount must not be null");
        items = items == null ? List.of() : List.copyOf(items);
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy must not be null");
        quotasByPromotionId = quotasByPromotionId == null ? Map.of() : Map.copyOf(quotasByPromotionId);
    }

    public QuotaSnapshot quotaFor(String promotionId) {
        return quotasByPromotionId.getOrDefault(promotionId, QuotaSnapshot.EMPTY);
    }

    /**
     * Resolves the eligible amount for a specific rule: if eligibleCategories is empty,
     * uses the global eligibleAmount; otherwise sums matching cart items (capped by global eligibleAmount).
     */
    public Money eligibleAmountFor(PromotionRule rule) {
        if (rule.eligibleCategories().isEmpty()) {
            return eligibleAmount;
        }
        Money categorySum = items.stream()
                .filter(item -> rule.eligibleCategories().contains(item.category()))
                .map(CartItem::amount)
                .reduce(Money.ZERO, Money::add);
        return categorySum.min(eligibleAmount);
    }
}
