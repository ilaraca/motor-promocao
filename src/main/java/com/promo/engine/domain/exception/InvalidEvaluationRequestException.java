package com.promo.engine.domain.exception;

public class InvalidEvaluationRequestException extends DomainException {
    public InvalidEvaluationRequestException(String message) {
        super("INVALID_EVALUATION_REQUEST", message);
    }
}
