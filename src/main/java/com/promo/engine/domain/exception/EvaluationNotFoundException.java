package com.promo.engine.domain.exception;

public class EvaluationNotFoundException extends DomainException {
    public EvaluationNotFoundException(String evaluationId) {
        super("EVALUATION_EXPIRED", "Evaluation quote not found or expired: " + evaluationId);
    }
}
