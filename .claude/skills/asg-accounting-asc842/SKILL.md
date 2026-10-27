---
name: asg-accounting-asc842
description: Use whenever working on the ASC 842 Accounting Service — lease classification, ROU asset / lease liability measurement, period close, journal entry generation, lease modifications, terminations, auto-reverse, or any code that emits ASC842_* candidates to the AccountingEngine. Covers the Drools rule layout, BigDecimal precision contract, the 5-trigger remeasurement matrix, and the idempotency model for period close.
---

# ASC 842 Lessee Accounting — Java + Drools

This skill encodes the ASG Edge Plus ASC 842 service. It is a **spoke** of the AccountingEngine (per `asg-hub-and-spoke-financial`), backed by **Drools 8.x KIE** for rule execution (per ADR-006). It never posts to the GL directly — it produces typed candidate transactions and submits them to the AccountingEngine hub.

> **Read first:** ADR-006 (Drools), ADR-007 (hub-and-spoke), `asg-decimal-precision`, `asg-contract-domain`, GAP-004 (modification matrix), GAP-006 (commencement changes).

## 1. Where This Service Lives

```
asc842-service/
├── domain/
│   ├── model/                       # LeaseClassification, ROUAsset, LeaseLiability, AmortizationLine
│   ├── event/                       # ASC842PeriodClosed, ASC842LeaseRemeasured
│   └── service/                     # ClassificationPolicy (calls Drools), MeasurementService
├── application/
│   ├── usecase/                     # ClassifyLease, MeasureAtCommencement, CloseAsc842Period, RemeasureOnEvent
│   └── port/out/                    # AccountingEngineGateway, ContractGateway
├── infrastructure/
│   ├── drools/                      # KieContainer config, AgendaEventListener for audit
│   ├── rules/                       # *.drl files (classification, lifecycle, modification)
│   └── persistence/jpa/             # schedules, classification audit
└── config/
```

The domain layer is pure. Drools lives in `infrastructure/drools` — DRL is an adapter, not a domain concept. The domain depends on `ClassificationPolicy` (a port); the adapter executes DRL and returns a typed verdict.

## 2. Classification — The Five-Criteria Test

ASC 842-10-25-2 requires lessee classification at lease commencement: **finance** if any of the five criteria are met, otherwise **operating**. The test runs once at commencement and again on any **modification** that triggers reassessment.

```drl
// rules/classification.drl
package com.asg.asc842.rules.classification;

import com.asg.asc842.domain.model.classification.ClassificationInput;
import com.asg.asc842.domain.model.classification.ClassificationVerdict;
import java.math.BigDecimal;

global ClassificationVerdict verdict;

rule "C1: Transfer of ownership"
    salience 100
    when
        $i: ClassificationInput(transfersOwnership == true)
    then
        verdict.markFinance("C1_OWNERSHIP_TRANSFER", $i);
end

rule "C2: Bargain purchase option reasonably certain"
    salience 90
    when
        $i: ClassificationInput(purchaseOptionReasonablyCertain == true)
    then
        verdict.markFinance("C2_BARGAIN_PURCHASE", $i);
end

rule "C3: Lease term >= 75% of remaining economic life"
    salience 80
    when
        $i: ClassificationInput(
            economicLifeThresholdPct != null,
            eval(leaseTermMonths.divide(remainingEconomicLifeMonths, 6, java.math.RoundingMode.HALF_EVEN)
                 .compareTo(economicLifeThresholdPct) >= 0))
    then
        verdict.markFinance("C3_MAJOR_PART_OF_LIFE", $i);
end

rule "C4: PV of payments >= 90% of fair value"
    salience 70
    when
        $i: ClassificationInput(
            fairValueThresholdPct != null,
            eval(presentValueOfPayments.divide(fairValue, 6, java.math.RoundingMode.HALF_EVEN)
                 .compareTo(fairValueThresholdPct) >= 0))
    then
        verdict.markFinance("C4_SUBSTANTIALLY_ALL_FV", $i);
end

rule "C5: Specialized asset"
    salience 60
    when
        $i: ClassificationInput(specializedAsset == true)
    then
        verdict.markFinance("C5_SPECIALIZED_ASSET", $i);
end

rule "Default to OPERATING when no finance criterion fired"
    salience -100
    when
        not (ClassificationVerdict(classification == ClassificationVerdict.Type.FINANCE))
    then
        verdict.markOperating();
end
```

