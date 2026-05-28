/**
 * Domain primitives — value objects shared across every service's domain layer.
 *
 * <p>Strict rules for everything in this package:
 *
 * <ul>
 *   <li>Pure Java; no Spring, no JPA, no Jackson, no infrastructure imports.
 *   <li>Immutable; records preferred; defensive copies on collection inputs.
 *   <li>Construction validates invariants and throws {@link IllegalArgumentException}.
 *   <li>{@code equals}/{@code hashCode} based on identity-defining fields only.
 *   <li>Public API documented; missing Javadoc fails the build.
 * </ul>
 *
 * <p>Enforced by ArchUnit rules in {@code test-support} and by the framework validator at {@code
 * tools/validate_framework.py}.
 */
package com.example.platform.domain.primitives;
