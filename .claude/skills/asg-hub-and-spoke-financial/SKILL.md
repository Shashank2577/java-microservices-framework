---
name: asg-hub-and-spoke-financial
description: Use whenever working on the AccountingEngine, on any of the 6 financial spoke services (PaymentProcessing, ASC842, Accrual, Allowances, PercentageRent, Recoveries), or on the approval interface between AccountingEngine and WorkflowEngine. Covers the hub responsibilities, the spoke submission contract, the AE ↔ WorkflowEngine approval protocol, failure semantics, and the ArchUnit rules that enforce hub-and-spoke boundaries.
---

# AccountingEngine — Hub-and-Spoke Financial Posting

Per ADR-007, the **AccountingEngine (AE)** is the single posting authority for the platform. The 6 financial **spoke** services may not post to the GL directly; they submit typed candidates to the hub and the hub posts them.

> **Read first:** ADR-007 (hub-and-spoke), ADR-011 (append-only audit), `java-messaging`, `asg-decimal-precision`, GAP-001 (WorkflowEngine ownership), GAP-003 (AE ↔ WorkflowEngine approval protocol).

## 1. The Hub — What the AccountingEngine Owns

| Responsibility | Detail |
| -------------- | ------ |
| GL entry posting | The **only** path to `gl_journal_entry` writes. Spoke services have no GL write permission. |
| Double-entry validation | Sum of debits = sum of credits **per submission**, enforced at boundary, rejected pre-persist. |
| Immutable ledger | INSERT-ONLY. Append a `correcting` entry to revise; never UPDATE. SHA-256 hash chain per ADR-011. |
| Period management | Open/close periods per tenant. Submissions to a closed period are rejected. |
| Approval orchestration | Resolves `ApprovalConfiguration` (type × amount × tenant) and routes through WorkflowEngine (§3). |
| Audit emission | Every state transition emits to `AuditService`. The hub is one of the noisiest event sources. |
| Idempotency | `(source_module, idempotency_key)` uniqueness — duplicate submissions are absorbed silently. |
| SoD enforcement | Submitter ≠ approver. Enforced at submission and approval-routing time. |

The hub is a **platform service**, not a business service. It is in the same architectural tier as `AuditService` — every financial transaction must transit it.

## 2. Spoke Contract — What Spokes Submit

The 6 spokes:

| Spoke | What it contributes | Typical candidate types |
| ----- | ------------------- | ----------------------- |
| `PaymentProcessing` | Vendor payments, AP disbursements | `PAYMENT_POSTED`, `PAYMENT_VOIDED` |
| `ASC842` | Lease accounting per US GAAP | `ASC842_INITIAL`, `ASC842_PERIODIC`, `ASC842_MODIFICATION` |
| `Accrual` | Period-end accruals & reversals | `ACCRUAL_RECORD`, `ACCRUAL_REVERSE` |
| `Allowances` | Tenant improvement allowances, offsets | `ALLOWANCE_RECORD`, `ALLOWANCE_OFFSET` |
| `PercentageRent` | Sales-based rent, breakpoints | `PCT_RENT_ACCRUAL`, `PCT_RENT_TRUE_UP` |
| `Recoveries` | CAM, tax, insurance recoveries, YEA | `RECOVERY_BILLABLE`, `YEA_TRUE_UP` |

### Submission DTO

```java
public record CandidateSubmission(
    SourceModule sourceModule,                  // ASC842 | PAYMENT | ACCRUAL | ...
    TransactionType transactionType,            // e.g., ASC842_PERIODIC
    IdempotencyKey idempotencyKey,              // deterministic; spoke owns format
    TenantId tenantId,
    ContractId contractId,                      // optional for cross-contract submissions
    FiscalPeriod period,
    LocalDate effectiveDate,
    List<JournalLine> lines,                    // role-tagged debits/credits
    Map<String, String> metadata                // free-form, e.g., trigger=termination
) { }

public record JournalLine(
    GlAccountRole role,                         // LEASE_LIABILITY, ROU_ASSET, ...
    Side side,                                  // DEBIT | CREDIT
    Money amount,
    String memo
) { }
```

