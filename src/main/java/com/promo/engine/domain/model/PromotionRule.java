package com.promo.engine.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable versioned promotion rule identified by (promotionId, ruleVersion).
 */
public record PromotionRule(
        String promotionId,
        int ruleVersion,
        RuleStatus status,
        String name,
        Instant validFrom,
        Instant validTo,
        BenefitType benefitType,
        StackabilityMode stackabilityMode,
        int priorityRank,
        int precedenceOrder,
        CalculationBase calculationBase,
        Money minOrderValue,
        Money maxBenefitAmount,
        Integer maxUsagePerUser,
        Money maxCampaignBudget,
        Set<String> eligibleSegments,
        Set<String> eligibleCategories,
        List<ProgressiveTier> tiers,
        String createdBy,
        Instant createdAt
) {
    public PromotionRule {
        Objects.requireNonNull(promotionId, "promotionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        Objects.requireNonNull(validTo, "validTo must not be null");
        Objects.requireNonNull(benefitType, "benefitType must not be null");
        Objects.requireNonNull(stackabilityMode, "stackabilityMode must not be null");
        Objects.requireNonNull(calculationBase, "calculationBase must not be null");
        Objects.requireNonNull(minOrderValue, "minOrderValue must not be null");
        eligibleSegments = eligibleSegments == null ? Set.of() : Set.copyOf(eligibleSegments);
        eligibleCategories = eligibleCategories == null ? Set.of() : Set.copyOf(eligibleCategories);
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
        createdBy = createdBy == null ? "system" : createdBy;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    public boolean isWithinValidityWindow(Instant instant) {
        return !instant.isBefore(validFrom) && !instant.isAfter(validTo);
    }

    public Optional<ProgressiveTier> findMatchingTier(Money baseAmount) {
        return tiers.stream()
                .filter(tier -> tier.matches(baseAmount))
                .findFirst();
    }

    public FrozenRuleRef toFrozenRef() {
        return new FrozenRuleRef(
                promotionId,
                ruleVersion,
                validFrom,
                validTo,
                maxUsagePerUser,
                maxCampaignBudget
        );
    }
}
