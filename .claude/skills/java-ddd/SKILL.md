---
name: java-ddd
description: Use when discovering service boundaries from a PRD, running event storming, defining ubiquitous language, drawing a context map, or deciding inter-context relationships (customer/supplier vs conformist vs anti-corruption layer). Strategic DDD; complements java-architecture §7 (tactical DDD — aggregates, VOs, domain events).
---

# Strategic DDD for Java 21 + Spring Boot 3.4 Microservices

This skill covers **strategic** DDD. For **tactical** DDD (aggregates, value
objects, domain events, repositories, ACL adapter code shape, package layering)
read `java-architecture/SKILL.md` §7 — that is the partner to this skill and
this document deliberately does not duplicate it.

## 1. Why DDD here, not "just build it"

LLM-assisted design tends to flatten the domain. Without DDD discipline you get:

- Anemic entities with all logic dumped in `*Service` classes
- Cross-context entity sharing ("we'll just import the `Customer` class")
- Wrong service boundaries sliced by tech (`user-service`, `data-service`,
  `data-aggregator-service`) instead of by business capability
- Ubiquitous language drift — the same word means three things in three
  Slack threads on the same day

DDD makes the cost of boundary mistakes visible **early**, before the second
service ships and the wrong shape gets cast in concrete.

## 2. Strategic vs tactical

| Strategic (this skill)                       | Tactical (java-architecture §7)        |
| -------------------------------------------- | -------------------------------------- |
| Bounded contexts, context map                | Aggregates, entities, VOs              |
| Ubiquitous language                          | Domain events, domain services         |
| Subdomain types (core / supporting / generic)| Repositories, ACL adapters             |
| Context relationships                        | Layering, package structure            |

Use **strategic** for "should this be one service or two?". Use **tactical**
once the boundary is decided and you're modeling inside it.

## 3. Event Storming (discovery)

Default discovery technique. Run **with domain experts**, not alone.

1. **Big Picture Storming** — orange stickies = domain events
   (`OrderPlaced`, `PaymentCaptured`, `ShipmentDispatched`). Past-tense.
   Lay them on a timeline left-to-right.
2. **Process modeling** — add blue (commands that cause events),
   yellow (actors/roles), pink (external systems), red (hotspots/questions).
3. **Software design** — group events into **aggregates** (purple stickies);
   identify bounded contexts by where the event clusters thin out.

Output: a wall of stickies + a photograph + a written summary that becomes
the input to the architecture skill.

If you can't gather domain experts, run a synchronous interview transcript
through the same three steps — events first, commands second, aggregates
third. Never let the LLM generate events from its general knowledge; that's
how you get a generic e-commerce shape pasted onto a domain that isn't one.

## 4. Ubiquitous Language

A glossary **per bounded context**. Sample for the `orders` context:

| Term       | Meaning in `orders` context                              | Meaning elsewhere                           |
| ---------- | -------------------------------------------------------- | ------------------------------------------- |
| Order      | Customer intent to purchase; lifecycle DRAFT→…→DELIVERED | In `billing`, "order" = invoice line. Different. |
| Customer   | Referenced by `CustomerId`; we do **not** model `Customer` here | Owned by `identity` context             |
| Line item  | A product + qty on the order                             | —                                           |
| Fulfilment | Handoff to a shipper                                     | In `warehouse`, a different state machine   |

Rules:

- **Same word, different context → different model.** Never share types
  across contexts, even via a "shared-domain" jar.
- The glossary lives in the service repo at `docs/glossary.md`.
- Code names match the glossary exactly. Reject the PR if a controller
  method name diverges from a glossary verb.
- LLM hint: when generating code in a service, the agent **consults
  `glossary.md`**, not its general training knowledge.

## 5. Subdomain types

Classify each context:

- **Core** — competitive advantage. Invest most here. Never outsource.
  Full skill-compliance and DDD discipline mandatory.
- **Supporting** — necessary but not differentiating. Build pragmatically.
  Consider OSS where the fit is close.
- **Generic** — commodity (auth, billing, search, email delivery).
  Buy or use OSS. SaaS adapters with minimal domain logic.

Framework implication: a generic-subdomain service is allowed to skip the
tactical-DDD layering in `java-architecture` §7 in favour of a thin adapter
over the SaaS API — call this out in the ADR.

## 6. Context Map

A diagram showing how bounded contexts interact. Document in
`docs/context-map.md` per **system** (collection of services), with these
relationship types:

| Relationship                  | When                                                                                                          | Implementation in this framework                                              |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **Customer / Supplier**       | Downstream context depends on upstream, but downstream's needs influence upstream's API                       | REST contract; provider versions API; consumer drives Pact tests              |
| **Conformist**                | Downstream takes upstream's model as-is (no negotiating power)                                                | Direct API consumption; data copied with upstream's shape                     |
| **Anti-Corruption Layer (ACL)** | Downstream wants its own model; translates at the boundary                                                  | Adapter in `infrastructure/`; never let foreign concepts leak into domain     |
| **Partnership**               | Two contexts evolve together (high coupling, joint releases)                                                  | Often a smell — investigate whether they should be one context                |
| **Shared Kernel**             | A tiny shared model across two contexts                                                                       | Avoid. Rare. If used, a separately published artifact with rigid governance   |
| **Open Host Service**         | Context provides a stable public API consumed by many                                                         | Versioned REST + OpenAPI; backward-compatible only                            |
| **Published Language**        | The format that flows between contexts                                                                        | Avro schemas in Kafka events; OpenAPI for REST                                |

