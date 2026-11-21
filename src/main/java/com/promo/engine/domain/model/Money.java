package com.promo.engine.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value object enforcing scale 2 and RoundingMode.HALF_EVEN
 * (Banker's Rounding) across the entire promotion engine.
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, ROUNDING_MODE));
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = Objects.requireNonNull(amount, "amount must not be null")
                .setScale(SCALE, ROUNDING_MODE);
    }

    public static Money of(BigDecimal value) {
        Objects.requireNonNull(value, "value must not be null");
        return new Money(value);
    }

    public static Money of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new Money(new BigDecimal(value.trim()));
    }

    public static Money ofCents(long cents) {
        return new Money(BigDecimal.valueOf(cents, SCALE));
    }

    public BigDecimal amount() {
        return amount;
    }

    public long toCents() {
        return amount.movePointRight(SCALE).longValueExact();
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    public Money percentage(BigDecimal percentRate) {
        Objects.requireNonNull(percentRate, "percentRate must not be null");
        BigDecimal raw = this.amount.multiply(percentRate)
                .divide(ONE_HUNDRED, SCALE, ROUNDING_MODE);
        return new Money(raw);
    }

    public Money min(Money other) {
        return this.compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return this.compareTo(other) >= 0 ? this : other;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isGreaterThan(Money other) {
        return this.compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return this.compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return this.compareTo(other) < 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return this.compareTo(other) <= 0;
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return this.amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
