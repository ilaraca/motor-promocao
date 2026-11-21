package com.promo.engine.domain.strategy;

import com.promo.engine.domain.model.BenefitType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BenefitStrategyRegistry {

    private final Map<BenefitType, BenefitCalculationStrategy> strategies;

    public BenefitStrategyRegistry(List<BenefitCalculationStrategy> strategyList) {
        Objects.requireNonNull(strategyList, "strategyList must not be null");
        Map<BenefitType, BenefitCalculationStrategy> map = new EnumMap<>(BenefitType.class);
        for (BenefitCalculationStrategy strategy : strategyList) {
            BenefitCalculationStrategy prev = map.put(strategy.supportedType(), strategy);
            if (prev != null) {
                throw new IllegalArgumentException("Duplicate strategy registered for " + strategy.supportedType());
            }
        }
        this.strategies = Map.copyOf(map);
    }

    public static BenefitStrategyRegistry defaultRegistry() {
        return new BenefitStrategyRegistry(List.of(
                new InstantDiscountTierStrategy(),
                new ProgressiveCashbackStrategy()
        ));
    }

    public BenefitCalculationStrategy resolve(BenefitType benefitType) {
        BenefitCalculationStrategy strategy = strategies.get(benefitType);
        if (strategy == null) {
            throw new IllegalStateException("No calculation strategy registered for benefit type: " + benefitType);
        }
        return strategy;
    }
}
