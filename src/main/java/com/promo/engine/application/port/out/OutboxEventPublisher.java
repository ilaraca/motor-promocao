package com.promo.engine.application.port.out;

import com.promo.engine.domain.model.CashbackGrantEvent;

/**
 * Decoupled messaging port — implemented by SQS in this POC,
 * trivially swappable to Kafka without touching domain or application layers.
 */
public interface OutboxEventPublisher {
    void publishCashbackGranted(CashbackGrantEvent event);
}
