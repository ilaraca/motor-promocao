package com.promo.engine.adapters.out.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promo.engine.domain.model.AppliedPromotion;
import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.CandidateDecision;
import com.promo.engine.domain.model.CandidateStatus;
import com.promo.engine.domain.model.CashbackGrantEvent;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.DiscardedEligiblePromotion;
import com.promo.engine.domain.model.FrozenRuleRef;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.ProgressiveTier;
import com.promo.engine.domain.model.ReasonCode;
import com.promo.engine.domain.model.TierBenefitMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Adapter-layer serialization codec converting pure domain value objects to/from
 * compact JSON strings stored inside DynamoDB Single Table attributes.
 */
public final class DynamoDbJsonCodec {

    private final ObjectMapper objectMapper;

    public DynamoDbJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeTiers(List<ProgressiveTier> tiers) {
        List<TierDoc> docs = tiers.stream()
                .map(t -> new TierDoc(
                        t.tierId(),
                        t.minAmount().toString(),
                        t.maxAmount() != null ? t.maxAmount().toString() : null,
                        t.benefitMode().name(),
                        t.benefitValue().toPlainString()
                ))
                .toList();
        return toJson(docs);
    }

    public List<ProgressiveTier> readTiers(String json) {
        List<TierDoc> docs = fromJson(json, new TypeReference<>() {});
        return docs.stream()
                .map(d -> new ProgressiveTier(
                        d.tierId(),
                        Money.of(d.minAmount()),
                        d.maxAmount() != null ? Money.of(d.maxAmount()) : null,
                        TierBenefitMode.valueOf(d.benefitMode()),
                        new BigDecimal(d.benefitValue())
                ))
                .toList();
    }

    public String writeAppliedPromotions(List<AppliedPromotion> list) {
        List<AppliedDoc> docs = list.stream().map(AppliedDoc::fromDomain).toList();
        return toJson(docs);
    }

    public List<AppliedPromotion> readAppliedPromotions(String json) {
        List<AppliedDoc> docs = fromJson(json, new TypeReference<>() {});
        return docs.stream().map(AppliedDoc::toDomain).toList();
    }

    public String writeDiscardedEligible(List<DiscardedEligiblePromotion> list) {
        List<DiscardedDoc> docs = list.stream().map(DiscardedDoc::fromDomain).toList();
        return toJson(docs);
    }

    public List<DiscardedEligiblePromotion> readDiscardedEligible(String json) {
        List<DiscardedDoc> docs = fromJson(json, new TypeReference<>() {});
        return docs.stream().map(DiscardedDoc::toDomain).toList();
    }

    public String writeCandidates(List<CandidateDecision> list) {
        List<CandidateDoc> docs = list.stream().map(CandidateDoc::fromDomain).toList();
        return toJson(docs);
    }

    public List<CandidateDecision> readCandidates(String json) {
        List<CandidateDoc> docs = fromJson(json, new TypeReference<>() {});
        return docs.stream().map(CandidateDoc::toDomain).toList();
    }

    public String writeFrozenRefs(List<FrozenRuleRef> list) {
        List<FrozenRefDoc> docs = list.stream().map(FrozenRefDoc::fromDomain).toList();
        return toJson(docs);
    }

    public List<FrozenRuleRef> readFrozenRefs(String json) {
        List<FrozenRefDoc> docs = fromJson(json, new TypeReference<>() {});
        return docs.stream().map(FrozenRefDoc::toDomain).toList();
    }

    public String writeSnapshot(DecisionSnapshot snapshot) {
        return toJson(SnapshotDoc.fromDomain(snapshot));
    }

    public DecisionSnapshot readSnapshot(String json) {
        return fromJson(json, SnapshotDoc.class).toDomain();
    }

    public String writeCashbackEvent(CashbackGrantEvent event) {
        return toJson(CashbackEventDoc.fromDomain(event));
    }

