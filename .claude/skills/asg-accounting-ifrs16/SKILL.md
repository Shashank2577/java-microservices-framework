---
name: asg-accounting-ifrs16
description: Use when implementing IFRS 16 lessee accounting, supporting an international tenant that requires IFRS rather than (or in addition to) US GAAP, or building dual-reporting where the same lease must produce both ASC 842 and IFRS 16 outputs. Covers the single lessee model, exemptions, reassessment triggers, dual-reporting strategy, and what is reused vs forked from `asg-accounting-asc842`.
---

# IFRS 16 Lessee Accounting

IFRS 16 is the international counterpart to ASC 842. The mechanics of measurement (ROU + lease liability, PV of payments, discount rate, modifications) are nearly identical — the **important** differences are classification, exemptions, and reassessment. This skill is a delta against `asg-accounting-asc842`; read that one first.

> **Read first:** `asg-accounting-asc842`, `asg-hub-and-spoke-financial`, `asg-decimal-precision`, ADR-006 (Drools), ADR-007 (hub-and-spoke).

## 1. The Single Lessee Model

ASC 842 is a **dual** model: operating leases produce single-cost straight-line expense; finance leases produce front-loaded interest + amortization. IFRS 16 is a **single** model: **all** leases for a lessee are accounted for like ASC 842 finance leases — interest expense + ROU amortization, every period, every lease.

**Implication:** there is no "operating vs finance" classification under IFRS 16 for lessees. The five-criteria test from `classification.drl` does not run for IFRS 16 tenants. Skip it.

```drl
// rules/ifrs16/classification.drl
// IFRS 16 single lessee model — no classification required.
// All leases are measured as finance-like (interest + amortization).
// This file exists only for parity with ASC 842 wiring and asserts
// the absence of classification facts.

package com.asg.ifrs16.rules.classification;
// (intentionally empty — see ClassificationPolicy.IFRS16.NO_OP)
```

The `ClassificationPolicy` port returns a fixed verdict `IFRS16_FINANCE_LIKE` for all leases under IFRS 16.

## 2. Recognition Exemptions

IFRS 16 allows two on-by-default elective exemptions; ASC 842 has only the short-term option. The service must honor both at tenant + lease scope.

| Exemption | Criterion | Treatment |
| --------- | --------- | --------- |
| Short-term | Lease term ≤ 12 months at commencement, **no purchase option** | Recognize payments as expense on straight-line basis. **No ROU, no liability** posted. |
| Low-value | Underlying asset value when new ≤ tenant-configured threshold (typical USD 5,000) | Same: straight-line expense, no on-balance-sheet. Elective per lease class. |

ASC 842 has the short-term exemption but no low-value exemption. **Do not** carry the low-value exemption into ASC 842 logic — the gating is per accounting framework. The `LeaseAccountingFramework` enum (`ASC_842 | IFRS_16`) selects the rule set.

```java
public enum LeaseAccountingFramework {
    ASC_842,
    IFRS_16;
}

// domain/service/ExemptionPolicy.java
public final class ExemptionPolicy {
    public boolean qualifiesForExemption(Lease lease, LeaseAccountingFramework framework,
                                         TenantThresholds thresholds) {
        if (lease.term().monthsAtCommencement() <= 12 && !lease.hasPurchaseOption()) return true;
        if (framework == LeaseAccountingFramework.IFRS_16
                && lease.underlyingAssetNewValue().compareTo(thresholds.lowValueLimit()) <= 0) return true;
        return false;
    }
}
```

When exempt, the service emits **no `IFRS16_INITIAL`** candidates and produces only periodic straight-line expense candidates with `transaction_type = IFRS16_EXEMPT_EXPENSE`.

## 3. Reassessment Triggers

IFRS 16 reassessment is broader than ASC 842 modification. Triggers that require remeasurement at the **revised discount rate**:

| Trigger | IFRS 16 treatment | ASC 842 equivalent |
| ------- | ----------------- | ------------------ |
| Change in lease term (option reassessment) | Remeasure at revised IBR | `option_reassessment` |
| Change in assessment of purchase option | Remeasure at revised IBR | covered by modification |
| Change in residual value guarantee | Remeasure at **original** IBR | `residual_value` |
| Change in index/rate (CPI reset) | Remeasure at **original** IBR | `index_or_rate` |
| Substantive contract modification | Scope test (same as ASC 842) | `modification` |
| Termination | Derecognize, gain/loss to P&L | `termination` |

The discount-rate choice (revised vs original) is the most-frequent IFRS 16 bug. Encode it explicitly:

```java
public enum Ifrs16RemeasurementTrigger {
    LEASE_TERM_CHANGE     (DiscountRateMode.REVISED),
    PURCHASE_OPTION_CHANGE(DiscountRateMode.REVISED),
    RESIDUAL_VALUE_CHANGE (DiscountRateMode.ORIGINAL),
    INDEX_OR_RATE_CHANGE  (DiscountRateMode.ORIGINAL),
    MODIFICATION          (DiscountRateMode.REVISED),
    TERMINATION           (DiscountRateMode.NOT_APPLICABLE);

    private final DiscountRateMode rateMode;
    Ifrs16RemeasurementTrigger(DiscountRateMode m) { this.rateMode = m; }
    public DiscountRateMode rateMode() { return rateMode; }
}
```

## 4. Dual Reporting

Some tenants — typically retail clients with international parents — need **both** ASC 842 and IFRS 16 outputs for the same underlying lease. The architecture supports this without duplicating contract data:

```
Contract (single source of truth)
   ├── ASC842 measurement (driven by ASC842AccountingService)
   └── IFRS16 measurement (driven by IFRS16AccountingService — this skill)
```

