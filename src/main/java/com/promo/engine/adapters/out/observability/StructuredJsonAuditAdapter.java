package com.promo.engine.adapters.out.observability;

import com.promo.engine.application.port.out.AuditEventPublisher;
import com.promo.engine.domain.model.CandidateDecision;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.PromotionRule;
import io.micrometer.core.instrument.MeterRegistry;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StructuredJsonAuditAdapter implements AuditEventPublisher {

    private static final Logger auditLog = LoggerFactory.getLogger("PROMOTION_AUDIT");

    private final MeterRegistry meterRegistry;

    public StructuredJsonAuditAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordEvaluation(
            String correlationId,
            String traceId,
            String channel,
            EvaluationQuote quote,
            long latencyMs
    ) {
        meterRegistry.timer("promotions.operation.duration",
                "operation", "evaluate",
                "outcome", "success").record(Duration.ofMillis(latencyMs));

        for (CandidateDecision candidate : quote.candidateDecisions()) {
            meterRegistry.counter("promotions.candidate.decisions.total",
                    "status", candidate.status().name(),
                    "reasonCode", candidate.reasonCode().name()).increment();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("grossAmount", quote.grossAmount().toString());
        summary.put("eligibleAmount", quote.eligibleAmount().toString());
        summary.put("totalDiscountAmount", quote.totalDiscountAmount().toString());
        summary.put("totalCashbackAmount", quote.totalCashbackAmount().toString());
        summary.put("finalPayableAmount", quote.finalPayableAmount().toString());

        List<Map<String, Object>> candidates = quote.candidateDecisions().stream()
                .map(this::toCandidateMap)
                .toList();

        auditLog.info("PROMOTION_EVALUATION_COMPLETED",
                StructuredArguments.kv("eventName", "PROMOTION_EVALUATION_COMPLETED"),
                StructuredArguments.kv("operation", "EVALUATE"),
                StructuredArguments.kv("correlationId", correlationId),
                StructuredArguments.kv("traceId", traceId),
                StructuredArguments.kv("channel", channel),
                StructuredArguments.kv("userId", quote.userId()),
                StructuredArguments.kv("orderId", null),
                StructuredArguments.kv("evaluationId", quote.evaluationId()),
                StructuredArguments.kv("checkoutKey", null),
                StructuredArguments.kv("latencyMs", latencyMs),
                StructuredArguments.kv("summary", summary),
                StructuredArguments.kv("candidates", candidates)
        );
    }

    @Override
    public void recordCommit(
            String correlationId,
            String traceId,
            String channel,
            DecisionSnapshot snapshot,
            List<CandidateDecision> candidates,
            boolean idempotentReplay,
            boolean conflictRecovered,
            long latencyMs
    ) {
        meterRegistry.timer("promotions.operation.duration",
                "operation", "commit",
                "outcome", "success").record(Duration.ofMillis(latencyMs));

        if (idempotentReplay) {
            meterRegistry.counter("promotions.commit.idempotency.hits.total",
                    "resolution", conflictRecovered ? "CONFLICT_RECOVERY" : "FAST_PATH").increment();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("grossAmount", snapshot.grossAmount().toString());
        summary.put("eligibleAmount", snapshot.eligibleAmount().toString());
        summary.put("totalDiscountAmount", snapshot.totalDiscountAmount().toString());
        summary.put("totalCashbackAmount", snapshot.totalCashbackAmount().toString());
        summary.put("finalPayableAmount", snapshot.finalPayableAmount().toString());

        List<Map<String, Object>> candidateList = candidates.stream()
                .map(this::toCandidateMap)
                .toList();

        String eventName = idempotentReplay
                ? "PROMOTION_COMMIT_IDEMPOTENT_HIT"
                : "PROMOTION_COMMIT_COMPLETED";

        auditLog.info(eventName,
                StructuredArguments.kv("eventName", eventName),
                StructuredArguments.kv("operation", "COMMIT"),
                StructuredArguments.kv("correlationId", correlationId),
                StructuredArguments.kv("traceId", traceId),
                StructuredArguments.kv("channel", channel),
                StructuredArguments.kv("userId", snapshot.userId()),
                StructuredArguments.kv("orderId", snapshot.orderId()),
                StructuredArguments.kv("evaluationId", snapshot.evaluationId()),
                StructuredArguments.kv("checkoutKey", snapshot.checkoutKey()),
                StructuredArguments.kv("idempotentReplay", idempotentReplay),
                StructuredArguments.kv("conflictRecovered", conflictRecovered),
                StructuredArguments.kv("latencyMs", latencyMs),
                StructuredArguments.kv("summary", summary),
                StructuredArguments.kv("candidates", candidateList)
        );
    }

    @Override
    public void recordCommitRejection(
            String correlationId,
            String traceId,
            String channel,
            String userId,
            String orderId,
            String evaluationId,
            String checkoutKey,
            String errorCode,
            long latencyMs
    ) {
        meterRegistry.timer("promotions.operation.duration",
                "operation", "commit",
                "outcome", "rejected").record(Duration.ofMillis(latencyMs));
        meterRegistry.counter("promotions.commit.rejections.total",
                "errorCode", errorCode).increment();

        auditLog.warn("PROMOTION_COMMIT_REJECTED",
                StructuredArguments.kv("eventName", "PROMOTION_COMMIT_REJECTED"),
                StructuredArguments.kv("operation", "COMMIT"),
                StructuredArguments.kv("correlationId", correlationId),
                StructuredArguments.kv("traceId", traceId),
                StructuredArguments.kv("channel", channel),
                StructuredArguments.kv("userId", userId),
                StructuredArguments.kv("orderId", orderId),
                StructuredArguments.kv("evaluationId", evaluationId),
                StructuredArguments.kv("checkoutKey", checkoutKey),
                StructuredArguments.kv("reasonCode", errorCode),
                StructuredArguments.kv("latencyMs", latencyMs)
        );
    }

    @Override
    public void recordRulePublished(String correlationId, String traceId, PromotionRule rule) {
        auditLog.info("PROMOTION_RULE_VERSION_PUBLISHED",
                StructuredArguments.kv("eventName", "PROMOTION_RULE_VERSION_PUBLISHED"),
                StructuredArguments.kv("operation", "PUBLISH_RULE"),
                StructuredArguments.kv("correlationId", correlationId),
                StructuredArguments.kv("traceId", traceId),
                StructuredArguments.kv("promotionId", rule.promotionId()),
                StructuredArguments.kv("ruleVersion", rule.ruleVersion()),
                StructuredArguments.kv("benefitType", rule.benefitType().name()),
                StructuredArguments.kv("actorId", rule.createdBy())
        );
    }

    private Map<String, Object> toCandidateMap(CandidateDecision c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promotionId", c.promotionId());
        m.put("ruleVersion", c.ruleVersion());
        m.put("status", c.status().name());
        m.put("reasonCode", c.reasonCode().name());
        m.put("calculatedAmount", c.calculatedAmount().toString());
        return m;
    }
}