Update the map every time a new integration ships. **Stale maps are worse
than no map** — they actively lie.

## 7. Distillation — finding the core

Periodic exercise (quarterly):

- List every aggregate in every service.
- Rank by "if we lost this overnight, do customers leave?" 1–5.
- 5s are the **core**; that's where engineering time and senior attention go.
- 1–2s should be examined for "should this be SaaS?".

Distillation surfaces investment misalignment — a team spending 60% of its
time on a context that ranks 2 is a planning failure, not an engineering one.

## 8. Anti-Corruption Layer (ACL) — pattern

When integrating with an external system or a legacy module:

```java
// External library uses its own "Customer" — different shape, different rules
package com.thirdparty.crm;
public class Customer { /* their shape, their semantics */ }

// We define our own port in the application layer
package com.example.orders.application.port.out;
public interface CustomerLookup {
    Optional<com.example.orders.domain.model.Customer> findById(CustomerId id);
}

// Adapter translates at the boundary
package com.example.orders.infrastructure.http;
@Component
class CrmCustomerAdapter implements CustomerLookup {
    private final CrmClient crm;

    @Override
    public Optional<com.example.orders.domain.model.Customer> findById(CustomerId id) {
        return crm.fetchCustomer(id.value())
            .map(this::toDomain);              // translate THEIR shape to OURS
    }

    private com.example.orders.domain.model.Customer toDomain(com.thirdparty.crm.Customer x) {
        // map fields, drop what we don't need, validate invariants
        return new com.example.orders.domain.model.Customer(...);
    }
}
```

Domain **never** imports `com.thirdparty.crm.*`. ArchUnit rules per
`java-architecture` §6 enforce this — a violation fails the build.

## 9. When a Bounded Context Should Split

Smells:

- Two teams constantly editing different parts of the same service.
- Glossary has homonyms ("order" means X in this method, Y in that).
- Conditional logic threaded with "if this is the X flow vs the Y flow".
- Different non-functional needs in one service (latency-sensitive read
  path vs heavy nightly batch).
- Different release cadences fighting each other on the same deploy.

Split by event-storming **the conflict zone only**; create a new service
with its own DB, own deployment pipeline, own bounded context, own glossary.

## 10. When NOT to Split

- "We might need this someday" — premature. Defer until the smells above
  appear in real life.
- A handful of related entities under one team — keep as one context.
- The friction is process (planning, ownership), not architecture — fix
  the process; splitting won't help and adds distributed-system tax.
- Splitting to "reduce build times" — fix the build, not the topology.

## 11. Multi-tenancy and DDD

- The **standard / control-plane tenant** (see `java-multi-tenancy`) is
  its **own bounded context**, distinct from regular tenants. Tenant
  provisioning, billing rollups, cross-tenant audit live in this context.
- Regular tenants share the same bounded contexts but are isolated by
  schema (or row, depending on the multi-tenancy strategy).
- Do **not** model the standard tenant as a regular tenant with a flag —
  that's the homonym anti-pattern and it always rots.

## 12. Workflow

1. Event storming (or transcribed equivalent) at project start.
2. Bounded contexts identified from event clusters.
3. Context map drawn with relationship types per §6.
4. Per context: write `glossary.md`, model aggregates
   (tactical — `java-architecture` §7), implement the service.
5. Update the context map on every integration change.
6. Run the distillation exercise quarterly.

## 13. Documentation artifacts

In the repo, **per system** (a collection of services):

- `docs/event-storm.md` (or a photo + writeup)
- `docs/context-map.md` with diagram (Mermaid C4 model)
- One `docs/glossary.md` **per service**

In code:

- An ADR for every boundary decision (see `java-code-quality` §12)
- ArchUnit rules per `java-architecture` §6 already enforce
  no cross-context imports — those rules are how this skill stays honest

## 14. Anti-patterns — refuse

- Skipping event storming and guessing boundaries — produces the
  `user-service` / `data-service` anti-pattern.
- Sharing entity classes across services (even via a "shared-domain" jar).
- "Same word means the same thing everywhere" — DDD's whole point is the
  opposite.
- ACL skipped because "we don't really need it" — every external
  integration without an ACL eventually leaks.
- Context map living in someone's head rather than in the repo.
- Glossary that engineers don't update — make staleness a PR-rejection
  criterion in review.
- Partnership relationships left unresolved — usually means split the
  context or merge it, never leave it.
- Generating bounded contexts from the LLM's "typical e-commerce / SaaS
  shape" instead of the actual domain — refuse and demand a transcript.

## 15. Reference

- Eric Evans, *Domain-Driven Design* — the original book
- Vaughn Vernon, *Implementing Domain-Driven Design* — pragmatic complement
- microservices.io — service decomposition patterns
- `java-architecture/SKILL.md` §7 — tactical DDD (the partner to this skill)
- `.claude/skills/lib/jabrena/030-architecture-adr-general/references/030-architecture-adr-general.md` — ADR template
- `.claude/skills/lib/jabrena/033-architecture-diagrams/references/033-architecture-diagrams.md` — C4 / context map diagram patterns
