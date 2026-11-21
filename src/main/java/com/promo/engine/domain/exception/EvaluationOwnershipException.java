package com.promo.engine.domain.exception;

public class EvaluationOwnershipException extends DomainException {
    public EvaluationOwnershipException(String evaluationId) {
        super("EVALUATION_OWNERSHIP_MISMATCH", "Authenticated user does not own evaluation: " + evaluationId);
    }
}
