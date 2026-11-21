package com.promo.engine.application.port.out;

import com.promo.engine.domain.model.CandidateDecision;
import com.promo.engine.domain.model.DecisionSnapshot;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.PromotionRule;

import java.util.List;

public interface AuditEventPublisher {

    void recordEvaluation(
            String correlationId,
            String traceId,
            String channel,
            EvaluationQuote quote,
            long latencyMs
    );

    void recordCommit(
            String correlationId,
            String traceId,
            String channel,
            DecisionSnapshot snapshot,
            List<CandidateDecision> candidates,
            boolean idempotentReplay,
            boolean conflictRecovered,
            long latencyMs
    );

    void recordCommitRejection(
            String correlationId,
            String traceId,
            String channel,
            String userId,
            String orderId,
            String evaluationId,
            String checkoutKey,
            String errorCode,
            long latencyMs
    );

    void recordRulePublished(
            String correlationId,
            String traceId,
            PromotionRule rule
    );
}