    public CashbackGrantEvent readCashbackEvent(String json) {
        return fromJson(json, CashbackEventDoc.class).toDomain();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize DynamoDB JSON attribute", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize DynamoDB JSON attribute", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize DynamoDB JSON attribute", e);
        }
    }

    record TierDoc(String tierId, String minAmount, String maxAmount, String benefitMode, String benefitValue) {}

    record AppliedDoc(
            String promotionId,
            int ruleVersion,
            String benefitType,
            String matchedTierId,
            String tierRange,
            int precedenceOrder,
            String calculationBase,
            String calculatedAmount
    ) {
        static AppliedDoc fromDomain(AppliedPromotion p) {
            return new AppliedDoc(
                    p.promotionId(),
                    p.ruleVersion(),
                    p.benefitType().name(),
                    p.matchedTierId(),
                    p.tierRange(),
                    p.precedenceOrder(),
                    p.calculationBase().name(),
                    p.calculatedAmount().toString()
            );
        }

        AppliedPromotion toDomain() {
            return new AppliedPromotion(
                    promotionId,
                    ruleVersion,
                    BenefitType.valueOf(benefitType),
                    matchedTierId,
                    tierRange,
                    precedenceOrder,
                    CalculationBase.valueOf(calculationBase),
                    Money.of(calculatedAmount)
            );
        }
    }

    record DiscardedDoc(String promotionId, int ruleVersion, String reasonCode) {
        static DiscardedDoc fromDomain(DiscardedEligiblePromotion d) {
            return new DiscardedDoc(d.promotionId(), d.ruleVersion(), d.reasonCode().name());
        }

        DiscardedEligiblePromotion toDomain() {
            return new DiscardedEligiblePromotion(promotionId, ruleVersion, ReasonCode.valueOf(reasonCode));
        }
    }

    record CandidateDoc(String promotionId, int ruleVersion, String status, String reasonCode, String calculatedAmount) {
        static CandidateDoc fromDomain(CandidateDecision c) {
            return new CandidateDoc(
                    c.promotionId(),
                    c.ruleVersion(),
                    c.status().name(),
                    c.reasonCode().name(),
                    c.calculatedAmount().toString()
            );
        }

        CandidateDecision toDomain() {
            return new CandidateDecision(
                    promotionId,
                    ruleVersion,
                    CandidateStatus.valueOf(status),
                    ReasonCode.valueOf(reasonCode),
                    Money.of(calculatedAmount)
            );
        }
    }

    record FrozenRefDoc(
            String promotionId,
            int ruleVersion,
            String validFrom,
            String validTo,
            Integer maxUsagePerUser,
            String maxCampaignBudget
    ) {
        static FrozenRefDoc fromDomain(FrozenRuleRef r) {
            return new FrozenRefDoc(
                    r.promotionId(),
                    r.ruleVersion(),
                    r.validFrom().toString(),
                    r.validTo().toString(),
                    r.maxUsagePerUser(),
                    r.maxCampaignBudget() != null ? r.maxCampaignBudget().toString() : null
            );
        }

        FrozenRuleRef toDomain() {
            return new FrozenRuleRef(
                    promotionId,
                    ruleVersion,
                    Instant.parse(validFrom),
                    Instant.parse(validTo),
                    maxUsagePerUser,
                    maxCampaignBudget != null ? Money.of(maxCampaignBudget) : null
            );
        }
    }

    record SnapshotDoc(
            String orderId,
            String userId,
            String evaluationId,
            String checkoutKey,
            String evaluatedAt,
            String committedAt,
            String grossAmount,
            String eligibleAmount,
            String totalDiscountAmount,
            String totalCashbackAmount,
            String finalPayableAmount,
            List<AppliedDoc> appliedPromotions,
            List<DiscardedDoc> discardedEligiblePromotions
    ) {
        static SnapshotDoc fromDomain(DecisionSnapshot s) {
            return new SnapshotDoc(
                    s.orderId(),
                    s.userId(),
                    s.evaluationId(),
                    s.checkoutKey(),
                    s.evaluatedAt().toString(),
                    s.committedAt().toString(),
                    s.grossAmount().toString(),
                    s.eligibleAmount().toString(),
                    s.totalDiscountAmount().toString(),
                    s.totalCashbackAmount().toString(),
                    s.finalPayableAmount().toString(),
                    s.appliedPromotions().stream().map(AppliedDoc::fromDomain).toList(),
                    s.discardedEligiblePromotions().stream().map(DiscardedDoc::fromDomain).toList()
            );
        }

        DecisionSnapshot toDomain() {
            return new DecisionSnapshot(
                    orderId,
                    userId,
                    evaluationId,
                    checkoutKey,
                    Instant.parse(evaluatedAt),
                    Instant.parse(committedAt),
                    Money.of(grossAmount),
                    Money.of(eligibleAmount),
                    Money.of(totalDiscountAmount),
                    Money.of(totalCashbackAmount),
                    Money.of(finalPayableAmount),
                    appliedPromotions.stream().map(AppliedDoc::toDomain).toList(),
                    discardedEligiblePromotions.stream().map(DiscardedDoc::toDomain).toList()
            );
        }
    }

    record GrantDoc(String promotionId, int ruleVersion, String tierRange, String amount) {}

    record CashbackEventDoc(
            String eventId,
            String eventType,
            String eventVersion,
            String occurredAt,
            String correlationId,
            String traceId,
            String orderId,
            String userId,
            String evaluationId,
            String checkoutKey,
            String totalCashbackAmount,
            List<GrantDoc> grants
    ) {
        static CashbackEventDoc fromDomain(CashbackGrantEvent e) {
            return new CashbackEventDoc(
                    e.eventId(),
                    "CashbackGrantedEvent",
                    "1.0",
                    e.occurredAt().toString(),
                    e.correlationId(),
                    e.traceId(),
                    e.orderId(),
                    e.userId(),
                    e.evaluationId(),
                    e.checkoutKey(),
                    e.totalCashbackAmount().toString(),
                    e.grants().stream()
                            .map(g -> new GrantDoc(g.promotionId(), g.ruleVersion(), g.tierRange(), g.amount().toString()))
                            .toList()
            );
        }

        CashbackGrantEvent toDomain() {
            return new CashbackGrantEvent(
                    eventId,
                    orderId,
                    userId,
                    evaluationId,
                    checkoutKey,
                    Money.of(totalCashbackAmount),
                    grants.stream()
                            .map(g -> new CashbackGrantEvent.GrantItem(
                                    g.promotionId(), g.ruleVersion(), g.tierRange(), Money.of(g.amount())))
                            .toList(),
                    Instant.parse(occurredAt),
                    correlationId,
                    traceId
            );
        }
    }
}