### Rule layout rules
- **One DRL per accounting domain** — `classification.drl`, `lifecycle.drl`, `modification.drl`, `disclosure.drl`. Never one mega-file.
- **Thresholds via globals or input fields**, never hardcoded. The 75% / 90% values are *per-tenant configurable* in the spec — inject via `ClassificationInput` from Config Service. Tenants may set their own materiality.
- **No domain logic in `then` blocks** — only call back into a verdict object. The verdict is plain Java; rules just append findings.
- **AgendaEventListener required** — every fired rule (rule name, matched fact ids, timestamp) must be captured for the audit trail. This is non-negotiable for SOX/SOC.

### Drools wiring
- `KieContainer` is built once at startup and cached. **Stateless KieSession per request** — thread-safe, no leakage between leases.
- DRL files are CI-validated (`KieScanner` dry build) — a parse failure must fail the pipeline, never reach prod.
- Per ADR-006, KieBase is cached in Caffeine; version it by `(tenant_id, ruleset_version)` so an in-flight period close cannot be poisoned by a mid-batch rule deploy.

## 3. Initial Measurement at Commencement

At the **commencement date** (the date the lessee can use the asset — not the contract sign date), compute:

| Quantity | Formula |
| -------- | ------- |
| Lease liability | `PV(remaining payments, discount_rate=IBR)` |
| ROU asset | `lease_liability + prepaid_lease_payments + initial_direct_costs − lease_incentives` |
| Discount rate | Rate implicit in lease if known; otherwise tenant's **incremental borrowing rate (IBR)** for matching term and currency |

```java
// domain/service/MeasurementService.java — pure Java, no Spring
public final class MeasurementService {
    public InitialMeasurement measureAtCommencement(LeaseTerm term, PaymentSchedule schedule,
                                                    DiscountRate rate, Money initialDirectCosts,
                                                    Money leaseIncentives, Money prepaidPayments) {
        Money liability = PresentValue.of(schedule.remainingPayments(term.commencement()), rate);
        Money rou = liability
            .add(prepaidPayments)
            .add(initialDirectCosts)
            .subtract(leaseIncentives);
        return new InitialMeasurement(liability, rou, rate, term.commencement());
    }
}
```

All amounts are `Money` (per `asg-decimal-precision`). Never `double`. **NUMERIC(19,4)** at the DB boundary. Intermediate PV math uses scale 10, rounded to scale 4 at boundary with `HALF_EVEN`.

## 4. Subsequent Measurement — Per Period

Each period (typically monthly), the service runs three computations per active lease:

1. **Interest accrual** on the lease liability: `liability_opening × periodic_rate`
2. **Liability reduction** = `payment − interest`
3. **ROU amortization**:
   - **Finance lease:** straight-line over lease term (or useful life if ownership transfers)
   - **Operating lease:** plug — amortize ROU so total period expense = straight-line average rent (single-cost model)

The period-close use-case generates a deterministic set of candidate journal entries and submits them to the AccountingEngine.

```java
@Service
@RequiredArgsConstructor
class CloseAsc842Period implements CloseAsc842PeriodUseCase {
    private final LeaseRepository leases;
    private final AccountingEngineGateway hub;          // port — submits candidates
    private final ClassificationPolicy classifier;       // Drools-backed
    private final Clock clock;

    @Transactional
    public PeriodCloseResult close(TenantId tenant, FiscalPeriod period) {
        var idemKey = IdempotencyKey.of("ASC842_PERIOD", tenant, period);
        // Re-running the same period is a no-op at the hub. See §7.
        var leases = this.leases.activeIn(tenant, period);
        var batch = leases.stream()
            .map(l -> generateCandidates(l, period))
            .toList();
        return hub.submit(idemKey, batch);
    }
}
```

### Journal entry shapes

