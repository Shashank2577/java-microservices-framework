# domain-primitives

Shared value objects used across every service's domain layer. Pure Java; no Spring, no JPA, no Jackson.

## What's in here

- **`TenantId`** — typed wrapper around `UUID` for the framework's unit of isolation. Constant `TenantId.STANDARD` is the control-plane / standard tenant.
- **`Money`** — immutable amount + currency. Scaled per currency's default fraction digits. Operations between different currencies throw.
- **`Result<T, E>`** — sealed success/failure carrier with `map`, `flatMap`, `orElseThrow`. Used in domain logic where throwing would be control-flow.

## Conventions

- Records preferred. Constructors validate invariants and throw `IllegalArgumentException` / `NullPointerException`.
- Public API gets Javadoc; the build fails on missing Javadoc here (this is a published library).
- Tests aim for 100% line + branch + mutation coverage. These types are tiny — the bar is non-negotiable.

## Adding a new primitive

Before adding a type to this package:

1. Confirm it's *used by ≥ 2 services* (otherwise it belongs in a single service's domain).
2. Confirm it has *no dependencies outside `java.*`* and `domain-primitives` itself.
3. Write the tests first; the type's invariants must be obvious from the test names.
4. Open an ADR if the type encodes a non-obvious policy.

## See also

- `.claude/skills/java-architecture/SKILL.md` §3 — domain-layer rules
- `.claude/skills/java-patterns-gof/SKILL.md` — sealed types + pattern matching (basis for `Result`)
