package com.promo.engine.domain.exception;

public class InvalidRuleDefinitionException extends DomainException {
    public InvalidRuleDefinitionException(String message) {
        super("INVALID_RULE_DEFINITION", message);
    }
}
