---
name: java-data-governance
description: Use when handling PII, GDPR DSAR (Data Subject Access Requests), data retention, right-to-erasure, audit logging (tamper-evident, compliance-grade), or data-residency boundaries. Covers PII classification, soft-delete vs crypto-shredding, scheduled purger jobs, append-only audit log with hash-chain, and per-tenant region pinning.
---

# Data Governance — Compliance Primitives

This skill defines the framework's **primitives** for handling regulated data: PII inventory, retention, erasure, audit, residency. The implementations here are concrete and opinionated; the regulatory **mapping** (which control satisfies which SOC2/ISO/HIPAA clause) is GRC's job, not the application's.

## 1. Scope

- **This skill is**: the framework's primitives for compliance — PII inventory, retention, erasure, audit, residency.
- **This skill is not**: a SOC2/ISO/HIPAA checklist. Mapping these primitives to your control framework is the GRC team's job. The code here gives them something to map *to*.

## 2. PII Classification

Annotation `@Pii(category)` on entity and DTO fields:

| Category        | Examples                                                          |
| --------------- | ----------------------------------------------------------------- |
| `DIRECT`        | email, phone, name, SSN, account number                           |
| `INDIRECT`      | IP, device id, precise geolocation                                |
| `SENSITIVE`     | health, ethnicity, sexual orientation, religion (regulated separately) |
| `PSEUDONYMIZED` | internal hashes used in place of `DIRECT`                         |

```java
public record CustomerDto(
    UUID id,
    @Pii(DIRECT)    String email,
    @Pii(DIRECT)    String fullName,
    @Pii(INDIRECT)  String lastLoginIp,
    @Pii(SENSITIVE) String dietaryRestrictions
) {}
```

Build-time codegen produces `META-INF/pii-inventory.json` per service. CI publishes the inventory to a known location. The data-governance team has **one place** to look — not a thousand JIRA tickets.

## 3. Right-to-Erasure (DSAR Delete)

Three lawful approaches. Pick per dataset based on volume and risk.

### 3.1 Soft delete + scheduled purge (default)

- Mark `deleted_at = now()`; hide from queries via a Hibernate `@Where` filter or repository default.
- `RetentionPurger` (see §4) hard-deletes after the retention window (project default: **30 days**).
- **Risk**: backups still contain the data until backup retention expires. **Document this** in your DSAR response template — saying "deleted" when backups still hold it is the kind of statement regulators care about.

### 3.2 Crypto-shredding

- Encrypt PII columns with a **per-tenant Data Encryption Key (DEK)** wrapped by a KMS master key.
- "Delete" = destroy the DEK in KMS → all encrypted blobs unreadable forever, **including in backups**.
- Best for high-volume datasets where physical delete is impractical, or when backup-tail compliance is needed.

### 3.3 Per-record DEK

- Each user gets a DEK; encrypted columns require that DEK to decrypt.
- Erase = destroy that user's DEK.
- **Highest** blast-radius isolation; **highest** operational cost (one row in KMS per user). Reserve for the highest-risk PII (health, government ID, financial credentials).

### 3.4 Erasure propagation

DSAR is an event, not an RPC:

```
user.erasure_requested.v1     → fanout to all services
user.erasure_completed.v1     ← per service, when its slice is gone
```

- Each service owns its slice of user data; each subscribes and processes.
- An **orchestrator** tracks completion across services in a `dsar_runs` table.
- Forward erasure to third parties (email provider, analytics, support tool) via their delete APIs — and **audit each external call**. If a third party has no delete API, you have a procurement problem, not an engineering one.

## 4. Retention Policies as Code

`retention.yml` per service:

```yaml
retentions:
  audit_log:          { days: 2555 }   # 7 years
  orders:             { days: 2555 }
  sessions:           { days: 30 }
  soft_deleted_users: { days: 30 }
  raw_webhooks:       { days: 90 }
```

