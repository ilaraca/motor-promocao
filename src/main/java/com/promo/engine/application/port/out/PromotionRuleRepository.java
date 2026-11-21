package com.promo.engine.application.port.out;

import com.promo.engine.domain.model.PromotionRule;

import java.util.List;
import java.util.Optional;

public interface PromotionRuleRepository {

    List<PromotionRule> findAllActive();

    Optional<Integer> findLatestVersion(String promotionId);

    PromotionRule publishNewVersion(PromotionRule newRule, Integer previousVersion);
}
