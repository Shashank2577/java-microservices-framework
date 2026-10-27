---
name: asg-contract-domain
description: Use whenever working on the ContractManagementService — the Contract aggregate, lease terms, payment schedules, option periods, modifications, lifecycle states, contract events, or any consumer that reads contract data. This is the gate for all Phase 4 financial work — every financial spoke depends on Contract. Covers the DDD model, lifecycle FSM, modification event protocol, schema hints, audit emission, and the read/command split.
---

# Contract Domain — The Spine of the Platform

The `Contract` aggregate anchors every financial calculation in the platform. Every ASC 842 measurement, every payment schedule, every accrual, every recovery references a contract. **Phase 4 cannot start until ContractManagementService is in place** — this is the critical-blocker called out in the KB.

> **Read first:** `asg-accounting-asc842`, `asg-hub-and-spoke-financial`, `asg-decimal-precision`, `java-multi-tenancy`, `java-patterns-database`, `java-migrations`, ADR-011 (immutable audit), GAP-006 (commencement changes), GAP-007 (RLS vs DB-per-tenant).

## 1. The Domain Model

```
Contract (aggregate root)
├── ContractId
├── TenantId
├── ContractStatus            (FSM — §3)
├── LocationRef               (FK by ID — Location is another aggregate)
├── VendorRef                 (FK by ID — Vendor lives in MastersManagementEngine)
├── BaseTerms                 (currency, lease type, signing date, etc.)
├── LeaseTerm                 (commencement, expiration, term months — see §2)
├── PaymentSchedule           (lines — see §2)
├── List<OptionPeriod>        (renewal + termination options — see §2)
├── List<ModificationEvent>   (immutable history — see §4)
└── Version                   (optimistic locking)
```

### Aggregate boundary rules (per `java-architecture` §7.1)
- The **only** entry point is `Contract`. `PaymentScheduleRepository.save(line)` does not exist.
- Children (`LeaseTerm`, `PaymentSchedule`, `OptionPeriod`, `ModificationEvent`) are loaded and saved as part of the root.
- References to `Location`, `Vendor`, and `Tenant` are **by ID**, never by object.
- One contract mutated per transaction. Cross-aggregate consistency is eventual via events.

## 2. Value Objects and Entity Children

```java
public record LeaseTerm(
    LocalDate commencement,           // anchors ASC 842 / IFRS 16
    LocalDate expiration,
    int termMonths,                   // derived but stored for audit fidelity
    boolean hasPurchaseOption,
    Money endOfTermResidualGuarantee  // optional; Money.zero(currency) if none
) {
    public LeaseTerm {
        Objects.requireNonNull(commencement);
        Objects.requireNonNull(expiration);
        if (!expiration.isAfter(commencement))
            throw new IllegalArgumentException("expiration must be after commencement");
        // termMonths must match the date span (with rounding policy)
        var computed = (int) ChronoUnit.MONTHS.between(commencement, expiration.plusDays(1));
        if (Math.abs(computed - termMonths) > 1)
            throw new IllegalArgumentException("termMonths inconsistent with dates");
    }
}

public record PaymentScheduleLine(
    LocalDate fromDate,
    LocalDate toDate,                 // exclusive — half-open intervals
    Money amount,
    Cadence cadence,                  // MONTHLY | QUARTERLY | ANNUAL | ONE_TIME
    PaymentCategory category          // BASE_RENT | CAM | TAX | INSURANCE | PCT_RENT | OTHER
) { }

public record OptionPeriod(
    OptionType type,                  // RENEWAL | TERMINATION | PURCHASE
    LocalDate exerciseWindowStart,
    LocalDate exerciseWindowEnd,
    int extensionMonths,              // for RENEWAL
    Money exercisePrice,              // for PURCHASE / TERMINATION fee
    ReasonablyCertain reasonablyCertain   // YES | NO | UNDETERMINED — drives ASC 842 §3 trigger
) { }
```

- All amounts are `Money` (per `asg-decimal-precision`).
- All dates are `LocalDate` — leases run in business-day terms, not instants.
- Schedule lines use **half-open intervals** `[from, to)` — eliminates the off-by-one at month boundaries.
- `ReasonablyCertain` is part of the contract, not the financial service. Changing this value is a contract modification (§4) that triggers ASC 842 `option_reassessment`.

