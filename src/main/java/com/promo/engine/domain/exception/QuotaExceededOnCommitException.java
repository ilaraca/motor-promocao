package com.promo.engine.domain.exception;

import com.promo.engine.domain.model.ReasonCode;

public class QuotaExceededOnCommitException extends DomainException {
    public QuotaExceededOnCommitException(ReasonCode reasonCode, String message) {
        super(reasonCode.name(), message);
    }
}