Scheduled `RetentionPurger` (uses `java-async-sync-jobs` patterns):
- Reads `retention.yml`.
- Per table, deletes rows past the retention horizon in bounded batches.
- Emits metrics (`retention_purged_total{table}`) and audit events.

Retention policy in a wiki is **not** retention policy. If it isn't in the repo, it doesn't exist.

## 5. Audit Log — Tamper-Evident, Distinct from App Logs

Application logs (Loki/ELK, see `java-observability`) are **operational** and **mutable**. The audit log is **compliance** and **append-only**. Do not conflate them.

### 5.1 Schema

```sql
CREATE TABLE public.audit_log (
  id             BIGSERIAL    PRIMARY KEY,
  tenant_id      UUID         NOT NULL,
  actor          VARCHAR(128) NOT NULL,     -- user id, service account, "system"
  action         VARCHAR(64)  NOT NULL,     -- e.g. "user.role.changed"
  resource_type  VARCHAR(64)  NOT NULL,
  resource_id    VARCHAR(128),
  before_json    JSONB,
  after_json     JSONB,
  occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  prev_hash      BYTEA        NOT NULL,
  hash           BYTEA        NOT NULL
);
```

- `hash = sha256(prev_hash || canonical(row_minus_hash))`. Hash chain → tampering detectable.
- Periodically ship the latest hash to an **external immutable store**: S3 Object Lock (WORM), or a notarization service. A chain rooted only in the same DB the chain protects is theatre.
- **No `UPDATE`, no `DELETE`** on this table — enforced by a DB role separate from the app role. The app account has `INSERT` and `SELECT` only.
- Soft-deleting audit rows is a no-op; the hash chain catches missing rows at verification time anyway.

### 5.2 What gets audited (minimum)

- Authentication events: login, logout, password change, MFA enroll/disable
- Access denied events
- All admin actions: tenant config changes, user role changes, secret rotation, support impersonation, tenant suspend/restore
- Data exports, DSAR fulfillment
- Schema migrations applied (which version, by whom)
- Deletions of business records

### 5.3 What does NOT get audited

- Routine reads (volume kills the chain and there is no compliance value)
- Internal background jobs without user action (use app logs)

## 6. Audit Log Query / Export

- Admin / external-auditor endpoint exports per-tenant slices by date range.
- **Verify the hash chain before export** — alert and refuse on break. An audit log that can't prove its integrity is worse than none, because it pretends it can.

## 7. Data Residency

- Each tenant carries a region pin (`public.tenants.region`).
- Routing rules:

| Component | Rule                                                            |
| --------- | --------------------------------------------------------------- |
| DB        | Per-region cluster. Tenant schemas live in the tenant's region. |
| Kafka     | Per-region cluster. Analytics topic loaders filter per region.  |
| Backups   | Bucket per region; bucket policy denies cross-region replication. |
| Caches    | Per-region Redis; never a single global cluster for PII-bearing keys. |

- Cross-region access (e.g., support staff in US helping an EU tenant) is **a feature flag at admin level + an audit event** — never silent.

## 8. Cross-Border Transfer Rules

- Some tenants prohibit data leaving their region. Codify: `public.tenants.allow_cross_region BOOLEAN NOT NULL DEFAULT false`.
- Any code path that could ship data across regions (export, replication, third-party push) checks the flag and refuses if false.
- Email/SMS providers: pick **per region** (SES eu-west, SES us-east, etc.). Per-tenant provider selection so an EU tenant's outbound never traverses US infra.

## 9. Encryption at Rest

| Layer            | Mechanism                                                                  |
| ---------------- | -------------------------------------------------------------------------- |
| DB (whole-disk)  | Postgres at-rest encryption via the platform (RDS/CloudSQL). **Mandatory.** |
| Column (high-sensitivity) | AES-256-GCM, envelope-encrypted with a KMS-wrapped DEK. One method: `Cipher.encrypt(value, dataKey)`. |
| KMS keys         | Rotated annually; old key versions retained **decrypt-only**.              |

Never roll your own crypto. Never hardcode keys. Never check a key into git. See `java-security` §11.

