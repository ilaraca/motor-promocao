package com.promo.engine.domain.service;

import com.promo.engine.domain.model.AppliedPromotion;
import com.promo.engine.domain.model.BenefitEffect;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.EvaluationContext;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.strategy.BenefitCalculationOutcome;
import com.promo.engine.domain.strategy.BenefitStrategyRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Stage 4: Deterministic cascading progressive tier calculator honoring precedenceOrder,
 * calculationBase (ELIGIBLE_GROSS vs POST_DISCOUNT_SUBTOTAL), caps, and non-negative floor.
 */
public final class CascadingBenefitCalculator {

    public static final Comparator<PromotionRule> PRECEDENCE_ORDER = Comparator
            .comparingInt(PromotionRule::precedenceOrder)
            .thenComparingInt(PromotionRule::priorityRank)
            .thenComparing(PromotionRule::promotionId)
            .thenComparing(Comparator.comparingInt(PromotionRule::ruleVersion).reversed());

    private final BenefitStrategyRegistry strategyRegistry;

    public CascadingBenefitCalculator(BenefitStrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    public CascadeResult calculate(List<PromotionRule> rules, EvaluationContext context) {
        List<PromotionRule> ordered = rules.stream()
                .sorted(PRECEDENCE_ORDER)
                .toList();

        Money runningEligibleSubtotal = context.eligibleAmount();
        Money totalDiscount = Money.ZERO;
        Money totalCashback = Money.ZERO;
        List<AppliedPromotion> applied = new ArrayList<>();
        List<PromotionRule> unmatchedAfterCascade = new ArrayList<>();

        for (PromotionRule rule : ordered) {
            Money ruleGrossEligible = context.eligibleAmountFor(rule);
            Money baseAmount = rule.calculationBase() == CalculationBase.POST_DISCOUNT_SUBTOTAL
                    ? runningEligibleSubtotal.min(ruleGrossEligible)
                    : ruleGrossEligible;

            Optional<BenefitCalculationOutcome> outcomeOpt = strategyRegistry
                    .resolve(rule.benefitType())
                    .calculate(rule, baseAmount, runningEligibleSubtotal);

            if (outcomeOpt.isEmpty()) {
                unmatchedAfterCascade.add(rule);
                continue;
            }

            BenefitCalculationOutcome outcome = outcomeOpt.get();
            Money calculated = outcome.calculatedAmount();

            switch (outcome.effect()) {
                case BenefitEffect.InstantPriceReduction reduction -> {
                    totalDiscount = totalDiscount.add(reduction.amount());
                    runningEligibleSubtotal = runningEligibleSubtotal.subtract(reduction.amount()).max(Money.ZERO);
                }
                case BenefitEffect.AsyncCashbackGrant grant ->
                        totalCashback = totalCashback.add(grant.amount());
            }

            applied.add(new AppliedPromotion(
                    rule.promotionId(),
                    rule.ruleVersion(),
                    rule.benefitType(),
                    outcome.matchedTier().tierId(),
                    outcome.matchedTier().tierRangeLabel(),
                    rule.precedenceOrder(),
                    rule.calculationBase(),
                    calculated
            ));
        }

        Money finalPayable = context.grossAmount().subtract(totalDiscount).max(Money.ZERO);
        return new CascadeResult(
                totalDiscount,
                totalCashback,
                finalPayable,
                applied,
                unmatchedAfterCascade
        );
    }

    public record CascadeResult(
            Money totalDiscountAmount,
            Money totalCashbackAmount,
            Money finalPayableAmount,
            List<AppliedPromotion> appliedPromotions,
            List<PromotionRule> unmatchedAfterCascade
    ) {
        public Money totalNetBenefit() {
            return totalDiscountAmount.add(totalCashbackAmount);
        }
    }
}
