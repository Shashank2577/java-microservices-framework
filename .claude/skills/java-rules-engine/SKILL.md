---
name: java-rules-engine
description: Use whenever evaluating whether to use a rules engine (Drools, Easy Rules, in-code strategy), how to structure rules, how to store/hot-reload them, and how to audit rule executions. Covers the decision matrix (when a rules engine pays off vs. when plain Java suffices), Drools KIE patterns, Easy Rules for simpler cases, multi-tenant rule sets, rule testing, and audit/traceability of rule firings.
---

# Rules Engines — Drools, Easy Rules, and Knowing When Not To

## 1. Decide first — do you actually need a rules engine?

| Situation                                                                | Use a rules engine?                            |
|--------------------------------------------------------------------------|------------------------------------------------|
| <10 rules, change rarely, written by engineers                           | **No** — strategy pattern + sealed types       |
| Rules change weekly+, written by engineers, audit + versioning matter    | **Maybe** — Easy Rules or Spring Expression Language |
| Rules change often, written/edited by **non-engineers** (analysts, ops)  | **Yes** — Drools / OpenL Tablets               |
| Fact base is large; complex chaining; "what fires what" matters          | **Yes** — Drools (RETE inference)              |
| Per-tenant rule customization, hot reload without deploy                 | **Yes** — Drools KIE with rule storage         |

**Framework default: don't reach for a rules engine.** The pain (rule debugging, opaque execution, deployment complexity) is real. Use it only when the matrix above says yes.

## 2. Plain-Java alternative (default)

Strategy pattern + sealed types covers most "rules" cleanly:

```java
sealed interface DiscountRule permits PercentOff, FixedOff, BogoFree {
    boolean supports(Order o);
    Money apply(Order o);
}

@Service
class DiscountEngine {
    private final List<DiscountRule> rules;        // Spring injects
    Money discountFor(Order o) {
        return rules.stream().filter(r -> r.supports(o))
            .map(r -> r.apply(o))
            .reduce(Money.ZERO, Money::add);
    }
}
```

Pros: typed, testable, easy to reason about. Cons: rule changes = deploy.

## 3. Easy Rules — light engine for engineer-authored rules

Use when you want named/audited rule executions but rules still live in code.

```java
@Rule(name = "vip-free-shipping", description = "VIP customers ship free")
class VipFreeShipping {
    @Condition boolean when(@Fact("order") Order o) { return o.customer().isVip(); }
    @Action   void then(@Fact("order") Order o) { o.setShippingFee(Money.ZERO); }
}
```

Each fire is observable (rule name + facts) → emit a Micrometer counter + INFO log.

### Easy Rules vs Drools — quick read

| Concern              | Easy Rules                       | Drools                                  |
|----------------------|----------------------------------|-----------------------------------------|
| Authoring            | Java code                        | DRL / decision tables / Java            |
| Inference            | None (linear evaluation)         | RETE (rules trigger rules)              |
| Hot reload           | Manual (redeploy or classloader) | First-class via `KieScanner`            |
| Non-engineer authors | No                               | Yes                                     |
| Cognitive cost       | Low                              | High (DRL semantics, agenda, salience)  |
| When to pick         | <200 rules, engineer-owned       | Non-engineer authoring or RETE required |

## 4. Drools — when non-engineers author rules

When and only when:
- Rules authored by domain experts who refuse to write Java.
- Rule decision tables (Excel) make sense for the business.
- You need RETE inference (rules trigger other rules).

KIE setup:
- `kie-spring-boot-starter`.
- Rules in `.drl` files OR Excel decision tables (`.xls`) — author preference.
- `KieContainer` rebuilt on rule change; serve via `KieScanner` for hot reload.

Minimal `.drl` shape — keep rules *declarative*, push procedural logic into facts:

```drl
package com.example.pricing
import com.example.pricing.facts.Order

rule "premium-tier-discount"
    salience 10
    when
        $o : Order(customer.tier == "PREMIUM", total > 500)
    then
        $o.applyDiscount(0.15, drools.getRule().getName());
end
```

Storage options:
- **Classpath** — simplest; rule change = rebuild + deploy.
- **DB-backed** — rules in `rule_set` table; admin UI uploads new versions; `KieScanner` reloads. Per-tenant rule sets viable.
- **Kogito / Business Central** — full authoring UI; heavier ops cost.

Pick classpath until you can show a business case for hot reload. The DB-backed path adds an admin UI, a publish workflow, and a reload bus — none of which is free.

## 5. Multi-tenant rule sets

