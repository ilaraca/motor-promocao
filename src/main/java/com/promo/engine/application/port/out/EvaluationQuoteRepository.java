package com.promo.engine.application.port.out;

import com.promo.engine.domain.model.EvaluationQuote;

import java.util.Optional;

public interface EvaluationQuoteRepository {

    void save(EvaluationQuote quote);

    Optional<EvaluationQuote> findById(String evaluationId);
}
