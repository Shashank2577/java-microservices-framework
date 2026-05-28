package com.example.platform.domain.primitives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void rejects_null_inputs() {
    assertThatNullPointerException().isThrownBy(() -> Money.of(null, "USD"));
    assertThatNullPointerException().isThrownBy(() -> Money.of(BigDecimal.TEN, null));
  }

  @Test
  void scales_to_currency_default_fraction_digits() {
    // USD has 2 digits
    Money usd = Money.of(new BigDecimal("12.3456"), "USD");
    assertThat(usd.amount()).isEqualByComparingTo("12.35");

    // BHD has 3 digits
    Money bhd = Money.of(new BigDecimal("12.3456"), "BHD");
    assertThat(bhd.amount()).isEqualByComparingTo("12.346");

    // JPY has 0 digits
    Money jpy = Money.of(new BigDecimal("12.5"), "JPY");
    assertThat(jpy.amount()).isEqualByComparingTo("12");
  }

  @Test
  void ofMinor_constructs_from_minor_units() {
    Money usd = Money.ofMinor(1999, "USD");
    assertThat(usd.amount()).isEqualByComparingTo("19.99");
    Money bhd = Money.ofMinor(1999, "BHD");
    assertThat(bhd.amount()).isEqualByComparingTo("1.999");
  }

  @Test
  void add_same_currency() {
    Money result =
        Money.of(new BigDecimal("10.00"), "USD").add(Money.of(new BigDecimal("5.50"), "USD"));
    assertThat(result.amount()).isEqualByComparingTo("15.50");
  }

  @Test
  void add_rejects_different_currencies() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> Money.of(new BigDecimal("10"), "USD").add(Money.of(new BigDecimal("10"), "EUR")))
        .withMessageContaining("USD")
        .withMessageContaining("EUR");
  }

  @Test
  void subtract_same_currency() {
    Money result =
        Money.of(new BigDecimal("10.00"), "USD").subtract(Money.of(new BigDecimal("3.50"), "USD"));
    assertThat(result.amount()).isEqualByComparingTo("6.50");
  }

  @Test
  void multiply_by_scalar() {
    Money result = Money.of(new BigDecimal("2.50"), "USD").multiply(4);
    assertThat(result.amount()).isEqualByComparingTo("10.00");
  }

  @Test
  void zero_factory() {
    Money zero = Money.zero("USD");
    assertThat(zero.isZero()).isTrue();
    assertThat(zero.isPositive()).isFalse();
  }

  @Test
  void positive_detection() {
    assertThat(Money.of(new BigDecimal("0.01"), "USD").isPositive()).isTrue();
    assertThat(Money.of(BigDecimal.ZERO, "USD").isPositive()).isFalse();
    assertThat(Money.of(new BigDecimal("-0.01"), "USD").isPositive()).isFalse();
  }

  @Test
  void toString_is_plain_amount_plus_iso_code() {
    assertThat(Money.of(new BigDecimal("19.99"), "USD")).hasToString("19.99 USD");
  }
}