## 10. Data Minimization

- Collect only what is needed. **Document the why** for every PII field — in code, as a Javadoc tag or annotation argument, not in a separate doc that drifts.
- Periodic review (quarterly): every PII field has a current business justification, or it is removed. The review is owned, scheduled, and produces a tracked artifact.

## 11. Consent and Lawful Basis

Per-purpose consent:

```sql
CREATE TABLE consent.user_consents (
  user_id     UUID         NOT NULL,
  purpose     VARCHAR(64)  NOT NULL,   -- 'marketing_email', 'product_analytics', etc.
  granted     BOOLEAN      NOT NULL,
  granted_at  TIMESTAMPTZ  NOT NULL,
  revoked_at  TIMESTAMPTZ,
  PRIMARY KEY (user_id, purpose)
);
```

Code paths for non-essential processing are **gated on consent**. "Essential" is a narrow category — payment processing for an order the user placed is essential; "we'd like to email you about new features" is not.

## 12. Observability for Governance

| Metric                              | Why                                                |
| ----------------------------------- | -------------------------------------------------- |
| `dsar_requests_total{state}`        | Track DSAR pipeline (received/in-progress/done/failed). |
| `dsar_completion_seconds`           | Histogram. Regulatory SLAs are real (e.g., 30 days). |
| `retention_purged_total{table}`     | Verify the purger is actually running.             |
| `audit_hash_chain_break_total`      | **Must be zero.** Page on first occurrence.        |
| `pii_unredacted_logged_total`       | Fail closed: any unredacted PII in app logs is an incident, not a warning. |

## 13. Multi-Tenancy + Governance

- **Tenant offboarding**: full data deletion across all stores (DB schema drop, Kafka topic cleanup, object storage prefixes, caches). Verify via audit query. Emit a final certificate (timestamped, hash-anchored).
- **Pause vs Delete**: different lifecycles. Codify in `public.tenants.state` (`ACTIVE`, `SUSPENDED`, `PAUSED`, `OFFBOARDING`, `DELETED`). Suspended tenants retain data; deleted tenants do not. Mixing these is how you accidentally delete a paying customer.

## 14. Anti-Patterns — Refuse

- PII fields without `@Pii` annotation — the inventory is wrong before it ships.
- Hard-delete with no audit trail.
- Audit log living in the same DB role as the app — anyone with app creds can `UPDATE`/`DELETE`.
- "We'll add audit later." Retrofitting audit onto a year of writes is harder, and the year of writes is gone.
- Retention policy in a Confluence page, not in `retention.yml`.
- Crypto-shred without a **tested** key destruction procedure. Untested destruction is a wish.
- Mixing PII across regions without a region check — the moment a cross-region path exists, every code change needs a residency review.
- Logging PII in app logs. Use `java-observability` redaction. There is no acceptable reason.

## 15. Pre-Merge Checklist

- [ ] New PII fields annotated with `@Pii(category)`; inventory regenerates cleanly.
- [ ] Retention policy added to `retention.yml` for any new table holding user data.
- [ ] Audit events emitted for any new admin/state-changing action.
- [ ] DSAR handler updated to cover any new user-owned data.
- [ ] No new direct PII logging; redaction in place for new sensitive fields.
- [ ] Region check on any new cross-region path (export, replication, third-party push).
- [ ] If column-encrypted: KMS key version recorded; rotation path documented.

## 16. Reference

- `.claude/skills/lib/jabrena/124-java-secure-coding/references/124-java-secure-coding.md` — crypto primitives, key handling, secure randomness
- GDPR Article 17 — right to erasure: https://gdpr-info.eu/art-17-gdpr/
- GDPR Article 30 — records of processing: https://gdpr-info.eu/art-30-gdpr/
- NIST SP 800-122 — Guide to Protecting the Confidentiality of PII: https://csrc.nist.gov/publications/detail/sp/800-122/final
- ISO/IEC 27018 — Code of practice for PII in public clouds
