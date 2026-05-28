package com.example.platform.domain.primitives;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Result&lt;T, E&gt; — explicit success/failure in the domain layer where
 * throwing an exception would be control flow rather than exception.
 *
 * <p>Sealed: callers exhaustively pattern-match without a default branch.
 * See {@code java-patterns-gof/SKILL.md} (sealed types replace classical Visitor).
 *
 * @param <T> success payload type
 * @param <E> failure payload type
 */
public sealed interface Result<T, E> permits Result.Success, Result.Failure {

    /** Success carrier. */
    record Success<T, E>(T value) implements Result<T, E> {
        public Success {
            Objects.requireNonNull(value, "Success.value");
        }
    }

    /** Failure carrier. */
    record Failure<T, E>(E error) implements Result<T, E> {
        public Failure {
            Objects.requireNonNull(error, "Failure.error");
        }
    }

    /** Construct a success. */
    static <T, E> Result<T, E> success(T value) {
        return new Success<>(value);
    }

    /** Construct a failure. */
    static <T, E> Result<T, E> failure(E error) {
        return new Failure<>(error);
    }

    /** {@code true} if this is a {@link Success}. */
    default boolean isSuccess() {
        return this instanceof Success<T, E>;
    }

    /** {@code true} if this is a {@link Failure}. */
    default boolean isFailure() {
        return this instanceof Failure<T, E>;
    }

    /**
     * Transform a success value; failures pass through unchanged.
     *
     * @param mapper function applied to the success payload
     * @param <U>    new success payload type
     */
    default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
        return switch (this) {
            case Success<T, E> s -> success(mapper.apply(s.value()));
            case Failure<T, E> f -> failure(f.error());
        };
    }

    /**
     * Compose with another fallible operation; failures pass through unchanged.
     *
     * @param mapper function producing the next {@code Result}
     * @param <U>    new success payload type
     */
    default <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper) {
        return switch (this) {
            case Success<T, E> s -> mapper.apply(s.value());
            case Failure<T, E> f -> failure(f.error());
        };
    }

    /** Get the success value or throw the supplied exception on failure. */
    default <X extends Throwable> T orElseThrow(Supplier<X> ex) throws X {
        return switch (this) {
            case Success<T, E> s -> s.value();
            case Failure<T, E> f -> throw ex.get();
        };
    }
}
