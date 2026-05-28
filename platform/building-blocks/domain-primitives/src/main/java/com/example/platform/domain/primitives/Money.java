package com.example.platform.domain.primitives;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Money — immutable amount + currency. Never {@code double} or {@code float}.
 *
 * <p>Scale follows the currency's default fraction digits (e.g., USD = 2,
 * BHD = 3). Operations between different currencies throw — explicit
 * conversion is required upstream.
 *
 * @see com.example.platform.domain.primitives package documentation
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "Money.amount");
        Objects.requireNonNull(currency, "Money.currency");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
    }

    /** Construct from a {@link BigDecimal} and ISO-4217 currency code. */
    public static Money of(BigDecimal amount, String isoCurrencyCode) {
        return new Money(amount, Currency.getInstance(isoCurrencyCode));
    }

    /** Convenience constructor from a {@code long} of minor units. */
    public static Money ofMinor(long minorUnits, String isoCurrencyCode) {
        Currency c = Currency.getInstance(isoCurrencyCode);
        BigDecimal value = BigDecimal.valueOf(minorUnits)
                .movePointLeft(c.getDefaultFractionDigits());
        return new Money(value, c);
    }

    /** Zero amount in the given currency. */
    public static Money zero(String isoCurrencyCode) {
        return Money.of(BigDecimal.ZERO, isoCurrencyCode);
    }

    /**
     * Add another {@code Money} of the same currency.
     *
     * @throws IllegalArgumentException if currencies differ
     */
    public Money add(Money other) {
        sameCurrency(other, "add");
        return new Money(this.amount.add(other.amount), currency);
    }

    /**
     * Subtract another {@code Money} of the same currency.
     *
     * @throws IllegalArgumentException if currencies differ
     */
    public Money subtract(Money other) {
        sameCurrency(other, "subtract");
        return new Money(this.amount.subtract(other.amount), currency);
    }

    /** Multiply by a scalar (e.g., quantity). */
    public Money multiply(int factor) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    /** {@code true} if amount is strictly greater than zero. */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** {@code true} if amount is exactly zero. */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    private void sameCurrency(Money other, String op) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot " + op + " " + this.currency.getCurrencyCode()
                            + " and " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
