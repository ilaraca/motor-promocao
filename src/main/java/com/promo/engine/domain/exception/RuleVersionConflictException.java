package com.promo.engine.domain.exception;

public class RuleVersionConflictException extends DomainException {
    public RuleVersionConflictException(String promotionId, int version) {
        super("RULE_VERSION_CONFLICT",
                "Concurrent modification detected while publishing " + promotionId + " version " + version);
    }
}