## 3. Lifecycle FSM

```
   ┌─────┐  submit   ┌───────────┐  approve   ┌────────┐
   │Draft│──────────▶│Pending    │───────────▶│ Active │
   └─────┘           │Approval   │            └────┬───┘
       ▲             └───────────┘                 │
       │ revise                                    │ modify
       │             ┌───────────┐                 ▼
       │             │ Returned  │           ┌──────────┐
       └─────────────┤(submitter)│           │ Modified │ ←─── re-emits to spokes
                     └───────────┘           └────┬─────┘
                                                  │
                                                  │ terminate / expire
                                                  ▼
                                          ┌──────────────┐
                                          │  Terminated  │
                                          │  / Expired   │
                                          └──────────────┘
```

### Transition rules
- Only `Draft → Pending Approval` and back via `Returned` are user-driven free transitions.
- `Pending Approval → Active` only via the approval workflow (see `asg-hub-and-spoke-financial` §3 — the same protocol AE uses).
- `Active → Modified` produces a `ModificationEvent`; the contract stays *Active* in the public state but `last_modified_at` advances and a new modification record is appended. The "Modified" state is shorthand for "Active with modification history" — it is not a distinct FSM state in storage.
- `Active → Terminated` requires a termination event with `termination_date` ≤ today + grace; future-dated terminations go to `Pending Termination` (sub-state) and auto-promote on the date.
- `Active → Expired` is automatic on `expiration` date if no renewal option was exercised.
- **No transitions out of `Terminated` or `Expired`.** Reactivation = a new contract.
- The current state and timestamps of all prior states are persisted in `contract_history` (per ADR-011 — append-only).

### Code

```java
public enum ContractStatus {
    DRAFT, PENDING_APPROVAL, RETURNED, ACTIVE,
    PENDING_TERMINATION, TERMINATED, EXPIRED;

    public boolean canModify()     { return this == ACTIVE; }
    public boolean isTerminal()    { return this == TERMINATED || this == EXPIRED; }
}

public final class Contract {
    // ...
    public ContractModified modify(ModificationRequest req, Instant now) {
        if (!status.canModify())
            throw new IllegalStateException("cannot modify in state " + status);
        var event = ModificationEvent.from(req, now);
        modifications.add(event);
        applyTo(this, event);                    // mutates terms/schedule/options
        version++;
        return new ContractModified(id, tenant, event, now);   // emitted event
    }

    public ContractTerminated terminate(LocalDate when, Money fee, Instant now) {
        if (status.isTerminal()) throw new IllegalStateException("already " + status);
        this.status = (when.isAfter(LocalDate.now())) ? PENDING_TERMINATION : TERMINATED;
        return new ContractTerminated(id, tenant, when, fee, now);
    }
}
```

## 4. Modification Event Protocol

Every change to lease term, payment schedule, or option status after `Active` is a `ModificationEvent`. The event is the unit of communication with the financial spokes — they react to it, never to direct CRUD on contract fields.

```java
public record ModificationEvent(
    ModificationEventId id,
    ContractId contractId,
    TenantId tenantId,
    ModificationKind kind,            // TERM_CHANGE | PAYMENT_CHANGE | OPTION_REASSESSMENT
                                      // | RESIDUAL_VALUE | INDEX_OR_RATE | TERMINATION
    LocalDate effectiveDate,
    String reason,                    // free text, audit
    UserId submittedBy,
    Instant submittedAt,
    Map<String, Object> delta         // structured before/after — see below
) implements DomainEvent { }
```

### `delta` shape (JSONB column)

Stores a structured `before` / `after` per modified field. This is the source of truth for replay and for the financial spokes' rule input.

```json
{
  "before": { "expiration": "2028-12-31", "termMonths": 60 },
  "after":  { "expiration": "2030-12-31", "termMonths": 84 },
  "fields": ["expiration", "termMonths"]
}
```

### Event emission

`ContractModified` (and `ContractTerminated`, `ContractActivated`, `ContractExpired`) are emitted via the **outbox pattern** in the same transaction as the contract save (per `java-messaging`). Topic: `contract.events.v1`. Partition key: `tenant_id`.

