package com.promo.engine.domain.exception;

public class IdempotencyConflictException extends DomainException {
    public IdempotencyConflictException(String checkoutKey) {
        super("IDEMPOTENCY_KEY_CONFLICT",
                "Idempotency key '" + checkoutKey + "' was already used with a different orderId or evaluationId");
    }
}
