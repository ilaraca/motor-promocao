package com.promo.engine.domain.strategy;

import com.promo.engine.domain.model.BenefitEffect;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.ProgressiveTier;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.TierBenefitMode;

import java.util.Optional;

public final class InstantDiscountTierStrategy implements BenefitCalculationStrategy {

    @Override
    public BenefitType supportedType() {
        return BenefitType.INSTANT_DISCOUNT;
    }

    @Override
    public Optional<BenefitCalculationOutcome> calculate(
            PromotionRule rule,
            Money baseAmount,
            Money runningEligibleSubtotal
    ) {
        Optional<ProgressiveTier> matchedOpt = rule.findMatchingTier(baseAmount);
        if (matchedOpt.isEmpty()) {
            return Optional.empty();
        }
        ProgressiveTier tier = matchedOpt.get();
        Money raw = computeRawAmount(baseAmount, tier);
        Money capped = rule.maxBenefitAmount() != null ? raw.min(rule.maxBenefitAmount()) : raw;
        Money effective = capped.min(runningEligibleSubtotal).max(Money.ZERO);
        return Optional.of(new BenefitCalculationOutcome(
                tier,
                effective,
                new BenefitEffect.InstantPriceReduction(effective)
        ));
    }

    private Money computeRawAmount(Money baseAmount, ProgressiveTier tier) {
        if (tier.benefitMode() == TierBenefitMode.PERCENTAGE) {
            return baseAmount.percentage(tier.benefitValue());
        }
        return Money.of(tier.benefitValue());
    }
}
