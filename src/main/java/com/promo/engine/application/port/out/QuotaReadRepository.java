package com.promo.engine.application.port.out;

import com.promo.engine.domain.model.QuotaSnapshot;

import java.util.Map;
import java.util.Set;

public interface QuotaReadRepository {
    Map<String, QuotaSnapshot> findQuotasAndBudgets(String userId, Set<String> promotionIds);
}
