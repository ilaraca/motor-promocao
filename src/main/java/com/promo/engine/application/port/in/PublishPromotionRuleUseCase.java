package com.promo.engine.application.port.in;

import com.promo.engine.domain.model.BenefitType;
import com.promo.engine.domain.model.CalculationBase;
import com.promo.engine.domain.model.Money;
import com.promo.engine.domain.model.ProgressiveTier;
import com.promo.engine.domain.model.PromotionRule;
import com.promo.engine.domain.model.StackabilityMode;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface PublishPromotionRuleUseCase {

    PromotionRule publish(PublishRuleCommand command);

    List<PromotionRule> listActiveRules();

    record PublishRuleCommand(
            String promotionId,
            String name,
            Instant validFrom,
            Instant validTo,
            BenefitType benefitType,
            StackabilityMode stackabilityMode,
            int priorityRank,
            int precedenceOrder,
            CalculationBase calculationBase,
            Money minOrderValue,
            Money maxBenefitAmount,
            Integer maxUsagePerUser,
            Money maxCampaignBudget,
            Set<String> eligibleSegments,
            Set<String> eligibleCategories,
            List<ProgressiveTier> tiers,
            String actorId,
            String correlationId,
            String traceId
    ) {}
}
