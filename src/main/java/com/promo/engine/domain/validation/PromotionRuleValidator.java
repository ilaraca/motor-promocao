package com.promo.engine.domain.validation;

import com.promo.engine.domain.exception.InvalidRuleDefinitionException;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.ProgressiveTier;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.TierBenefitMode;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 1 (Design-Time) validator ensuring malformed rules can never be published.
 */
public final class PromotionRuleValidator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public void validate(PromotionRule rule) {
        if (rule.promotionId().isBlank()) {
            throw new InvalidRuleDefinitionException("promotionId must not be blank");
        }
        if (rule.ruleVersion() < 1) {
            throw new InvalidRuleDefinitionException("ruleVersion must be >= 1");
        }
        if (!rule.validFrom().isBefore(rule.validTo())) {
            throw new InvalidRuleDefinitionException("validFrom must be strictly before validTo");
        }
        if (rule.minOrderValue().isNegative()) {
            throw new InvalidRuleDefinitionException("minOrderValue must be >= 0");
        }
        if (rule.maxBenefitAmount() != null && !rule.maxBenefitAmount().isPositive()) {
            throw new InvalidRuleDefinitionException("maxBenefitAmount must be > 0 when specified");
        }
        if (rule.maxUsagePerUser() != null && rule.maxUsagePerUser() <= 0) {
            throw new InvalidRuleDefinitionException("maxUsagePerUser must be > 0 when specified");
        }
        if (rule.maxCampaignBudget() != null && !rule.maxCampaignBudget().isPositive()) {
            throw new InvalidRuleDefinitionException("maxCampaignBudget must be > 0 when specified");
        }
        validateTiers(rule.tiers());
    }

    private void validateTiers(List<ProgressiveTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new InvalidRuleDefinitionException("At least one progressive tier is required");
        }
        List<ProgressiveTier> sorted = tiers.stream()
                .sorted(Comparator.comparing(ProgressiveTier::minAmount))
                .toList();

        Set<String> tierIds = new HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            ProgressiveTier tier = sorted.get(i);
            if (tier.tierId().isBlank()) {
                throw new InvalidRuleDefinitionException("tierId must not be blank");
            }
            if (!tierIds.add(tier.tierId())) {
                throw new InvalidRuleDefinitionException("Duplicate tierId: " + tier.tierId());
            }
            if (tier.minAmount().isNegative()) {
                throw new InvalidRuleDefinitionException("Tier minAmount must be >= 0: " + tier.tierId());
            }
            if (tier.maxAmount() != null && tier.maxAmount().isLessThan(tier.minAmount())) {
                throw new InvalidRuleDefinitionException("Tier maxAmount must be >= minAmount: " + tier.tierId());
            }
            if (tier.maxAmount() == null && i < sorted.size() - 1) {
                throw new InvalidRuleDefinitionException("Only the highest tier may have an unbounded maxAmount");
            }
            validateTierBenefitValue(tier);

            if (i > 0) {
                ProgressiveTier previous = sorted.get(i - 1);
                if (previous.maxAmount() == null || !previous.maxAmount().isLessThan(tier.minAmount())) {
                    throw new InvalidRuleDefinitionException(
                            "Overlapping tier ranges between " + previous.tierId() + " and " + tier.tierId()
                    );
                }
            }
        }
    }

    private void validateTierBenefitValue(ProgressiveTier tier) {
        BigDecimal value = tier.benefitValue();
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidRuleDefinitionException("Tier benefitValue must be > 0: " + tier.tierId());
        }
        if (tier.benefitMode() == TierBenefitMode.PERCENTAGE && value.compareTo(ONE_HUNDRED) > 0) {
            throw new InvalidRuleDefinitionException("Percentage benefitValue must be <= 100: " + tier.tierId());
        }
        if (tier.benefitMode() == TierBenefitMode.FIXED_AMOUNT && Money.of(value).isNegative()) {
            throw new InvalidRuleDefinitionException("Fixed amount benefitValue must be positive: " + tier.tierId());
        }
    }
}
