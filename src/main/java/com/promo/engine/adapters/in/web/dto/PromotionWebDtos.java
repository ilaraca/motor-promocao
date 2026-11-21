package com.promo.engine.adapters.in.web.dto;

import com.promo.engine.domain.model.AppliedPromotion;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.DiscardedEligiblePromotion;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.ProgressiveTier;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.StackabilityMode;
import com.promo.engine.domain.model.TierBenefitMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class PromotionWebDtos {

    private PromotionWebDtos() {}

    public record CartItemDto(
            @NotBlank String sku,
            @NotBlank String category,
            @NotNull @DecimalMin("0.00") BigDecimal amount
    ) {}

    public record EvaluateRequest(
            String userId,
            @NotNull @DecimalMin("0.00") BigDecimal grossAmount,
            @NotNull @DecimalMin("0.00") BigDecimal eligibleAmount,
            @Valid List<CartItemDto> items,
            ConflictPolicy conflictPolicy
    ) {}

    public record AppliedPromotionDto(
            String promotionId,
            int ruleVersion,
            String benefitType,
            String matchedTierId,
            String tierRange,
            int precedenceOrder,
            String calculationBase,
            BigDecimal calculatedAmount
    ) {
        public static AppliedPromotionDto fromDomain(AppliedPromotion p) {
            return new AppliedPromotionDto(
                    p.promotionId(),
                    p.ruleVersion(),
                    p.benefitType().name(),
                    p.matchedTierId(),
                    p.tierRange(),
                    p.precedenceOrder(),
                    p.calculationBase().name(),
                    p.calculatedAmount().amount()
            );
        }
    }

    public record DiscardedEligibleDto(
            String promotionId,
            int ruleVersion,
            String reasonCode
    ) {
        public static DiscardedEligibleDto fromDomain(DiscardedEligiblePromotion d) {
            return new DiscardedEligibleDto(d.promotionId(), d.ruleVersion(), d.reasonCode().name());
        }
    }

    public record EvaluateResponse(
            String evaluationId,
            Instant expiresAt,
            BigDecimal finalPayableAmount,
            BigDecimal totalDiscountAmount,
            BigDecimal totalCashbackAmount,
            List<AppliedPromotionDto> appliedPromotions,
            List<DiscardedEligibleDto> discardedEligiblePromotions
    ) {
        public static EvaluateResponse fromDomain(EvaluationQuote quote) {
            return new EvaluateResponse(
                    quote.evaluationId(),
                    quote.expiresAt(),
                    quote.finalPayableAmount().amount(),
                    quote.totalDiscountAmount().amount(),
                    quote.totalCashbackAmount().amount(),
                    quote.appliedPromotions().stream().map(AppliedPromotionDto::fromDomain).toList(),
                    quote.discardedEligiblePromotions().stream().map(DiscardedEligibleDto::fromDomain).toList()
            );
        }
    }

    public record CommitRequest(
            @NotBlank String orderId,
            @NotBlank String evaluationId
    ) {}

    public record DecisionSnapshotResponse(
            String orderId,
            String userId,
            String evaluationId,
            String checkoutKey,
            Instant evaluatedAt,
            Instant committedAt,
            BigDecimal grossAmount,
            BigDecimal eligibleAmount,
            BigDecimal totalDiscountAmount,
            BigDecimal totalCashbackAmount,
            BigDecimal finalPayableAmount,
            List<AppliedPromotionDto> appliedPromotions,
            List<DiscardedEligibleDto> discardedEligiblePromotions
    ) {
        public static DecisionSnapshotResponse fromDomain(DecisionSnapshot s) {
            return new DecisionSnapshotResponse(
                    s.orderId(),
                    s.userId(),
                    s.evaluationId(),
                    s.checkoutKey(),
                    s.evaluatedAt(),
                    s.committedAt(),
                    s.grossAmount().amount(),
                    s.eligibleAmount().amount(),
                    s.totalDiscountAmount().amount(),
                    s.totalCashbackAmount().amount(),
                    s.finalPayableAmount().amount(),
                    s.appliedPromotions().stream().map(AppliedPromotionDto::fromDomain).toList(),
                    s.discardedEligiblePromotions().stream().map(DiscardedEligibleDto::fromDomain).toList()
            );
        }
    }

    public record ProgressiveTierRequest(
            @NotBlank String tierId,
            @NotNull @DecimalMin("0.00") BigDecimal minAmount,
            BigDecimal maxAmount,
            @NotNull TierBenefitMode benefitMode,
            @NotNull BigDecimal benefitValue
    ) {}

    public record PublishRuleRequest(
            @NotBlank String promotionId,
            String name,
            @NotNull Instant validFrom,
            @NotNull Instant validTo,
            @NotNull BenefitType benefitType,
            @NotNull StackabilityMode stackabilityMode,
            int priorityRank,
            int precedenceOrder,
            @NotNull CalculationBase calculationBase,
            @NotNull @DecimalMin("0.00") BigDecimal minOrderValue,
            BigDecimal maxBenefitAmount,
            Integer maxUsagePerUser,
            BigDecimal maxCampaignBudget,
            Set<String> eligibleSegments,
            Set<String> eligibleCategories,
            @NotEmpty @Valid List<ProgressiveTierRequest> tiers
    ) {}

    public record PromotionRuleResponse(
            String promotionId,
            int ruleVersion,
            String status,
            String name,
            Instant validFrom,
            Instant validTo,
            String benefitType,
            String stackabilityMode,
            int priorityRank,
            int precedenceOrder,
            String calculationBase,
            BigDecimal minOrderValue,
            BigDecimal maxBenefitAmount,
            Integer maxUsagePerUser,
            BigDecimal maxCampaignBudget,
            Set<String> eligibleSegments,
            Set<String> eligibleCategories,
            List<ProgressiveTierResponse> tiers,
            String createdBy,
            Instant createdAt
    ) {
        public static PromotionRuleResponse fromDomain(PromotionRule r) {
            return new PromotionRuleResponse(
                    r.promotionId(),
                    r.ruleVersion(),
                    r.status().name(),
                    r.name(),
                    r.validFrom(),
                    r.validTo(),
                    r.benefitType().name(),
                    r.stackabilityMode().name(),
                    r.priorityRank(),
                    r.precedenceOrder(),
                    r.calculationBase().name(),
                    r.minOrderValue().amount(),
                    r.maxBenefitAmount() != null ? r.maxBenefitAmount().amount() : null,
                    r.maxUsagePerUser(),
                    r.maxCampaignBudget() != null ? r.maxCampaignBudget().amount() : null,
                    r.eligibleSegments(),
                    r.eligibleCategories(),
                    r.tiers().stream().map(ProgressiveTierResponse::fromDomain).toList(),
                    r.createdBy(),
                    r.createdAt()
            );
        }
    }

    public record ProgressiveTierResponse(
            String tierId,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String tierRange,
            String benefitMode,
            BigDecimal benefitValue
    ) {
        public static ProgressiveTierResponse fromDomain(ProgressiveTier t) {
            return new ProgressiveTierResponse(
                    t.tierId(),
                    t.minAmount().amount(),
                    t.maxAmount() != null ? t.maxAmount().amount() : null,
                    t.tierRangeLabel(),
                    t.benefitMode().name(),
                    t.benefitValue()
            );
        }
    }
}
