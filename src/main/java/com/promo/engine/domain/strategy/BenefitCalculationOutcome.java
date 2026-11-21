package com.promo.engine.domain.strategy;

import com.promo.engine.domain.model.BenefitEffect;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.ProgressiveTier;

public record BenefitCalculationOutcome(
        ProgressiveTier matchedTier,
        Money calculatedAmount,
        BenefitEffect effect
) {}
