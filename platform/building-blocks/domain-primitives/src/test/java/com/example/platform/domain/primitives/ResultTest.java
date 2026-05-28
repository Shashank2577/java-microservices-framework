package com.example.platform.domain.primitives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ResultTest {

  @Test
  void success_carries_a_value() {
    Result<Integer, String> r = Result.success(42);
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.isFailure()).isFalse();
    assertThat(((Result.Success<Integer, String>) r).value()).isEqualTo(42);
  }

  @Test
  void failure_carries_an_error() {
    Result<Integer, String> r = Result.failure("boom");
    assertThat(r.isSuccess()).isFalse();
    assertThat(r.isFailure()).isTrue();
    assertThat(((Result.Failure<Integer, String>) r).error()).isEqualTo("boom");
  }

  @Test
  void map_transforms_success() {
    Result<Integer, String> mapped = Result.<Integer, String>success(10).map(i -> i * 3);
    assertThat(mapped.isSuccess()).isTrue();
    assertThat(((Result.Success<Integer, String>) mapped).value()).isEqualTo(30);
  }

  @Test
  void map_passes_failure_through() {
    Result<Integer, String> orig = Result.failure("err");
    Result<Integer, String> mapped = orig.map(i -> i * 3);
    assertThat(mapped.isFailure()).isTrue();
    assertThat(((Result.Failure<Integer, String>) mapped).error()).isEqualTo("err");
  }

  @Test
  void flatMap_chains_fallible_ops() {
    Result<Integer, String> r =
        Result.<Integer, String>success(2).flatMap(i -> Result.success(i + 1));
    assertThat(((Result.Success<Integer, String>) r).value()).isEqualTo(3);
  }

  @Test
  void flatMap_short_circuits_on_failure_in_either_step() {
    Result<Integer, String> r1 =
        Result.<Integer, String>success(2).flatMap(i -> Result.failure("downstream"));
    assertThat(r1.isFailure()).isTrue();
    assertThat(((Result.Failure<Integer, String>) r1).error()).isEqualTo("downstream");

    Result<Integer, String> r2 =
        Result.<Integer, String>failure("upstream").flatMap(i -> Result.success(i + 1));
    assertThat(r2.isFailure()).isTrue();
    assertThat(((Result.Failure<Integer, String>) r2).error()).isEqualTo("upstream");
  }

  @Test
  void orElseThrow_returns_value_on_success() throws Exception {
    Integer v =
        Result.<Integer, String>success(7).orElseThrow(() -> new IllegalStateException("no"));
    assertThat(v).isEqualTo(7);
  }

  @Test
  void orElseThrow_throws_supplied_exception_on_failure() {
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                Result.<Integer, String>failure("oops")
                    .orElseThrow(() -> new IllegalStateException("no")))
        .withMessage("no");
  }

  @Test
  void rejects_null_payloads() {
    assertThatNullPointerException().isThrownBy(() -> Result.success(null));
    assertThatNullPointerException().isThrownBy(() -> Result.failure(null));
  }
}