Per `java-multi-tenancy`:
- Default rule set in `public.rule_sets`.
- Tenant overrides in the tenant's schema or a `tenant_rule_overrides` table.
- A `RuleSetResolver` picks the active set by tenant + use-case; cached with TTL invalidation on update event.
- Cache key includes `tenant_id` + `rule_set_version`.

```sql
CREATE TABLE public.rule_sets (
    id           BIGSERIAL PRIMARY KEY,
    use_case     TEXT NOT NULL,            -- 'pricing', 'fraud', etc.
    version      INT  NOT NULL,
    content      BYTEA NOT NULL,           -- compiled kjar or raw drl
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (use_case, version)             -- versions immutable
);
```

Hot reload:
- `KieScanner` polls the rule store every N seconds; OR
- Kafka event `rule_set.updated.v1` triggers reload in each replica.

## 6. Audit & traceability — non-negotiable

Every rule fire is observable:
- Micrometer counter: `rule_fires_total{rule_set, rule_name, tenant_class}`.
- Audit log entry (see `java-data-governance` §5) for every business-relevant rule fire: rule, version, inputs (redacted), outputs, timestamp.
- Replay capability: given an audit row, you can re-evaluate the rule with the historical version + inputs and get the same answer.
- This implies **rule versions are immutable**. New version = new row.

Wire Drools' `AgendaEventListener` to your audit + metrics sink — don't sprinkle logging into `then` blocks:

```java
session.addEventListener(new DefaultAgendaEventListener() {
    @Override public void afterMatchFired(AfterMatchFiredEvent e) {
        var name = e.getMatch().getRule().getName();
        meter.counter("rule_fires_total",
            "rule_set", ruleSet.id(), "rule_name", name).increment();
        audit.record(name, ruleSet.version(), e.getMatch().getObjects());
    }
});
```

## 7. Testing rules

- Unit test every rule with given/when/then; no Spring context needed for plain-Java or Easy Rules.
- Drools: `KieSession` per test method, fact loading + firing + assertions.
- Decision tables: golden-file tests — full table + sample facts + expected outputs.
- Regression suite: every prior bug gets a test fixing the rule that caused it.
- Coverage standard same as application code (95% — see `java-code-quality`).

```java
@Test void premium_tier_discount_fires_above_500() {
    var session = kieContainer.newKieSession();
    var order = new Order(premiumCustomer(), Money.of(750));
    session.insert(order);
    int fired = session.fireAllRules(new RuleNameEqualsAgendaFilter("premium-tier-discount"));
    assertThat(fired).isEqualTo(1);
    assertThat(order.discount()).isEqualTo(Money.of(112.50));
    session.dispose();   // sessions are heavyweight — always dispose
}
```

## 8. Performance

- Drools RETE has memory cost proportional to fact set × rule count. Profile before scaling tenants.
- For 10k+ rules per tenant or 1M+ facts, consider OpenL Tablets or a custom engine. Drools at that scale needs careful tuning.
- Easy Rules / strategy pattern: O(N rules × match check); fine to thousands.
- **Reuse `KieBase`, not `KieSession`.** Compile once per rule-set version, spawn a new stateless session per request. A pool of stateful sessions is almost always premature.
- Time every rule firing through Micrometer with a high-cardinality guard (rule name only, not tenant id).

## 9. Anti-patterns — refuse

- Adopting Drools "because it's industry standard" without the matrix in §1 saying yes.
- Storing rules in code while claiming "business users can change them".
- Rule changes deployed without versioning.
- Rule fires without audit (one of the main reasons to use an engine is the audit trail).
- Drools `.drl` files in `src/main/resources` with logic that's actually code, not rules — refactor to plain Java.
- Mutating facts inside a Drools `then` block in ways that re-trigger the same rule (infinite loop).
- Sharing one `KieContainer` across tenants without isolation.
- Caching rule results across tenants (rules are tenant-scoped per §5).

## 10. Pre-merge checklist
- [ ] Decision matrix consulted; "use engine" path justified
- [ ] Per-tenant rule resolution covered (or explicitly N/A)
- [ ] Every rule fire emits metric + audit log
- [ ] Rule versions immutable; new version = new row
- [ ] Test coverage ≥ 95% on rules (per `java-code-quality`)
- [ ] Hot-reload path tested in integration tests

## 11. Reference

- Drools docs: https://www.drools.org/
- Easy Rules: https://github.com/j-easy/easy-rules
- `.claude/skills/lib/jabrena/121-java-object-oriented-design/references/121-java-object-oriented-design.md` — strategy pattern depth
- `.claude/skills/java-data-governance` — for audit log integration
