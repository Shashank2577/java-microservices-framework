---
type: component
name: domain-primitives
slug: domain-primitives
kind: building-block
lifecycle: production
owner-team: platform
created: 2026-05-28
catalog-info: platform/building-blocks/domain-primitives/catalog-info.yaml
status: active
linked-decisions: []
linked-adrs: []
---

# domain-primitives

## Purpose

Shared value objects used across every service's domain layer. Pure Java; no Spring, no JPA, no Jackson, no infrastructure. The framework's discipline starts here: if it can't be expressed using only `java.*` and these primitives, it doesn't belong in the domain layer.

## Current shape

- **Language / framework**: Java 21 pure types; no framework dependencies.
- **Build**: Gradle 8 KTS via `platform/building-blocks/domain-primitives/build.gradle.kts`. Uses the root version catalog. Plain `java-library` plugin only.
- **Tests**: JUnit 5 + AssertJ + Mockito (bundle `libs.bundles.spring.test`).
- **Coverage target**: 100% line + branch + mutation (per `java-code-quality` and the per-package 98% rule). Types are tiny; the bar is non-negotiable.

## Public types

- `TenantId` — typed wrapper around `UUID`, includes `STANDARD` constant for the control-plane tenant.
- `Money` — immutable amount + ISO-4217 currency, scaled per currency's default fraction digits.
- `Result<T, E>` — sealed `Success` / `Failure`, with `map` / `flatMap` / `orElseThrow`.

## Owners

- Primary: [[shashank]]
- Secondary: [[claude]]

## Key decisions

*(populated as decisions are made)*

## ADRs

*(none yet)*

## Related components

- Downstream: *(every service's domain layer)*
- Upstream: *(none — this is a leaf module)*

## Open drift items

- *(none)*

## Runbooks

*(none — it's a pure library; no operational concerns)*

## Notes

Rules for adding a new primitive:
1. Used by ≥ 2 services (otherwise belongs in one service's domain).
2. Zero dependencies outside `java.*` and `domain-primitives` itself.
3. Tests written first; type's invariants obvious from test names.
4. ADR if the type encodes a non-obvious policy.