### Idempotency keys

Each spoke owns its key format, but the format must be deterministic from the business event so re-runs collide:

| Spoke | Key shape |
| ----- | --------- |
| ASC842 (periodic) | `asc842:periodic:{contract_id}:{period_yyyymm}` |
| ASC842 (modification) | `asc842:mod:{contract_id}:{event_id}` |
| Payment | `pmt:{payment_id}:{attempt_id}` |
| Accrual | `acc:{contract_id}:{period_yyyymm}:{accrual_type}` |
| Allowance | `alw:{contract_id}:{allowance_id}` |
| PctRent | `pct:{contract_id}:{period_yyyymm}` |
| Recovery | `rec:{contract_id}:{period_yyyymm}:{recovery_type}` |

The hub enforces `UNIQUE (source_module, idempotency_key)` at the DB level. A duplicate submission is **accepted with the prior result** (not an error) — this is how retries become safe.

### Spoke port (consumer-facing)

```java
// application/port/out/AccountingEngineGateway.java
public interface AccountingEngineGateway {
    SubmissionResult submit(CandidateSubmission submission);
    SubmissionResult submitBatch(List<CandidateSubmission> batch);   // atomic — all or none
    Optional<SubmissionResult> lookup(SourceModule m, IdempotencyKey k);
}
```

A batch is **atomic**: either all candidates post, or none do. Spokes use this for multi-line events (e.g., termination + auto-reverse, §asc842).

## 3. AccountingEngine ↔ WorkflowEngine Approval Protocol

> **Resolves GAP-003** (was previously open). Adopts **Option C — Hybrid**: AE owns approval *configuration and decisions*; WorkflowEngine owns task *surfacing*. AE remains the system of record for whether a candidate is approved or not.

### Lifecycle

```
spoke submits → AE validates → AE resolves ApprovalConfiguration
                                       │
                       ┌───────────────┴────────────────┐
              no approval needed                  approval needed
                       │                                │
              AE posts → GL                  AE creates ApprovalRequest (pending)
                                                        │
                                       AE emits ApprovalRequested event
                                                        │
                                   WorkflowEngine consumes, creates WorkflowTask(s)
                                       in My Tasks UI, routes to approver(s)
                                                        │
                                   approver decides → WorkflowEngine emits
                                       ApprovalDecided(request_id, decision, approver)
                                                        │
                                       AE consumes → updates ApprovalRequest
                                                        │
                       ┌────────────────────────────────┴───────────────────────────┐
                  approved                                                       rejected
                       │                                                            │
              AE posts → GL                                       AE marks candidate rejected;
              AE emits ApprovalGranted                            emits ApprovalRejected;
                                                                  source spoke notified for cleanup
```

### Contract

```java
// AE → WorkflowEngine (Kafka event, outbox-published)
public record ApprovalRequested(
    ApprovalRequestId requestId,
    TenantId tenantId,
    SubmissionRef submissionRef,                // points back to the candidate batch
    ApprovalConfigurationRef configurationRef,  // which matrix row matched
    List<ApprovalStep> chain,                   // multi-step support (see below)
    Money totalAmount,
    UserId submitter,                           // for SoD enforcement on the WF side
    Instant deadline                            // SLA — drives escalation
) { }

// WorkflowEngine → AE (Kafka event, outbox-published)
public record ApprovalDecided(
    ApprovalRequestId requestId,
    ApprovalStepId stepId,                       // which step decided
    Decision decision,                           // APPROVED | REJECTED | RETURNED
    UserId approver,
    String reason,
    Instant decidedAt
) { }
```

### Multi-step approvals

`chain` is an ordered list. A submission may require Manager → Director → Controller. WorkflowEngine surfaces them **one at a time** in order; AE remains pending until the **final** step approves (or any step rejects, which short-circuits). The chain is fully resolved by AE at submission time — WorkflowEngine does not re-resolve.

