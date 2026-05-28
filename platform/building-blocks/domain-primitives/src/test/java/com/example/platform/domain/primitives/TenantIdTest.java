package com.example.platform.domain.primitives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantIdTest {

  @Test
  void rejects_null_value() {
    assertThatNullPointerException()
        .isThrownBy(() -> new TenantId(null))
        .withMessageContaining("TenantId.value");
  }

  @Test
  void of_parses_a_canonical_uuid() {
    TenantId t = TenantId.of("0d8b2cb4-1d35-4f63-bcf1-7c1f7e3f9a51");
    assertThat(t.value()).isEqualTo(UUID.fromString("0d8b2cb4-1d35-4f63-bcf1-7c1f7e3f9a51"));
  }

  @Test
  void of_rejects_invalid_string() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> TenantId.of("not-a-uuid"))
        .withMessageContaining("not a valid tenant id");
  }

  @Test
  void newId_creates_unique_values() {
    TenantId a = TenantId.newId();
    TenantId b = TenantId.newId();
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void standard_is_a_stable_well_known_id() {
    assertThat(TenantId.STANDARD.isStandard()).isTrue();
    assertThat(TenantId.newId().isStandard()).isFalse();
    assertThat(TenantId.STANDARD.toString()).isEqualTo("00000000-0000-0000-0000-000000000001");
  }

  @Test
  void toString_returns_the_uuid_canonical_form() {
    UUID v = UUID.randomUUID();
    assertThat(new TenantId(v)).hasToString(v.toString());
  }

  @Test
  void equals_is_value_based() {
    UUID v = UUID.randomUUID();
    assertThat(new TenantId(v)).isEqualTo(new TenantId(v));
  }
}