| Event | Debit | Credit |
| ----- | ----- | ------ |
| Operating — period rent (single cost) | Lease Expense | Lease Liability + ROU amortization |
| Finance — interest accrual | Interest Expense | Lease Liability |
| Finance — ROU amortization | Amortization Expense | Accumulated Amortization (ROU) |
| Payment posted | Lease Liability | Cash / AP |
| Modification — liability increase | ROU | Lease Liability |
| Modification — liability decrease | Lease Liability | ROU (then Gain/Loss if ROU floors at 0) |
| Termination | Lease Liability + Accumulated Amortization | ROU + Gain/Loss |

Concrete GL account codes come from the tenant's chart of accounts (Config Service) — the service emits *role-tagged* lines (`role: LEASE_LIABILITY`), and the hub resolves to actual GL codes. This keeps the service tenant-agnostic.

## 5. Modification Events — The Five-Trigger Matrix

> **Resolves GAP-004** (was previously thin in the spec).

A submission to AccountingEngine of `transaction_type = ASC842_MODIFICATION` carries a **trigger** that drives a distinct algorithm. The service supports five:

| Trigger | Treatment | Inputs (additional to standard) |
| ------- | --------- | ------------------------------- |
| `modification` | Scope test → if scope expanded + standalone price, treat as **separate contract**; else **remeasure** | new payment schedule, new term, optional new IBR |
| `option_reassessment` | Triggered when a renewal/termination option's "reasonably certain" status changes. Remeasure with new term using **revised discount rate** | new term, new IBR |
| `residual_value` | Change to lessee-guaranteed residual value. Adjust liability for **change in probable payment** at original discount rate | new probable RV amount |
| `index_or_rate` | CPI reset or similar index-linked payment change. Remeasure at **original discount rate** unless rate is also reset | new payment schedule |
| `termination` | Derecognize remaining ROU and liability. Recognize **gain/loss** through P&L. Auto-reverse open accruals (§6). | termination_date, termination_fee, gain_loss_amount |

### Scope test (modification trigger)

```
IF (modification adds a standalone right-of-use)
   AND (price increase ≈ standalone price for that right) THEN
     → Separate contract: original lease unchanged; new lease accounted for fresh
ELSE
     → Remeasure: keep ROU/liability continuity; apply delta at modification date
```

The scope test must be **explicit** in code and **logged** with the inputs that drove the verdict. Per ADR-006 the test belongs in `rules/modification.drl` so a CPA can audit the predicate without reading Java.

### Submission contract to AccountingEngine

```jsonc
{
  "source_module": "ASC842",
  "transaction_type": "ASC842_MODIFICATION",
  "trigger": "termination",                       // one of the 5
  "idempotency_key": "asc842:mod:<contract_id>:<event_id>",
  "tenant_id": "...",
  "contract_id": "...",
  "effective_date": "2026-04-01",
  // conditional-required by trigger:
  "termination_date": "2026-04-01",
  "termination_fee": "12500.0000",                // NUMERIC(19,4)
  "gain_loss_amount": "-3400.0000",
  "lines": [ /* role-tagged debits/credits */ ]
}
```

Conditional-required fields are enforced by Drools rules in `modification.drl` so the submission fails-fast before the candidate reaches the hub.

## 6. Termination and Auto-Reverse

Termination on `ASC842_MODIFICATION` (per the recent KB commit) requires:

1. Derecognize the lease liability at carrying value as of `termination_date`.
2. Derecognize the ROU at net carrying value (cost − accumulated amortization).
3. Recognize `gain_loss_amount = liability_carrying − rou_carrying − termination_fee` (with the sign convention documented in `disclosure.drl`).
4. **Auto-reverse** all open accruals for the terminated lease that have a `service_period_end > termination_date`:
   - For each unposted accrual candidate, emit a reversing candidate with the same idempotency root and `reversal_of` reference.
   - Reversed candidates carry the original journal-entry shape with debits/credits swapped.
   - The reversal batch is submitted in the **same hub call** as the termination so the hub's all-or-nothing semantics apply.

