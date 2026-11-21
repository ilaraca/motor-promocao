package com.promo.engine.domain.service;

import com.promo.engine.domain.model.AppliedPromotion;
import com.promo.engine.domain.model.CandidateDecision;
import com.promo.engine.domain.model.DiscardedEligiblePromotion;
import com.promo.engine.domain.model.EvaluationContext;
import com.promo.engine.domain.model.EvaluationResult;
import com.promo.engine.domain.model.FrozenRuleRef;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.ReasonCode;
import com.promo.engine.domain.strategy.BenefitStrategyRegistry;
import com.promo.engine.domain.validation.EvaluationInputValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure deterministic Promotion Evaluation Engine.
 * Executes: Input Validation -> Eligibility -> Conflict Resolution -> Cascading Tier Calculation.
 */
public final class PromotionEvaluationEngine {

    private static final Comparator<PromotionRule> CANONICAL_RULE_ORDER = Comparator
            .comparing(PromotionRule::promotionId)
            .thenComparing(Comparator.comparingInt(PromotionRule::ruleVersion).reversed());

    private static final Comparator<CandidateDecision> CANONICAL_DECISION_ORDER = Comparator
            .comparing(CandidateDecision::promotionId)
            .thenComparing(Comparator.comparingInt(CandidateDecision::ruleVersion).reversed());

    private final EvaluationInputValidator inputValidator;
    private final EligibilityEvaluator eligibilityEvaluator;
    private final ConflictResolver conflictResolver;
    private final CascadingBenefitCalculator benefitCalculator;

    public PromotionEvaluationEngine(BenefitStrategyRegistry strategyRegistry) {
        this.inputValidator = new EvaluationInputValidator();
        this.eligibilityEvaluator = new EligibilityEvaluator(strategyRegistry);
        this.benefitCalculator = new CascadingBenefitCalculator(strategyRegistry);
        this.conflictResolver = new ConflictResolver(this.benefitCalculator);
    }

    public static PromotionEvaluationEngine withDefaultStrategies() {
        return new PromotionEvaluationEngine(BenefitStrategyRegistry.defaultRegistry());
    }

    public EligibilityEvaluator eligibilityEvaluator() {
        return eligibilityEvaluator;
    }

    public EvaluationResult evaluate(List<PromotionRule> candidateRules, EvaluationContext context) {
        inputValidator.validate(context);

        // Sort inputs canonically so evaluation is 100% invariant to repository return order
        List<PromotionRule> canonicalCandidates = candidateRules.stream()
                .sorted(CANONICAL_RULE_ORDER)
                .toList();

        List<PromotionRule> eligibleRules = new ArrayList<>();
        List<CandidateDecision> allDecisions = new ArrayList<>();

        // Stage 1: Eligibility Filter
        for (PromotionRule rule : canonicalCandidates) {
            Optional<ReasonCode> rejection = eligibilityEvaluator.checkEligibility(rule, context);
            if (rejection.isPresent()) {
                allDecisions.add(CandidateDecision.rejected(
                        rule.promotionId(), rule.ruleVersion(), rejection.get()));
            } else {
                eligibleRules.add(rule);
            }
        }

        // Stage 2 & 3: Conflict Resolution & Stackability
        ConflictResolver.ResolutionOutcome resolution = conflictResolver.resolve(eligibleRules, context);

        List<DiscardedEligiblePromotion> discardedEligible = new ArrayList<>(resolution.discardedEligible());
        for (DiscardedEligiblePromotion discarded : resolution.discardedEligible()) {
            allDecisions.add(CandidateDecision.rejected(
                    discarded.promotionId(), discarded.ruleVersion(), discarded.reasonCode()));
        }

        // Stage 4: Cascading Progressive Tier Calculation
        CascadingBenefitCalculator.CascadeResult cascade = benefitCalculator.calculate(resolution.winners(), context);

        for (AppliedPromotion applied : cascade.appliedPromotions()) {
            allDecisions.add(CandidateDecision.applied(
                    applied.promotionId(), applied.ruleVersion(), applied.calculatedAmount()));
        }
        // Edge case: a POST_DISCOUNT_SUBTOTAL rule dropped below its minimum tier after an earlier discount
        for (PromotionRule unmatched : cascade.unmatchedAfterCascade()) {
            allDecisions.add(CandidateDecision.rejected(
                    unmatched.promotionId(), unmatched.ruleVersion(), ReasonCode.MIN_ORDER_VALUE_NOT_MET));
        }

        allDecisions.sort(CANONICAL_DECISION_ORDER);

        // Freeze every applied rule version (along with original validity window & caps)
        List<FrozenRuleRef> frozenRefs = resolution.winners().stream()
                .filter(rule -> cascade.appliedPromotions().stream()
                        .anyMatch(a -> a.promotionId().equals(rule.promotionId())
                                && a.ruleVersion() == rule.ruleVersion()))
                .sorted(CascadingBenefitCalculator.PRECEDENCE_ORDER)
                .map(PromotionRule::toFrozenRef)
                .toList();

        return new EvaluationResult(
                context.grossAmount(),
                context.eligibleAmount(),
                cascade.totalDiscountAmount(),
                cascade.totalCashbackAmount(),
                cascade.finalPayableAmount(),
                cascade.appliedPromotions(),
                discardedEligible,
                allDecisions,
                frozenRefs
        );
    }
}