### Rejection and return

- `REJECTED`: terminal. AE marks the candidate rejected, emits `ApprovalRejected`, source spoke must clean up (e.g., release reservation, mark payment voided).
- `RETURNED`: not terminal. Sent back to the submitter for revision. AE marks the candidate `RETURNED`; the spoke may resubmit with a **new idempotency key** (the original key is now consumed).

### Timeout / escalation

`deadline` drives WorkflowEngine escalation. If breached, WorkflowEngine emits `ApprovalEscalated(requestId, escalatedTo)`; the matrix tier shifts up (e.g., Director → Controller). AE does not track time itself — it trusts the WorkflowEngine SLA contract.

### Failure modes

| Failure | Behavior |
| ------- | -------- |
| AE → WF outbox publish fails | Spring outbox retries; submission stays `pending`. Safe — no GL posting yet. |
| WF crashes mid-task | Recovery job re-emits `ApprovalRequested` for any AE request older than threshold without `ApprovalDecided`. AE-side dedupe (request_id) absorbs duplicates. |
| WF → AE outbox publish of decision fails | WF retries; AE state remains pending. A periodic AE reconciliation polls WF for any decisions older than expected SLA. |
| Decision arrives for unknown request_id | AE rejects with `UNKNOWN_REQUEST`; WF logs and quarantines. Likely a replay after data migration. |
| SoD violation at submission | AE rejects the submission immediately with `SOD_VIOLATION`; never reaches WF. |
| Submitter is also the (only) approver | AE rejects at config-resolution time with `NO_VALID_APPROVER`. |

### What AE does *not* delegate

- Approval configuration storage (the matrix). Always AE.
- The decision record (system of record). Always AE.
- SoD validation. Always AE.
- Idempotency. Always AE.

WorkflowEngine is the **task surface** and the **escalation timer**. Nothing more.

## 4. Event-Driven Coordination

All inter-service coordination is via Kafka + the **outbox pattern** per `java-messaging`. No spoke makes synchronous HTTP calls into the AE for posting; submissions are typed events on a dedicated topic.

```
Topics:
  accounting.candidates.v1            spoke → AE (submission)
  accounting.results.v1               AE → spokes (submission accepted/rejected/posted)
  accounting.approvals.requested.v1   AE → WorkflowEngine
  accounting.approvals.decided.v1     WorkflowEngine → AE
  audit.events.v1                     AE → AuditService (per ADR-011)
```

- Partition key: `tenant_id` — strict per-tenant ordering, parallelism across tenants.
- Schema registry: Avro per `java-messaging`. Backward-compatible evolution required.
- Outbox tables on both sides — events are committed in the same transaction as the state change.

> **Open question:** synchronous path for ad-hoc UI posting (e.g., manual JE entry). Current direction: still goes via the same submission contract but with an in-process gateway adapter that awaits the AE result before returning. Document when the manual-JE UI is built.

## 5. Failure Modes — Partial and Compensating

### Partial spoke failure mid-batch

Per GAP-013: when a multi-contract submission (e.g., 1,000 contracts in nightly accrual) fails partway:

- **Per-contract submission** (the recommended model): each contract submits its own batch with its own idempotency key. A failure on contract N does not poison contract N-1 or N+1. The spoke maintains a `pending_submissions` table and a recovery job retries failed ones.
- **All-or-nothing batch** is reserved for tightly coupled candidates (e.g., termination + auto-reverse). The batch must be small enough to retry idempotently.

### Spoke crash after submission, before result handling

The AE has already posted (idempotency key recorded). On restart the spoke calls `gateway.lookup(source, key)` to fetch the prior result and resume. **Never** infer "must have failed" from absence of state — always look up.

### AE rejects a candidate (validation failure)

`SubmissionResult` carries `status = REJECTED` with a reason code. The spoke must:
1. Log the rejection (full submission + reason).
2. Surface it to the source business event (mark the originating record `ACCOUNTING_REJECTED`).
3. **Not** silently retry — most rejections are deterministic (out-of-balance, invalid GL role).

