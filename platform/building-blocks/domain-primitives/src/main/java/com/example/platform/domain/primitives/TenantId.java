package com.example.platform.domain.primitives;

import java.util.Objects;
import java.util.UUID;

/**
 * Tenant identifier — a typed wrapper around a UUID so the type system catches
 * "passed a customer id where a tenant id was expected."
 *
 * <p>The framework's multi-tenancy posture (schema-per-tenant) treats this as
 * the unit of isolation: every repository query, every Kafka message, every
 * structured log line carries a {@code TenantId}.
 *
 * @see com.example.platform.domain.primitives package documentation
 */
public record TenantId(UUID value) {

    /** Reserved well-known id for the control-plane / standard tenant. */
    public static final TenantId STANDARD = new TenantId(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    public TenantId {
        Objects.requireNonNull(value, "TenantId.value");
    }

    /**
     * Construct from a string representation. The string must be a valid
     * lowercase UUID (RFC 4122 canonical form).
     *
     * @throws IllegalArgumentException if the string is not a valid UUID
     */
    public static TenantId of(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return new TenantId(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("not a valid tenant id: " + value, ex);
        }
    }

    /** Generate a fresh random tenant id. Useful in tests and tenant onboarding. */
    public static TenantId newId() {
        return new TenantId(UUID.randomUUID());
    }

    /** {@code true} if this is the control-plane / standard tenant. */
    public boolean isStandard() {
        return STANDARD.equals(this);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
