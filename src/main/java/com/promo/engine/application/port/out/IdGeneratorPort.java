package com.promo.engine.application.port.out;

public interface IdGeneratorPort {
    String nextEvaluationId();

    String nextEventId();
}
