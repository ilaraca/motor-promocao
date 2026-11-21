package com.promo.engine.config;

import com.promo.engine.application.port.in.CommitPromotionsUseCase;
import com.promo.engine.application.port.in.EvaluatePromotionsUseCase;
import com.promo.engine.application.port.in.GetOrderSnapshotUseCase;
import com.promo.engine.application.port.in.PublishPromotionRuleUseCase;
import com.promo.engine.application.port.out.AuditEventPublisher;
import com.promo.engine.application.port.out.ClockPort;
import com.promo.engine.application.port.out.CommitTransactionPort;
import com.promo.engine.application.port.out.EvaluationQuoteRepository;
import com.promo.engine.application.port.out.IdGeneratorPort;
import com.promo.engine.application.port.out.PromotionRuleRepository;
import com.promo.engine.application.port.out.QuotaReadRepository;
import com.promo.engine.application.service.CommitPromotionsService;
import com.promo.engine.application.service.EvaluatePromotionsService;
import com.promo.engine.application.service.GetOrderSnapshotService;
import com.promo.engine.application.service.PublishPromotionRuleService;
import com.promo.engine.domain.model.ConflictPolicy;
import com.promo.engine.domain.service.PromotionEvaluationEngine;
import com.promo.engine.domain.strategy.BenefitStrategyRegistry;
import com.promo.engine.domain.validation.PromotionRuleValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class DomainConfig {

    @Bean
    public MutableClockAdapter clockPort() {
        return new MutableClockAdapter();
    }

    @Bean
    public IdGeneratorPort idGeneratorPort() {
        return new IdGeneratorPort() {
            @Override
            public String nextEvaluationId() {
                return "eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }

            @Override
            public String nextEventId() {
                return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }
        };
    }

    @Bean
    public BenefitStrategyRegistry benefitStrategyRegistry() {
        return BenefitStrategyRegistry.defaultRegistry();
    }

    @Bean
    public PromotionEvaluationEngine promotionEvaluationEngine(BenefitStrategyRegistry registry) {
        return new PromotionEvaluationEngine(registry);
    }

    @Bean
    public PromotionRuleValidator promotionRuleValidator() {
        return new PromotionRuleValidator();
    }

    @Bean
    public EvaluatePromotionsUseCase evaluatePromotionsUseCase(
            PromotionRuleRepository ruleRepository,
            QuotaReadRepository quotaReadRepository,
            EvaluationQuoteRepository quoteRepository,
            PromotionEvaluationEngine evaluationEngine,
            AuditEventPublisher auditEventPublisher,
            ClockPort clock,
            IdGeneratorPort idGenerator,
            @Value("${promotions.engine.quote-ttl-seconds:600}") long quoteTtlSeconds,
            @Value("${promotions.engine.default-conflict-policy:PRIORITY_RANK}") ConflictPolicy defaultPolicy,
            @Value("${promotions.engine.max-stacking-limit:5}") int maxStackingLimit
    ) {
        return new EvaluatePromotionsService(
                ruleRepository,
                quotaReadRepository,
                quoteRepository,
                evaluationEngine,
                auditEventPublisher,
                clock,
                idGenerator,
                Duration.ofSeconds(quoteTtlSeconds),
                defaultPolicy,
                maxStackingLimit
        );
    }

    @Bean
    public CommitPromotionsUseCase commitPromotionsUseCase(
            EvaluationQuoteRepository quoteRepository,
            CommitTransactionPort commitTransactionPort,
            AuditEventPublisher auditEventPublisher,
            ClockPort clock,
            IdGeneratorPort idGenerator
    ) {
        return new CommitPromotionsService(
                quoteRepository,
                commitTransactionPort,
                auditEventPublisher,
                clock,
                idGenerator
        );
    }

    @Bean
    public PublishPromotionRuleUseCase publishPromotionRuleUseCase(
            PromotionRuleRepository ruleRepository,
            PromotionRuleValidator ruleValidator,
            AuditEventPublisher auditEventPublisher,
            ClockPort clock
    ) {
        return new PublishPromotionRuleService(ruleRepository, ruleValidator, auditEventPublisher, clock);
    }

    @Bean
    public GetOrderSnapshotUseCase getOrderSnapshotUseCase(CommitTransactionPort commitTransactionPort) {
        return new GetOrderSnapshotService(commitTransactionPort);
    }
}
