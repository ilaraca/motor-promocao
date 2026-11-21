package com.promo.engine.domain;

import com.promo.engine.domain.exception.InvalidRuleDefinitionException;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.CandidateDecision;
import com.promo.engine.domain.model.CandidateStatus;
import com.promo.engine.domain.model.CartItem;
import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.model.EvaluationContext;
import com.promo.engine.domain.model.EvaluationResult;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.ProgressiveTier;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.ReasonCode;
import com.promo.engine.domain.model.RuleStatus;
import com.promo.engine.domain.model.StackabilityMode;
import com.promo.engine.domain.model.TierBenefitMode;
import com.promo.engine.domain.service.PromotionEvaluationEngine;
import com.promo.engine.domain.validation.PromotionRuleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionEvaluationEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-20T21:45:00Z");
    private static final Instant VALID_FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant VALID_TO = Instant.parse("2026-12-31T23:59:59Z");

    private final PromotionEvaluationEngine engine = PromotionEvaluationEngine.withDefaultStrategies();
    private final PromotionRuleValidator validator = new PromotionRuleValidator();

    @ParameterizedTest(name = "Order R$ {0} -> expected discount R$ {1} (tier={2})")
    @CsvSource({
            "49.99,  0.00,  NONE",
            "50.00,  2.50,  tier_50_99",
            "99.99,  5.00,  tier_50_99",
            "100.00, 10.00, tier_100_199",
            "199.99, 20.00, tier_100_199",
            "200.00, 30.00, tier_200_plus"
    })
    @DisplayName("PRD Scenario 1: Exact boundary tiers (99.99 vs 100.00) with BigDecimal HALF_EVEN")
    void shouldCalculateExactBoundaryTiersWithoutRoundingDrift(
            String orderAmount,
            String expectedDiscount,
            String expectedTierId
    ) {
        PromotionRule tieredDiscount = progressiveDiscountRule("promo_faixa", 1, StackabilityMode.STACKABLE, 1, 1);
        Money amount = Money.of(orderAmount);

        EvaluationResult result = engine.evaluate(
                List.of(tieredDiscount),
                context(amount, amount, ConflictPolicy.PRIORITY_RANK, 5)
        );

        assertThat(result.totalDiscountAmount()).isEqualTo(Money.of(expectedDiscount));
        assertThat(result.finalPayableAmount()).isEqualTo(amount.subtract(Money.of(expectedDiscount)));

        if ("NONE".equals(expectedTierId)) {
            assertThat(result.appliedPromotions()).isEmpty();
            assertThat(result.candidateDecisions())
                    .singleElement()
                    .extracting(CandidateDecision::status, CandidateDecision::reasonCode)
                    .containsExactly(CandidateStatus.REJECTED, ReasonCode.MIN_ORDER_VALUE_NOT_MET);
        } else {
            assertThat(result.appliedPromotions())
                    .singleElement()
                    .satisfies(applied -> assertThat(applied.matchedTierId()).isEqualTo(expectedTierId));
        }
    }

    @Test
    @DisplayName("PRD Scenario 1b: Exact Banker's Rounding (RoundingMode.HALF_EVEN)")
    void shouldApplyHalfEvenBankersRounding() {
        // 5% of 100.10 = 5.005 -> rounds to even 5.00
        // 5% of 100.30 = 5.015 -> rounds to even 5.02
        assertThat(Money.of("100.10").percentage(new BigDecimal("5"))).isEqualTo(Money.of("5.00"));
        assertThat(Money.of("100.30").percentage(new BigDecimal("5"))).isEqualTo(Money.of("5.02"));
    }

    @Test
    @DisplayName("PRD Section 6 Example: gross=180.00, eligible=160.00 -> discount=16.00, cashback=8.00, payable=164.00")
    void shouldReproduceExactPrdApiExample() {
        PromotionRule discountRule = progressiveDiscountRule("promo_faixa_10", 3, StackabilityMode.STACKABLE, 1, 1);
        PromotionRule cashbackRule = new PromotionRule(
                "promo_cb_prog",
                2,
                RuleStatus.ACTIVE,
                "Cashback Progressivo",
                VALID_FROM,
                VALID_TO,
                BenefitType.CASHBACK,
                StackabilityMode.STACKABLE,
                2,
                2,
                CalculationBase.ELIGIBLE_GROSS,
                Money.of("150.00"),
                Money.of("50.00"),
                5,
                Money.of("10000.00"),
                Set.of(),
                Set.of("RESTAURANTS"),
                List.of(new ProgressiveTier("cb_150_plus", Money.of("150.00"), null, TierBenefitMode.PERCENTAGE, new BigDecimal("5"))),
                "admin",
                NOW
        );

        EvaluationContext ctx = new EvaluationContext(
                "usr_98765",
                Set.of(),
                Money.of("180.00"),
                Money.of("160.00"),
                List.of(new CartItem("ITEM-01", "RESTAURANTS", Money.of("160.00"))),
                NOW,
                ConflictPolicy.PRIORITY_RANK,
                5,
                Map.of()
        );

        EvaluationResult result = engine.evaluate(List.of(cashbackRule, discountRule), ctx);

        assertThat(result.totalDiscountAmount()).isEqualTo(Money.of("16.00"));
        assertThat(result.totalCashbackAmount()).isEqualTo(Money.of("8.00"));
        assertThat(result.finalPayableAmount()).isEqualTo(Money.of("164.00"));
        assertThat(result.appliedPromotions()).hasSize(2);
        assertThat(result.appliedPromotions().get(0).promotionId()).isEqualTo("promo_faixa_10");
        assertThat(result.appliedPromotions().get(0).tierRange()).isEqualTo("100.00-199.99");
        assertThat(result.appliedPromotions().get(1).promotionId()).isEqualTo("promo_cb_prog");
        assertThat(result.appliedPromotions().get(1).tierRange()).isEqualTo("150.00+");
    }

    @Test
    @DisplayName("PRD Scenario 2: 1 EXCLUSIVE + 2 STACKABLE resolves deterministically with EXCLUSIVE_PROMOTION_CONFLICT")
    void shouldResolveExclusiveVsStackableConflictDeterministically() {
        PromotionRule exclusiveVip = new PromotionRule(
                "promo_vip_excl",
                1,
                RuleStatus.ACTIVE,
                "VIP Exclusive 12%",
                VALID_FROM,
                VALID_TO,
                BenefitType.INSTANT_DISCOUNT,
                StackabilityMode.EXCLUSIVE,
                1, // highest priority
                1,
                CalculationBase.ELIGIBLE_GROSS,
                Money.of("100.00"),
                Money.of("100.00"),
                null,
                null,
                Set.of(),
                Set.of(),
                List.of(new ProgressiveTier("t1", Money.of("100.00"), null, TierBenefitMode.PERCENTAGE, new BigDecimal("12"))),
                "admin",
                NOW
        );
        PromotionRule stack1 = progressiveDiscountRule("promo_stack_1", 1, StackabilityMode.STACKABLE, 2, 1);
        PromotionRule stack2 = new PromotionRule(
                "promo_stack_2",
                1,
                RuleStatus.ACTIVE,
                "Stackable Cashback 5%",
                VALID_FROM,
                VALID_TO,
                BenefitType.CASHBACK,
                StackabilityMode.STACKABLE,
                3,
                2,
                CalculationBase.ELIGIBLE_GROSS,
                Money.of("50.00"),
                Money.of("50.00"),
                null,
                null,
                Set.of(),
                Set.of(),
                List.of(new ProgressiveTier("cb1", Money.of("50.00"), null, TierBenefitMode.PERCENTAGE, new BigDecimal("5"))),
                "admin",
                NOW
        );

        // Under PRIORITY_RANK: EXCLUSIVE (rank 1) beats STACKABLE (ranks 2 & 3)
        EvaluationContext priorityCtx = context(Money.of("160.00"), Money.of("160.00"), ConflictPolicy.PRIORITY_RANK, 5);
        EvaluationResult resA = engine.evaluate(List.of(exclusiveVip, stack1, stack2), priorityCtx);
        EvaluationResult resB = engine.evaluate(List.of(stack2, exclusiveVip, stack1), priorityCtx); // permuted input

        assertThat(resA).isEqualTo(resB); // 100% deterministic regardless of input order
        assertThat(resA.appliedPromotions())
                .singleElement()
                .extracting(a -> a.promotionId(), a -> a.calculatedAmount())
                .containsExactly("promo_vip_excl", Money.of("19.20"));
        assertThat(resA.discardedEligiblePromotions())
                .hasSize(2)
                .allSatisfy(d -> assertThat(d.reasonCode()).isEqualTo(ReasonCode.EXCLUSIVE_PROMOTION_CONFLICT));

        // Under MAX_FINANCIAL_BENEFIT: 2 STACKABLE (16.00 + 8.00 = 24.00) beat EXCLUSIVE (19.20)
        EvaluationContext maxBenefitCtx = context(Money.of("160.00"), Money.of("160.00"), ConflictPolicy.MAX_FINANCIAL_BENEFIT, 5);
        EvaluationResult maxRes = engine.evaluate(List.of(exclusiveVip, stack1, stack2), maxBenefitCtx);

        assertThat(maxRes.appliedPromotions()).hasSize(2);
        assertThat(maxRes.totalDiscountAmount()).isEqualTo(Money.of("16.00"));
        assertThat(maxRes.totalCashbackAmount()).isEqualTo(Money.of("8.00"));
        assertThat(maxRes.discardedEligiblePromotions())
                .singleElement()
                .extracting(d -> d.promotionId(), d -> d.reasonCode())
                .containsExactly("promo_vip_excl", ReasonCode.EXCLUSIVE_PROMOTION_CONFLICT);
    }

    @Test
    @DisplayName("POST_DISCOUNT_SUBTOTAL calculates subsequent benefit on subtotal remaining after earlier discounts")
    void shouldSupportPostDiscountSubtotalCascading() {
        PromotionRule firstDiscount = new PromotionRule(
                "promo_disc_1", 1, RuleStatus.ACTIVE, "10% Gross", VALID_FROM, VALID_TO,
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, CalculationBase.ELIGIBLE_GROSS,
                Money.of("100.00"), null, null, null, Set.of(), Set.of(),
                List.of(new ProgressiveTier("t1", Money.of("100.00"), null, TierBenefitMode.PERCENTAGE, new BigDecimal("10"))),
                "admin", NOW
        );
        PromotionRule secondDiscount = new PromotionRule(
                "promo_disc_2", 1, RuleStatus.ACTIVE, "10% Post-Discount", VALID_FROM, VALID_TO,
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 2, 2, CalculationBase.POST_DISCOUNT_SUBTOTAL,
                Money.of("50.00"), null, null, null, Set.of(), Set.of(),
                List.of(new ProgressiveTier("t1", Money.of("50.00"), null, TierBenefitMode.PERCENTAGE, new BigDecimal("10"))),
                "admin", NOW
        );

        // Eligible 200.00 -> Promo 1 = 20.00 (subtotal 180.00) -> Promo 2 = 10% of 180.00 = 18.00 -> Total 38.00
        EvaluationResult result = engine.evaluate(
                List.of(firstDiscount, secondDiscount),
                context(Money.of("200.00"), Money.of("200.00"), ConflictPolicy.PRIORITY_RANK, 5)
        );

        assertThat(result.totalDiscountAmount()).isEqualTo(Money.of("38.00"));
        assertThat(result.finalPayableAmount()).isEqualTo(Money.of("162.00"));
    }

    @Test
    @DisplayName("Layer 1 Design-Time Validation rejects overlapping progressive tiers")
    void shouldRejectOverlappingTiersOnRulePublish() {
        PromotionRule invalidRule = new PromotionRule(
                "promo_bad", 1, RuleStatus.ACTIVE, "Bad Tiers", VALID_FROM, VALID_TO,
                BenefitType.INSTANT_DISCOUNT, StackabilityMode.STACKABLE, 1, 1, CalculationBase.ELIGIBLE_GROSS,
                Money.of("50.00"), null, null, null, Set.of(), Set.of(),
                List.of(
                        new ProgressiveTier("t1", Money.of("50.00"), Money.of("100.00"), TierBenefitMode.PERCENTAGE, new BigDecimal("5")),
                        new ProgressiveTier("t2", Money.of("100.00"), Money.of("199.99"), TierBenefitMode.PERCENTAGE, new BigDecimal("10"))
                ),
                "admin", NOW
        );

        assertThatThrownBy(() -> validator.validate(invalidRule))
                .isInstanceOf(InvalidRuleDefinitionException.class)
                .hasMessageContaining("Overlapping tier ranges");
    }

    private PromotionRule progressiveDiscountRule(
            String id,
            int version,
            StackabilityMode mode,
            int priorityRank,
            int precedenceOrder
    ) {
        return new PromotionRule(
                id,
                version,
                RuleStatus.ACTIVE,
                "Desconto Progressivo",
                VALID_FROM,
                VALID_TO,
                BenefitType.INSTANT_DISCOUNT,
                mode,
                priorityRank,
                precedenceOrder,
                CalculationBase.ELIGIBLE_GROSS,
                Money.of("50.00"),
                Money.of("100.00"),
                null,
                null,
                Set.of(),
                Set.of(),
                List.of(
                        new ProgressiveTier("tier_50_99", Money.of("50.00"), Money.of("99.99"), TierBenefitMode.PERCENTAGE, new BigDecimal("5")),
                        new ProgressiveTier("tier_100_199", Money.of("100.00"), Money.of("199.99"), TierBenefitMode.PERCENTAGE, new BigDecimal("10")),
                        new ProgressiveTier("tier_200_plus", Money.of("200.00"), null, TierBenefitMode.PERCENTAGE, new BigDecimal("15"))
                ),
                "admin",
                NOW
        );
    }

    private EvaluationContext context(Money gross, Money eligible, ConflictPolicy policy, int maxStacking) {
        return new EvaluationContext(
                "usr_test",
                Set.of(),
                gross,
                eligible,
                List.of(new CartItem("SKU-1", "GENERAL", eligible)),
                NOW,
                policy,
                maxStacking,
                Map.of()
        );
    }
}
