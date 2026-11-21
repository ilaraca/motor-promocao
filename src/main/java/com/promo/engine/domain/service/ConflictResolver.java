package com.promo.engine.domain.service;

import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.model.DiscardedEligiblePromotion;
import com.promo.engine.domain.model.EvaluationContext;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.ReasonCode;
import com.promo.engine.domain.model.StackabilityMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stage 3: Deterministic conflict resolution between EXCLUSIVE and STACKABLE promotions
 * supporting PRIORITY_RANK and MAX_FINANCIAL_BENEFIT policies with total-order tie-breaking.
 */
public final class ConflictResolver {

    public static final Comparator<PromotionRule> PRIORITY_TIE_BREAKER = Comparator
            .comparingInt(PromotionRule::priorityRank)
            .thenComparing(PromotionRule::promotionId)
            .thenComparing(Comparator.comparingInt(PromotionRule::ruleVersion).reversed());

    private final CascadingBenefitCalculator calculator;

    public ConflictResolver(CascadingBenefitCalculator calculator) {
        this.calculator = calculator;
    }

    public ResolutionOutcome resolve(List<PromotionRule> eligibleRules, EvaluationContext context) {
        if (eligibleRules.isEmpty()) {
            return new ResolutionOutcome(List.of(), List.of());
        }

        List<PromotionRule> exclusives = eligibleRules.stream()
                .filter(r -> r.stackabilityMode() == StackabilityMode.EXCLUSIVE)
                .sorted(PRIORITY_TIE_BREAKER)
                .toList();

        List<PromotionRule> allStackables = eligibleRules.stream()
                .filter(r -> r.stackabilityMode() == StackabilityMode.STACKABLE)
                .sorted(CascadingBenefitCalculator.PRECEDENCE_ORDER)
                .toList();

        int limit = context.maxStackingLimit();
        List<PromotionRule> cappedStackables = allStackables.size() > limit
                ? allStackables.subList(0, limit)
                : allStackables;
        List<PromotionRule> overflowStackables = allStackables.size() > limit
                ? allStackables.subList(limit, allStackables.size())
                : List.of();

        // Case 1: Only STACKABLE rules are eligible
        if (exclusives.isEmpty()) {
            List<DiscardedEligiblePromotion> discarded = overflowStackables.stream()
                    .map(r -> new DiscardedEligiblePromotion(r.promotionId(), r.ruleVersion(), ReasonCode.STACKING_LIMIT_REACHED))
                    .toList();
            return new ResolutionOutcome(cappedStackables, discarded);
        }

        // Case 2: Determine the winning EXCLUSIVE candidate
        ScoredRule bestExclusive = selectBestExclusive(exclusives, context);

        // If no STACKABLE rules exist, best EXCLUSIVE wins and all other EXCLUSIVES are discarded
        if (cappedStackables.isEmpty()) {
            List<DiscardedEligiblePromotion> discarded = new ArrayList<>();
            for (PromotionRule rule : exclusives) {
                if (!sameRuleVersion(rule, bestExclusive.rule())) {
                    discarded.add(new DiscardedEligiblePromotion(
                            rule.promotionId(), rule.ruleVersion(), ReasonCode.EXCLUSIVE_PROMOTION_CONFLICT));
                }
            }
            return new ResolutionOutcome(List.of(bestExclusive.rule()), discarded);
        }

        // Case 3: Both EXCLUSIVE and STACKABLE candidates exist — compare per ConflictPolicy
        boolean exclusiveWins = doesExclusiveWin(bestExclusive, cappedStackables, context);
        List<DiscardedEligiblePromotion> discarded = new ArrayList<>();

        if (exclusiveWins) {
            for (PromotionRule rule : exclusives) {
                if (!sameRuleVersion(rule, bestExclusive.rule())) {
                    discarded.add(new DiscardedEligiblePromotion(
                            rule.promotionId(), rule.ruleVersion(), ReasonCode.EXCLUSIVE_PROMOTION_CONFLICT));
                }
            }
            for (PromotionRule rule : allStackables) {
                discarded.add(new DiscardedEligiblePromotion(
                        rule.promotionId(), rule.ruleVersion(), ReasonCode.EXCLUSIVE_PROMOTION_CONFLICT));
            }
            return new ResolutionOutcome(List.of(bestExclusive.rule()), discarded);
        } else {
            for (PromotionRule rule : exclusives) {
                discarded.add(new DiscardedEligiblePromotion(
                        rule.promotionId(), rule.ruleVersion(), ReasonCode.EXCLUSIVE_PROMOTION_CONFLICT));
            }
            for (PromotionRule rule : overflowStackables) {
                discarded.add(new DiscardedEligiblePromotion(
                        rule.promotionId(), rule.ruleVersion(), ReasonCode.STACKING_LIMIT_REACHED));
            }
            return new ResolutionOutcome(cappedStackables, discarded);
        }
    }

    private ScoredRule selectBestExclusive(List<PromotionRule> exclusives, EvaluationContext context) {
        if (context.conflictPolicy() == ConflictPolicy.PRIORITY_RANK) {
            PromotionRule first = exclusives.getFirst(); // already sorted by PRIORITY_TIE_BREAKER
            Money benefit = calculator.calculate(List.of(first), context).totalNetBenefit();
            return new ScoredRule(first, benefit);
        }

        return exclusives.stream()
                .map(rule -> new ScoredRule(rule, calculator.calculate(List.of(rule), context).totalNetBenefit()))
                .min(Comparator
                        .comparing(ScoredRule::netBenefit, Comparator.reverseOrder())
                        .thenComparing(ScoredRule::rule, PRIORITY_TIE_BREAKER))
                .orElseThrow();
    }

    private boolean doesExclusiveWin(
            ScoredRule bestExclusive,
            List<PromotionRule> cappedStackables,
            EvaluationContext context
    ) {
        int bestStackableRank = cappedStackables.stream()
                .mapToInt(PromotionRule::priorityRank)
                .min()
                .orElse(Integer.MAX_VALUE);

        if (context.conflictPolicy() == ConflictPolicy.PRIORITY_RANK) {
            return bestExclusive.rule().priorityRank() <= bestStackableRank;
        }

        Money stackableBenefit = calculator.calculate(cappedStackables, context).totalNetBenefit();
        int cmp = bestExclusive.netBenefit().compareTo(stackableBenefit);
        if (cmp != 0) {
            return cmp > 0;
        }
        return bestExclusive.rule().priorityRank() <= bestStackableRank;
    }

    private boolean sameRuleVersion(PromotionRule a, PromotionRule b) {
        return a.promotionId().equals(b.promotionId()) && a.ruleVersion() == b.ruleVersion();
    }

    private record ScoredRule(PromotionRule rule, Money netBenefit) {}

    public record ResolutionOutcome(
            List<PromotionRule> winners,
            List<DiscardedEligiblePromotion> discardedEligible
    ) {}
}