Consumers:
- `ASC842AccountingService` — drives the 5-trigger remeasurement matrix (see `asg-accounting-asc842` §5)
- `IFRS16AccountingService` — drives the IFRS 16 reassessment triggers
- `PaymentProcessingService` — adjusts upcoming scheduled payments
- `AccrualManagementService` — adjusts active accrual basis
- `PercentageRentAndSalesService` — adjusts breakpoints
- `RecoveriesAndYEAService` — adjusts recovery base
- `GlobalSearchComponent` — re-indexes
- `ReportsAndDashboardsEngine` — invalidates cached aggregates
- `AuditService` — persists the event for SOC compliance

Cross-link: the kinds map directly to the ASC 842 modification triggers. See `asg-accounting-asc842` §5 table.

## 5. Tenant Ownership and Master Data References

- Every Contract is owned by exactly one `TenantId`. There is no global Contract.
- `VendorRef` and picklist values come from `MastersManagementEngine` — references are by ID + version. The contract stores a snapshot of the resolved vendor name + key fields at the time of save, so a vendor rename does not retroactively change historical contracts. See GAP-022.
- Schema-per-tenant per `java-multi-tenancy`. Cross-tenant joins are forbidden.

## 6. Audit Emission

Every mutation of `Contract` emits to AuditService per ADR-011:

```java
@Transactional
public ContractId activate(ContractId id) {
    var contract = repo.byId(TenantContext.current(), id).orElseThrow();
    var event = contract.activate(clock.instant());
    repo.save(contract);
    audit.record(AuditRecord.builder()
        .tenantId(contract.tenantId())
        .actor(currentUser())
        .entity("Contract", contract.id().value())
        .action("ACTIVATE")
        .before(snapshotBefore)               // JSONB
        .after(snapshotAfter)                 // JSONB
        .timestamp(clock.instant())
        .build());
    events.publish(event);                    // outbox
    return contract.id();
}
```

- Audit is **insert-only** (ADR-011). No updates to audit records, ever.
- Before/after snapshots use the structured `delta` shape from §4 for modifications; full record snapshot for create/terminate.
- 7-year retention; tiered storage per ADR-011.

## 7. Read Model vs Command Model (CQRS-lite)

The aggregate is the **command** side. Read traffic — list views, search, dashboard rollups — does **not** go through `ContractRepository.byId`. Instead:

- Dedicated query services hit denormalized read tables / materialized views (`contract_list_view`, `contract_financial_summary_view`).
- Read tables are populated from `contract.events.v1` consumers — eventually consistent.
- The repository never exposes `findAll`, `findByX`, or `findByStatusAndCreatedAt` — see `java-architecture` §7.5. List endpoints go to the query service.

```java
public interface ContractRepository {
    Optional<Contract> byId(TenantId t, ContractId id);
    void save(Contract contract);
    // NOTHING ELSE
}

// separate interface, separate implementation
public interface ContractQueryService {
    Page<ContractListRow> list(TenantId t, ContractListCriteria c, PageRequest p);
    Optional<ContractFinancialSummary> summary(TenantId t, ContractId id);
}
```

## 8. Postgres Schema Hints

Per `java-patterns-database` conventions:

```sql
-- per-tenant schema t_<tenant>
CREATE TABLE contract (
    contract_id          UUID         PRIMARY KEY,
    tenant_id            UUID         NOT NULL,                -- redundant guardrail
    status               VARCHAR(32)  NOT NULL,
    location_id          UUID         NOT NULL,
    vendor_id            UUID         NOT NULL,
    vendor_name_snapshot VARCHAR(255) NOT NULL,                -- master data snapshot
    currency             CHAR(3)      NOT NULL,
    commencement         DATE         NOT NULL,
    expiration           DATE         NOT NULL,
    term_months          INT          NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,      -- optimistic locking
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_modified_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_status_known CHECK (status IN (
        'DRAFT','PENDING_APPROVAL','RETURNED','ACTIVE',
        'PENDING_TERMINATION','TERMINATED','EXPIRED'))
);
CREATE INDEX ix_contract_status     ON contract(status);
CREATE INDEX ix_contract_location   ON contract(location_id);
CREATE INDEX ix_contract_commence   ON contract(commencement);

CREATE TABLE payment_schedule_line (
    line_id              UUID         PRIMARY KEY,
    contract_id          UUID         NOT NULL REFERENCES contract(contract_id),
    from_date            DATE         NOT NULL,
    to_date              DATE         NOT NULL,                -- exclusive
    amount               NUMERIC(19,4) NOT NULL,
    currency             CHAR(3)      NOT NULL,
    cadence              VARCHAR(16)  NOT NULL,
    category             VARCHAR(32)  NOT NULL,
    CONSTRAINT chk_half_open CHECK (to_date > from_date)
);
CREATE INDEX ix_psl_contract        ON payment_schedule_line(contract_id);

CREATE TABLE modification_event (
    event_id             UUID         PRIMARY KEY,
    contract_id          UUID         NOT NULL REFERENCES contract(contract_id),
    kind                 VARCHAR(32)  NOT NULL,
    effective_date       DATE         NOT NULL,
    reason               TEXT,
    submitted_by         UUID         NOT NULL,
    submitted_at         TIMESTAMPTZ  NOT NULL,
    delta                JSONB        NOT NULL
);
CREATE INDEX ix_modevt_contract     ON modification_event(contract_id, effective_date);
```

