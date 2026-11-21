package com.promo.engine.config;

import com.promo.engine.application.port.out.ClockPort;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System clock adapter that can also be shifted deterministically in integration tests
 * (e.g., testing TTL expiration without waiting 10 minutes).
 */
public final class MutableClockAdapter implements ClockPort {

    private final AtomicReference<Instant> fixedInstant = new AtomicReference<>(null);

    @Override
    public Instant now() {
        Instant fixed = fixedInstant.get();
        return fixed != null ? fixed : Instant.now();
    }

    public void fixAt(Instant instant) {
        fixedInstant.set(instant);
    }

    public void advanceBySeconds(long seconds) {
        fixedInstant.updateAndGet(cur -> (cur != null ? cur : Instant.now()).plusSeconds(seconds));
    }

    public void resetToSystem() {
        fixedInstant.set(null);
    }
}
