---
type: component
name: <component-name>
slug: <kebab-slug>
kind: service | starter | building-block | infra
lifecycle: experimental | production | deprecated
owner-team: <team-name>
created: YYYY-MM-DD
catalog-info: <path to catalog-info.yaml>
status: active
linked-decisions: []
linked-adrs: []
---

# <Component name>

## Purpose

*(One paragraph — what this component does, what bounded context it serves)*

## Current shape

*(How it's built — key tech choices. Should reflect reality. If reality drifts, drift detection fires.)*

- Language / framework: Java 21 + Spring Boot 3.4
- Data store: <e.g., Postgres schema-per-tenant>
- Cache: <e.g., Caffeine local>
- Messaging: <e.g., Kafka producer to `orders.order.*.v1`>
- External deps: <e.g., Stripe via Resilience4j-wrapped RestClient>
- Test stack: <e.g., JUnit 5 + Testcontainers + ArchUnit>

## Owners

*(People who own this component — primary first)*

- Primary: [[person-slug]]
- Secondary: [[person-slug]]

## Key decisions

*(Decisions that shape this component)*

- [[dec-NNNN]]

## ADRs

*(Promoted decisions in `docs/adrs/`)*

- ADR-NNNN

## Related components

- Upstream: [[component-name]] *(this component consumes from)*
- Downstream: [[component-name]] *(this component publishes to)*

## Open drift items

*(Set by drift detection — should normally be empty)*

- *(none)*

## Runbooks

*(Operational procedures — link to `docs/runbooks/`)*

- `docs/runbooks/<runbook-name>.md`

## Notes