Auto-reverse is **idempotent**: re-submission of the same `termination_date` produces no new entries. The hub's `(source_module, idempotency_key)` constraint guarantees this even if a retry happens after a crash.

## 7. Idempotency, Period Close, and Replay

- Every candidate carries an `idempotency_key` derived deterministically from `(tenant, contract, period, candidate_type)`.
- Re-running `CloseAsc842Period` for the same `(tenant, period)` is a no-op at the hub — duplicate keys are silently absorbed. This is the safety net for a crashed nightly batch.
- **Replay** of a historical period uses the snapshotted `ruleset_version` from the original close. Drools KieBase versions are loadable side-by-side; **never** replay against the current KieBase, or you reproduce yesterday's numbers under today's rules.
- A period is **closed** when (a) all active leases for the tenant have submitted candidates, (b) the hub has accepted them, (c) the close record is written with `period_close_id`. A reopened period requires a separate `PeriodReopen` use-case with audit logging and explicit operator action — never an implicit re-run.

## 8. Precision and Money

All amounts use the `Money` value object from `asg-decimal-precision`. Highlights specific to ASC 842:

- PV math runs at scale 10, rounds to scale 4 at the storage boundary, `RoundingMode.HALF_EVEN`.
- The **discount rate** is stored as a decimal at scale 8 (e.g., `0.04250000`). Periodic rate = `(1 + annual)^(1/12) − 1` exact at scale 12, rounded to 8.
- **Never** compare `BigDecimal` with `.equals()` — that distinguishes `1.00` from `1.0`. Use `.compareTo() == 0` (or `Money.equals` which normalizes scale).
- Currency is part of `Money`. A lease cannot mix currencies; the service rejects payment schedules with mixed currency at validation.

## 9. Tenancy

This is a multi-tenant service. Apply `java-multi-tenancy` rules in full:
- Every use-case enters under a resolved `TenantId`.
- Schema-per-tenant — Hibernate resolves the schema from `TenantContext`.
- Drools KieBase cache is keyed by `(tenant_id, ruleset_version)` so tenant A's threshold overrides never leak to tenant B.

## 10. Testing

Per `java-testing`:

- **Unit tests** for the domain layer — no Spring, no Drools. `MeasurementService`, `PresentValue`, `Money` math.
- **Rules tests (DroolsTest pattern)** — fire each DRL with hand-crafted `ClassificationInput` / `ModificationInput` facts and assert the verdict + which rule fired (via `AgendaEventListener` capture). One test per rule, one test for default fall-through, one for thresholds at the boundary.
- **Use-case tests with Testcontainers Postgres** + an in-process AccountingEngine fake — verify the candidate submission contract.
- **Golden-master fixtures** — a directory of `(input.json, expected_candidates.json)` pairs derived from the legacy system's known-correct outputs for at least: one operating lease, one finance lease (each of C1–C5), one modification of each trigger type, one termination with auto-reverse, one CPI reset mid-term. These fixtures **must not change** without CPA sign-off; a diff is a regression.
- **Property tests** — for the PV math, assert algebraic identities (`PV(x) + PV(y) == PV(x+y)` within scale tolerance) using jqwik.

## 11. Smells

- A `double` anywhere in a financial path. There is no acceptable reason.
- DRL rules with side-effect `then` blocks doing real math. Math lives in Java; rules only **classify**.
- A use-case posting directly to a GL table. It must go through `AccountingEngineGateway`.
- A modification submission without an explicit `trigger`. Reject at the boundary.
- A period-close that reads the *current* KieBase to replay a 2024 period. Use the snapshotted version.
- An auto-reverse that runs *after* the termination has been posted (race window). Submit termination + reversals atomically.

## Reference

- `.claude/skills/lib/jabrena/144-java-data-oriented-programming/references/144-java-data-oriented-programming.md` — records + sealed types for verdicts and triggers.
- `.claude/skills/lib/jabrena/301-frameworks-spring-boot-core/references/301-frameworks-spring-boot-core.md` — bean wiring for KieContainer.
- ADR-006 (Drools), ADR-007 (hub-and-spoke), GAP-004 (modification matrix), GAP-006 (commencement changes), GAP-010 (decimal precision).