### Compensating transactions

The AE never UPDATEs. To reverse a posted entry, the spoke submits a **correcting** candidate that debits/credits the inverse. The idempotency key for the corrector encodes the original (`...:correction-of:<orig_key>`) so retries are safe.

## 6. ArchUnit Rules — Enforce the Boundaries

Add to every spoke service's test module:

```java
@AnalyzeClasses(packages = "com.asg.<spoke>", importOptions = DoNotIncludeTests.class)
public class HubSpokeArchitectureTest {

    @ArchTest static final ArchRule spokes_must_not_write_gl_directly =
        noClasses().that().resideInAnyPackage("..application..", "..infrastructure..")
            .should().dependOnClassesThat().haveNameMatching(".*GlJournal.*Repository")
            .orShould().dependOnClassesThat().haveNameMatching(".*GlEntry.*Jpa.*");

    @ArchTest static final ArchRule spokes_post_only_via_gateway =
        classes().that().resideInAPackage("..application.usecase..")
            .should().onlyDependOnClassesThat().resideOutsideOfPackage("..infrastructure.persistence.gl..")
            .andShould(dependOn(AccountingEngineGateway.class));

    @ArchTest static final ArchRule no_spoke_to_spoke_calls =
        noClasses().that().resideInAPackage("com.asg.<spoke>..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.asg.payment..", "com.asg.asc842..", "com.asg.accrual..",
                "com.asg.allowances..", "com.asg.pctrent..", "com.asg.recoveries..")
            .as("spokes must coordinate via the hub, never call each other directly");

    @ArchTest static final ArchRule submissions_carry_idempotency_key =
        classes().that().areAssignableTo(CandidateSubmission.class)
            .should().haveOnlyFinalFields();
}
```

In the AE itself:

```java
@ArchTest static final ArchRule gl_writes_only_from_posting_service =
    classes().that().dependOnClassesThat().haveNameMatching(".*GlJournalEntryRepository")
        .should().resideInAPackage("..application.posting..");
```

CI fails on violation. **A spoke importing another spoke's package is a P0 review block.**

## 7. SoD and Approval Configuration

- The `ApprovalConfiguration` is a 3-dim matrix per ADR-007: `transaction_type × amount_tier × tenant`. Stored in AE.
- Each row resolves to an ordered `chain` of approver roles.
- A submitter who matches any role in the resolved chain is removed from that step; if removing them empties a step, the request is rejected (`NO_VALID_APPROVER`).
- Configuration changes are themselves audited and require an approval (meta-approval). The bootstrap matrix is a Flyway migration; subsequent changes go through the UI + AE approval flow.

## 8. Tenancy

- All AE state is tenant-scoped. Schema-per-tenant per `java-multi-tenancy`.
- Cross-tenant aggregation (for ASG-internal reporting) goes through a separate read-only export path, never through AE primaries.
- The matrix is stored per tenant. There is no global matrix.

## 9. Smells

- A spoke importing `GlJournalEntryRepository`. P0.
- A spoke importing another spoke's package. P0.
- A submission without an `idempotency_key`. Reject at the boundary.
- An AE submission handler that calls a spoke synchronously. The flow is event-driven; sync calls reintroduce coupling and ordering bugs.
- A "manual override" path that bypasses the matrix. There is no such path — manual JEs are submissions like any other.
- An UPDATE on `gl_journal_entry`. Append a correcting entry.

## Reference

- `.claude/skills/java-messaging/SKILL.md` — outbox pattern, topic conventions.
- `.claude/skills/java-data-governance/SKILL.md` — immutable audit (ADR-011 wiring).
- `.claude/skills/lib/jabrena/314-frameworks-spring-kafka/references/314-frameworks-spring-kafka.md` — Spring Kafka deep dive.
- ADR-007 (hub-and-spoke), ADR-011 (append-only audit), GAP-001, GAP-003, GAP-013, GAP-019.
