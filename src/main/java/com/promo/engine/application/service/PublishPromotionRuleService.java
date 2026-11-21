package com.promo.engine.application.service;

import com.promo.engine.application.port.in.PublishPromotionRuleUseCase;
import com.promo.engine.application.port.out.AuditEventPublisher;
import com.promo.engine.application.port.out.ClockPort;
import com.promo.engine.application.port.out.PromotionRuleRepository;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.RuleStatus;
import com.promo.engine.domain.validation.PromotionRuleValidator;

import java.util.List;
import java.util.Optional;

public final class PublishPromotionRuleService implements PublishPromotionRuleUseCase {

    private final PromotionRuleRepository ruleRepository;
    private final PromotionRuleValidator ruleValidator;
    private final AuditEventPublisher auditEventPublisher;
    private final ClockPort clock;

    public PublishPromotionRuleService(
            PromotionRuleRepository ruleRepository,
            PromotionRuleValidator ruleValidator,
            AuditEventPublisher auditEventPublisher,
            ClockPort clock
    ) {
        this.ruleRepository = ruleRepository;
        this.ruleValidator = ruleValidator;
        this.auditEventPublisher = auditEventPublisher;
        this.clock = clock;
    }

    @Override
    public PromotionRule publish(PublishRuleCommand command) {
        Optional<Integer> latestOpt = ruleRepository.findLatestVersion(command.promotionId());
        int nextVersion = latestOpt.map(v -> v + 1).orElse(1);

        PromotionRule newRule = new PromotionRule(
                command.promotionId(),
                nextVersion,
                RuleStatus.ACTIVE,
                command.name(),
                command.validFrom(),
                command.validTo(),
                command.benefitType(),
                command.stackabilityMode(),
                command.priorityRank(),
                command.precedenceOrder(),
                command.calculationBase(),
                command.minOrderValue(),
                command.maxBenefitAmount(),
                command.maxUsagePerUser(),
                command.maxCampaignBudget(),
                command.eligibleSegments(),
                command.eligibleCategories(),
                command.tiers(),
                command.actorId(),
                clock.now()
        );

        // Layer 1 (Design-Time) validation
        ruleValidator.validate(newRule);

        PromotionRule persisted = ruleRepository.publishNewVersion(newRule, latestOpt.orElse(null));
        auditEventPublisher.recordRulePublished(command.correlationId(), command.traceId(), persisted);
        return persisted;
    }

    @Override
    public List<PromotionRule> listActiveRules() {
        return ruleRepository.findAllActive();
    }
}
