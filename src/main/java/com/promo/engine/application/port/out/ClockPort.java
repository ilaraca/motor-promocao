package com.promo.engine.application.port.out;

import java.time.Instant;

public interface ClockPort {
    Instant now();
}
