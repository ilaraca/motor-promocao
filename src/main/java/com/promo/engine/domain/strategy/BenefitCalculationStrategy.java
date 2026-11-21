package com.promo.engine.domain.strategy;

import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.PromotionRule;

import java.util.Optional;

/**
 * Open/Closed Strategy contract allowing new benefit types (e.g. Free Shipping,
 * Loyalty Points) to be integrated without modifying the core evaluation pipeline.
 */
public interface BenefitCalculationStrategy {

    BenefitType supportedType();

    Optional<BenefitCalculationOutcome> calculate(
            PromotionRule rule,
            Money baseAmount,
            Money runningEligibleSubtotal
    );
}
