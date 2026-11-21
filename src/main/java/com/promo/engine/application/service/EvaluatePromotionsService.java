package com.promo.engine.application.service;

import com.promo.engine.application.port.in.EvaluatePromotionsUseCase;
import com.promo.engine.application.port.out.AuditEventPublisher;
import com.promo.engine.application.port.out.ClockPort;
import com.promo.engine.application.port.out.EvaluationQuoteRepository;
import com.promo.engine.application.port.out.IdGeneratorPort;
import com.promo.engine.application.port.out.PromotionRuleRepository;
import com.promo.engine.application.port.out.QuotaReadRepository;
import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.model.EvaluationContext;
import com.promo.engine.domain.model.EvaluationQuote;
import com.promo.engine.domain.model.EvaluationResult;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.QuotaSnapshot;
import com.promo.engine.domain.service.PromotionEvaluationEngine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class EvaluatePromotionsService implements EvaluatePromotionsUseCase {

    private final PromotionRuleRepository ruleRepository;
    private final QuotaReadRepository quotaReadRepository;
    private final EvaluationQuoteRepository quoteRepository;
    private final PromotionEvaluationEngine evaluationEngine;
    private final AuditEventPublisher auditEventPublisher;
    private final ClockPort clock;
    private final IdGeneratorPort idGenerator;
    private final Duration quoteTtl;
    private final ConflictPolicy defaultConflictPolicy;
    private final int maxStackingLimit;

    public EvaluatePromotionsService(
            PromotionRuleRepository ruleRepository,
            QuotaReadRepository quotaReadRepository,
            EvaluationQuoteRepository quoteRepository,
            PromotionEvaluationEngine evaluationEngine,
            AuditEventPublisher auditEventPublisher,
            ClockPort clock,
            IdGeneratorPort idGenerator,
            Duration quoteTtl,
            ConflictPolicy defaultConflictPolicy,
            int maxStackingLimit
    ) {
        this.ruleRepository = ruleRepository;
        this.quotaReadRepository = quotaReadRepository;
        this.quoteRepository = quoteRepository;
        this.evaluationEngine = evaluationEngine;
        this.auditEventPublisher = auditEventPublisher;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.quoteTtl = quoteTtl;
        this.defaultConflictPolicy = defaultConflictPolicy;
        this.maxStackingLimit = maxStackingLimit;
    }

    @Override
    public EvaluationQuote evaluate(EvaluateCommand command) {
        long startNanos = System.nanoTime();
        Instant now = clock.now();
        ConflictPolicy policy = command.conflictPolicyOverride() != null
                ? command.conflictPolicyOverride()
                : defaultConflictPolicy;

        // Fail fast on invalid monetary/cart inputs before making any DynamoDB network calls
        EvaluationContext staticContext = new EvaluationContext(
                command.targetUserId(),
                command.principal().segments(),
                command.grossAmount(),
                command.eligibleAmount(),
                command.items(),
                now,
                policy,
                maxStackingLimit,
                Map.of()
        );
        evaluationEngine.validateInput(staticContext);

        List<PromotionRule> activeRules = ruleRepository.findAllActive();

        // Latency optimization: run static eligibility checks in memory before DynamoDB BatchGetItem

        Set<String> quotaLookupIds = activeRules.stream()
                .filter(rule -> rule.maxUsagePerUser() != null || rule.maxCampaignBudget() != null)
                .filter(rule -> evaluationEngine.eligibilityEvaluator()
                        .checkStaticEligibility(rule, staticContext).isEmpty())
                .map(PromotionRule::promotionId)
                .collect(Collectors.toSet());

        Map<String, QuotaSnapshot> quotas = quotaLookupIds.isEmpty()
                ? Map.of()
                : quotaReadRepository.findQuotasAndBudgets(command.targetUserId(), quotaLookupIds);

        EvaluationContext fullContext = new EvaluationContext(
                command.targetUserId(),
                command.principal().segments(),
                command.grossAmount(),
                command.eligibleAmount(),
                command.items(),
                now,
                policy,
                maxStackingLimit,
                quotas
        );

        EvaluationResult result = evaluationEngine.evaluate(activeRules, fullContext);
        String evaluationId = idGenerator.nextEvaluationId();
        Instant expiresAt = now.plus(quoteTtl);

        EvaluationQuote quote = new EvaluationQuote(
                evaluationId,
                command.targetUserId(),
                now,
                expiresAt,
                result.grossAmount(),
                result.eligibleAmount(),
                result.totalDiscountAmount(),
                result.totalCashbackAmount(),
                result.finalPayableAmount(),
                result.appliedPromotions(),
                result.discardedEligiblePromotions(),
                result.candidateDecisions(),
                result.frozenRuleRefs()
        );

        quoteRepository.save(quote);

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditEventPublisher.recordEvaluation(
                command.correlationId(),
                command.traceId(),
                command.channel(),
                quote,
                latencyMs
        );

        return quote;
    }
}