### Dual-reporting rules

- **One Contract aggregate**, owned by `ContractManagementService`. Never fork the contract.
- **Two parallel measurement streams.** Each writes its own ROU/liability schedules to its own tables (`asc842_rou_schedule`, `ifrs16_rou_schedule`), keyed by `contract_id`.
- **Hub submissions are tagged with framework.** `source_module = "ASC842"` or `"IFRS16"` so the AccountingEngine can route to the correct GL set (most tenants run separate ledgers per framework).
- **A modification event is broadcast once.** Both services subscribe to the same `contract.modification` event. Each interprets it under its own rules. This guarantees the two streams stay consistent with the underlying contract.
- **Period close runs independently** per framework. A late-arriving IFRS 16 entry must not block ASC 842 close.

### Configuration

Tenant configuration (Config Service) declares which framework(s) apply:

```yaml
tenant: warby-parker-uk
frameworks:
  - IFRS_16        # primary — UK statutory
  - ASC_842        # secondary — US parent reporting
ifrs16:
  low_value_threshold: { amount: "5000.0000", currency: "GBP" }
asc842:
  classification_thresholds:
    economic_life_pct: "0.75"
    fair_value_pct:    "0.90"
```

## 5. Shared Infrastructure — What Reuses, What Forks

| Concern | ASC 842 | IFRS 16 | Reuse strategy |
| ------- | ------- | ------- | -------------- |
| Drools KieContainer wiring | yes | yes | **Shared** infrastructure module |
| `Money` / `BigDecimal` rules | yes | yes | **Shared** (see `asg-decimal-precision`) |
| Present-value math | yes | yes | **Shared** `PresentValue` domain service |
| Classification rules (5-criteria) | yes | no | **ASC 842 only** |
| Exemptions | short-term only | short-term + low-value | **Forked** policy class |
| Remeasurement trigger enum | 5 triggers | 6 triggers (broader) | **Forked** — different rate-mode mapping |
| Period-close use-case | `CloseAsc842Period` | `CloseIfrs16Period` | **Forked** — different candidate types |
| AccountingEngine gateway | shared port | shared port | **Shared** — only `source_module` differs |
| Disclosure rule set | ASC 842 disclosures | IFRS 16 disclosures | **Forked** DRL |
| Golden-master fixtures | per ASC 842 lease | per IFRS 16 lease | **Forked** fixture trees |

**Rule of thumb:** measurement math is shared; classification and disclosure are forked. Resist the urge to "unify" the trigger enums — the rate-mode mapping is a load-bearing distinction.

## 6. Currency Considerations

IFRS 16 is used heavily by non-USD tenants. The service must:

- Accept any ISO 4217 currency on the lease (USD, GBP, EUR, CAD, AUD common in our pipeline).
- Discount in the **lease currency** — never convert payment streams to a reporting currency before PV.
- Emit candidates in the lease currency; the AccountingEngine's currency-translation step (separate concern) handles reporting-currency translation for consolidated reports.
- Per `asg-decimal-precision`, scale is uniform NUMERIC(19,4) regardless of currency. Per-currency presentation rounding (e.g., JPY 0 decimals) is a display concern, not a storage concern.
- **Never** assume USD defaults. Reject a lease with no currency at validation; do not silently default.

> **Open question:** Do we support mid-life currency redenomination (e.g., a CHF-pegged lease moving to EUR)? Not in MVP — flag as a future modification trigger.

## 7. Tenancy and Multi-Framework Resolution

The `TenantContext` is extended with the **active framework** for the current request scope. Most reporting requests target one framework; bulk operations may iterate both.

```java
public final class TenantAccountingContext {
    private static final ScopedValue<LeaseAccountingFramework> CURRENT = ScopedValue.newInstance();
    public static <R> R callWith(LeaseAccountingFramework f, Callable<R> c) throws Exception {
        return ScopedValue.where(CURRENT, f).call(c::call);
    }
    public static LeaseAccountingFramework current() {
        if (!CURRENT.isBound()) throw new IllegalStateException("No accounting framework in context");
        return CURRENT.get();
    }
}
```

The Drools KieBase cache is keyed `(tenant_id, framework, ruleset_version)` — three dimensions, not two.

## 8. Testing

- All ASC 842 testing patterns apply (Testcontainers, golden masters, property tests on PV).
- **Cross-framework parity tests:** for a lease where both frameworks apply, assert that on Day 1 the **lease liability** is identical (both frameworks use PV of payments at the same IBR for that day). The ROU and subsequent expense profiles differ — that's expected.
- **Exemption tests:** a 12-month lease must produce **zero** ROU/liability candidates under IFRS 16; a 13-month otherwise-identical lease must produce them. Boundary test the threshold day.
- **Trigger × rate-mode matrix:** one test per trigger asserting that the discount rate fed into `PresentValue` is the original vs revised as table §3 dictates.

## 9. Smells

- An IFRS 16 path calling the ASC 842 five-criteria classification — sign of accidental code share.
- A modification handler that doesn't branch on `framework` for the rate-mode decision.
- Currency conversion happening inside the measurement step — that's a downstream reporting concern.
- A "low-value" lease producing an ROU/liability — exemption not honored.
- A tenant configured for both frameworks but only one period-close job scheduled — silent under-reporting.

## Reference

- `.claude/skills/asg-accounting-asc842/SKILL.md` — measurement primitives are shared.
- `.claude/skills/lib/jabrena/144-java-data-oriented-programming/references/144-java-data-oriented-programming.md` — enums + records for the framework/trigger taxonomy.
- ADR-006 (Drools), ADR-007 (hub-and-spoke), GAP-010 (decimal precision).
