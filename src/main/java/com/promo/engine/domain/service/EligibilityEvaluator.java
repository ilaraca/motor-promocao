package com.promo.engine.domain.service;

import com.promo.engine.domain.model.EvaluationContext;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.QuotaSnapshot;
import com.promo.engine.domain.model.ReasonCode;
import com.promo.engine.domain.model.RuleStatus;
import com.promo.engine.domain.strategy.BenefitCalculationOutcome;
import com.promo.engine.domain.strategy.BenefitStrategyRegistry;

import java.util.Collections;
import java.util.Optional;

/**
 * Stage 1: Deterministic eligibility filter with fixed short-circuit rejection precedence.
 */
public final class EligibilityEvaluator {

    private final BenefitStrategyRegistry strategyRegistry;

    public EligibilityEvaluator(BenefitStrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    /**
     * Pure static check (no quota/budget lookup required) — also used before DynamoDB BatchGetItem
     * to avoid unnecessary read round-trips for ineligible campaigns.
     */
    public Optional<ReasonCode> checkStaticEligibility(PromotionRule rule, EvaluationContext context) {
        // 1. Validity window and active status
        if (rule.status() != RuleStatus.ACTIVE || !rule.isWithinValidityWindow(context.evaluatedAt())) {
            return Optional.of(ReasonCode.OUT_OF_VALIDITY_WINDOW);
        }

        // 2. User segment and category eligibility
        if (!rule.eligibleSegments().isEmpty()
                && Collections.disjoint(rule.eligibleSegments(), context.userSegments())) {
            return Optional.of(ReasonCode.USER_NOT_ELIGIBLE);
        }
        Money ruleEligibleAmount = context.eligibleAmountFor(rule);
        if (!rule.eligibleCategories().isEmpty() && !ruleEligibleAmount.isPositive()) {
            return Optional.of(ReasonCode.USER_NOT_ELIGIBLE);
        }

        // 3. Minimum order value and tier match
        if (ruleEligibleAmount.isLessThan(rule.minOrderValue())
                || rule.findMatchingTier(ruleEligibleAmount).isEmpty()) {
            return Optional.of(ReasonCode.MIN_ORDER_VALUE_NOT_MET);
        }

        return Optional.empty();
    }

    /**
     * Full Stage 1 eligibility evaluation including user cap and campaign budget checks.
     */
    public Optional<ReasonCode> checkEligibility(PromotionRule rule, EvaluationContext context) {
        Optional<ReasonCode> staticRejection = checkStaticEligibility(rule, context);
        if (staticRejection.isPresent()) {
            return staticRejection;
        }

        QuotaSnapshot quota = context.quotaFor(rule.promotionId());

        // 4. Per-user usage cap
        if (rule.maxUsagePerUser() != null && quota.userUsageCount() >= rule.maxUsagePerUser()) {
            return Optional.of(ReasonCode.USER_CAP_REACHED);
        }

        // 5. Campaign total budget
        if (rule.maxCampaignBudget() != null) {
            Money remainingBudget = rule.maxCampaignBudget().subtract(quota.campaignSpentAmount());
            if (!remainingBudget.isPositive()) {
                return Optional.of(ReasonCode.CAMPAIGN_BUDGET_EXHAUSTED);
            }
            Money ruleEligibleAmount = context.eligibleAmountFor(rule);
            Optional<BenefitCalculationOutcome> preview = strategyRegistry
                    .resolve(rule.benefitType())
                    .calculate(rule, ruleEligibleAmount, ruleEligibleAmount);
            if (preview.isPresent() && preview.get().calculatedAmount().isGreaterThan(remainingBudget)) {
                return Optional.of(ReasonCode.CAMPAIGN_BUDGET_EXHAUSTED);
            }
        }

        return Optional.empty();
    }
}
