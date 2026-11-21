package com.promo.engine.domain.exception;

public class EvaluationExpiredException extends DomainException {
    public EvaluationExpiredException(String errorCode, String message) {
        super(errorCode, message);
    }

    public static EvaluationExpiredException ttlExpired(String evaluationId) {
        return new EvaluationExpiredException(
                "EVALUATION_EXPIRED",
                "Evaluation quote has expired and requires re-evaluation: " + evaluationId
        );
    }

    public static EvaluationExpiredException ruleValidityExpired(String promotionId, int ruleVersion) {
        return new EvaluationExpiredException(
                "OUT_OF_VALIDITY_WINDOW",
                "Frozen rule " + promotionId + " v" + ruleVersion + " is outside its original validity window"
        );
    }
}