- All financial columns: NUMERIC(19,4) (per `asg-decimal-precision`).
- Per ADR-004, this is the per-tenant schema. There is no `tenant_id` partitioning column-level guard (RLS is not used — DB-per-tenant via schema), but we keep the column anyway as a defense-in-depth check during development.
- See GAP-007 for the RLS vs DB-per-tenant tension; current direction is schema-per-tenant (per `java-multi-tenancy`).

## 9. Migrations

- Flyway per `java-migrations`. Per-schema migrations because we are schema-per-tenant.
- The schema for tenant `t_warby` is bootstrapped from a "template schema" at onboarding; migrations are then applied tenant-by-tenant in a controlled rollout.
- Schema changes to `contract` are blast-radius events — every financial spoke reads contract data. Coordinate via the events module (Avro evolution) and `java-migrations` review.

## 10. Versioning and Concurrent Edits

- Optimistic locking via `version` column. JPA `@Version`.
- A concurrent modification surfaces as `OptimisticLockException` → mapped to RFC 7807 `409 Conflict` per `java-api-errors`. The client refreshes and retries.
- Multi-step contract editing in the UI uses a **session draft** (saved to a `contract_draft` table), not a long-running optimistic lock. The draft is materialized into a contract record only at `Pending Approval`.

## 11. Phase 4 Gate

Phase 4 financial work (`PaymentProcessing`, `ASC842`, `IFRS16`, `Accrual`, `Allowances`, `PercentageRent`, `Recoveries`) **cannot start** until Contract is sufficient. "Sufficient" means:

- Aggregate model with `LeaseTerm` and `PaymentSchedule` complete.
- `ContractCreated`, `ContractModified`, `ContractTerminated`, `ContractActivated` events emitted on the outbox to `contract.events.v1`.
- `ContractRepository.byId` returns the full aggregate with payment lines.
- Audit emission live.
- Lifecycle FSM enforced (no test-only ACTIVE transitions).

If any of these is missing, financial development is blocked. Cite this gate in any PR that proposes parallel start.

## 12. Smells

- A use-case that calls `paymentScheduleLineRepository.save(line)` — child saved without root.
- A `Contract` field of type `Vendor` or `Location` — cross-aggregate object reference.
- A `findByStatus(...)` on `ContractRepository` — repository becoming a query bag; move to `ContractQueryService`.
- A modification applied as a direct UPDATE without a `ModificationEvent` — the financial spokes won't react.
- An audit record with `UPDATE` semantics — append-only. Append a new record; do not overwrite.
- A field rename in the contract schema landed in one service before the events module evolved — backward-compatibility break.

## Reference

- `.claude/skills/java-architecture/SKILL.md` §7 — DDD tactical patterns.
- `.claude/skills/java-patterns-database/SKILL.md` — schema conventions, indexing.
- `.claude/skills/java-migrations/SKILL.md` — Flyway per-schema rollout.
- `.claude/skills/java-multi-tenancy/SKILL.md` — schema-per-tenant.
- `.claude/skills/java-messaging/SKILL.md` — outbox + Avro.
- `.claude/skills/lib/jabrena/144-java-data-oriented-programming/references/144-java-data-oriented-programming.md` — record-based modeling.
- ADR-011 (audit), GAP-006 (commencement changes), GAP-007 (RLS vs DB-per-tenant), GAP-022 (master-data snapshot).
