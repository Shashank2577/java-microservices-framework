---
name: java-migrations
description: Use whenever changing the database schema, adding a Flyway migration, or planning a schema change that can't be done in one step (drop column, narrow type, rename, etc.). Covers Flyway versioned/repeatable layout, per-schema (per-tenant) migrations, the expand/contract pattern, and migration testing.
---

# Database Migrations — Flyway

## 1. Layout

```
src/main/resources/db/migration/
├── public/                          # shared schema migrations
│   ├── V001__tenants_table.sql
│   ├── V002__shedlock.sql
│   └── R__public_views.sql
└── tenant/                          # applied per tenant schema
    ├── V001__init.sql
    ├── V002__add_orders.sql
    ├── V003__add_order_lines.sql
    └── R__order_views.sql
```

- `V<seq>__<snake_summary>.sql` — versioned, runs once, **immutable** after merge.
- `R__<name>.sql` — repeatable; runs whenever its checksum changes. Use for views, functions, materialized-view definitions.
- Numbering: zero-padded 3 digits (`V001`...`V999`); switch to `V20260525_01__...` once you cross ~500 or want date-based ordering across teams.

## 2. The Cardinal Rules

1. **Never edit a merged migration.** Add a new one. Flyway will refuse to start if a checksum changed.
2. **Forward-only.** No down migrations. If you need to undo, write a new forward migration that undoes it.
3. **Idempotent where possible.** `CREATE TABLE IF NOT EXISTS`, `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. Makes re-runs safe in dev.
4. **One concern per migration.** Adding a table and adding a column to another table = two migrations.
5. **Ship with the code.** Entity change without a migration = a broken PR.
6. **Run in CI against a real Postgres** (Testcontainers) before merge.

## 3. Configuration

`application.yml`:
```yaml
spring.flyway:
  enabled: false                    # we run Flyway programmatically (per-tenant)
  validate-on-migrate: true
  baseline-on-migrate: false        # never true in shipped code
  out-of-order: false
```

Programmatic boot:
```java
@PostConstruct
void migrate() {
    // 1. public schema (shared metadata)
    Flyway.configure().dataSource(ds)
        .schemas("public").defaultSchema("public")
        .locations("classpath:db/migration/public")
        .load().migrate();

    // 2. each tenant schema
    tenantRegistry.allActive().forEach(tenantMigrator::migrate);
}
```

## 4. Migration Template (versioned)

```sql
-- V003__add_orders.sql
-- Adds the orders table for the order placement feature (PROJ-142).
-- Notes:
--   - tenant_id is implicit via schema; column omitted in tenant schema.
--   - status backed by a CHECK to keep DB enforcing the enum.

CREATE TABLE IF NOT EXISTS orders (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id  UUID          NOT NULL,
    status       VARCHAR(32)   NOT NULL,
    total        NUMERIC(19,4) NOT NULL,
    currency     CHAR(3)       NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   TEXT          NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by   TEXT          NOT NULL,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_orders_status CHECK (status IN ('DRAFT','PLACED','PAID','SHIPPED','CANCELLED','DELIVERED'))
);

CREATE INDEX IF NOT EXISTS ix_orders_customer_status
    ON orders (customer_id, status, created_at DESC);

COMMENT ON TABLE orders IS 'Customer orders, lifecycle from DRAFT to DELIVERED.';
```

Conventions in every versioned file:
- Header comment with intent + issue ref.
- `IF NOT EXISTS` on creates.
- Explicit constraint names (`chk_*`, `uq_*`, `fk_*`, `ix_*`).
- Index every FK.
- Add `COMMENT ON ...` for non-obvious columns/tables.

## 5. Expand / Contract — Safe Schema Evolution

Never do destructive changes in one step in any environment where the previous code version might be running.

### Renaming a column `old` → `new`
1. **Expand**: add `new`, backfill from `old`, keep both.
   ```sql
   ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_city TEXT;
   UPDATE orders SET shipping_city = ship_city WHERE shipping_city IS NULL;
   ```
2. Ship code that writes both, reads from `new`.
3. **Contract**: drop `old` in a later release once you're sure no instance writes it.
   ```sql
   ALTER TABLE orders DROP COLUMN IF EXISTS ship_city;
   ```

### Narrowing a type / adding NOT NULL
1. Add new nullable column / wider type.
2. Backfill in batches (avoid long locks).
3. App writes the new column.
4. Verify zero NULLs.
5. `ALTER ... SET NOT NULL` (uses table scan but no rewrite in PG ≥ 12 if no default change).
6. Drop the old column.

### Adding a NOT NULL column with default to a big table
- Postgres ≥ 11: `ALTER TABLE x ADD COLUMN c TEXT NOT NULL DEFAULT 'foo'` is fast (metadata only).
- For computed/derived backfills, do it in a separate migration after the column add, in batches.

### Splitting a table
1. Create the new table.
2. Backfill.
3. Dual-write from the app.
4. Switch reads.
5. Stop writing the old table.
6. Drop it.

## 6. Long Operations & Locks

- Avoid `ALTER TABLE` operations that rewrite the whole table on big tables. Postgres docs list which clauses cause rewrites.
- Build indexes with `CREATE INDEX CONCURRENTLY` (cannot be in a transaction; use a dedicated migration `-- !flyway.placeholders.transactional=false` if using a Flyway extension that supports it, or wrap with a flag).
- For huge backfills, do them outside Flyway (one-off admin job) and have the migration just verify the result.

## 7. Per-Tenant Migrations — Pitfalls

- **Same migration set, applied N times** — once per tenant schema. Make sure every statement is schema-local (no hardcoded `public.foo`).
- Use `${tenant_schema}` placeholder for any cross-reference that must stay inside the tenant schema (rare; we mostly rely on `search_path`).
- A migration that's slow on one tenant is slow on all tenants — onboarding latency multiplies.
- Test the migration against a tenant schema fixture with realistic row counts.

## 8. Migration Testing

```java
@SpringBootTest
@Testcontainers
class FlywayMigrationsTest {
    @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Test void public_migrations_apply_cleanly() {
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .schemas("public").defaultSchema("public")
            .locations("classpath:db/migration/public").load().migrate();
    }

    @Test void tenant_migrations_apply_cleanly_to_a_fresh_schema() {
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .schemas("t_test").defaultSchema("t_test").createSchemas(true)
            .locations("classpath:db/migration/tenant").load().migrate();
    }
}
```

For each PR with a migration, run the test against the previous version's snapshot too — confirms migrations apply cleanly from any starting point.

## 9. Data Backfill Patterns

Schema migrations and data backfills look similar but solve different problems. Don't conflate them.

### 9.1 Schema migration vs data backfill — the distinction

| Aspect | Flyway migration | Data backfill |
| ------ | ---------------- | ------------- |
| Scope | Schema change + small fixed data (`INSERT ... ON CONFLICT DO NOTHING` for a known set) | Touches many rows (10k+ to millions) |
| Duration | Seconds | Minutes to hours |
| Locks | Holds locks, blocks app startup if Flyway runs at boot | Chunked, short transactions per batch |
| Restartable | No — atomic, all-or-nothing | Yes — cursor-based |
| Where it runs | At deploy, via Flyway | Separate admin job, after deploy |

**Rule of thumb**: if the data work takes more than ~10s or touches more than ~10k rows, it's a backfill, not a migration. Refuse to put it in a Flyway `V*.sql`.

### 9.2 Backfill job pattern (Spring `CommandLineRunner` + chunking)

```java
@Component
@RequiredArgsConstructor
class CustomerEmailLowercaseBackfill {
    private final JdbcTemplate jdbc;
    private final BackfillStateRepo state;

    public BackfillResult run(BackfillRunRequest req) {
        var checkpoint = state.last(req.name(), req.tenantId());  // cursor (id) of last processed row
        long processed = 0;
        UUID cursor = checkpoint.cursor();
        while (true) {
            var batch = jdbc.queryForList(
                "SELECT id FROM customers WHERE tenant_id = ? AND id > ? ORDER BY id LIMIT 500",
                UUID.class, req.tenantId(), cursor);
            if (batch.isEmpty()) break;
            for (UUID id : batch) updateOne(id, req.tenantId());
            cursor = batch.get(batch.size() - 1);
            state.checkpoint(req.name(), req.tenantId(), cursor, processed += batch.size());
            if (req.shouldStop().get()) return BackfillResult.PAUSED(cursor, processed);
        }
        state.markComplete(req.name(), req.tenantId());
        return BackfillResult.DONE(processed);
    }
}
```

### 9.3 `backfill_runs` tracking table

```sql
CREATE TABLE backfill_runs (
    name        VARCHAR(128) NOT NULL,
    tenant_id   UUID         NOT NULL,
    cursor      TEXT         NULL,
    processed   BIGINT       NOT NULL DEFAULT 0,
    status      VARCHAR(32)  NOT NULL,    -- PENDING|RUNNING|PAUSED|DONE|FAILED
    started_at  TIMESTAMPTZ  NULL,
    finished_at TIMESTAMPTZ  NULL,
    last_error  TEXT         NULL,
    PRIMARY KEY (name, tenant_id)
);
```

Ships as a normal Flyway `V*.sql` in the `public` schema — it's schema, not data.

### 9.4 Per-tenant backfills

Two execution models:

- **Loop tenants in one job process** (default): same image, one tenant at a time, set `TenantContext`, run, persist checkpoint per `(name, tenant_id)`. Tenant-fairness via round-robin or priority queue.
- **Parallel per-tenant job processes**: when work is huge and tenants are independent. Use ShedLock keyed by `(name, tenant_id)` so the same tenant isn't backfilled twice concurrently.

A migration that's slow on one tenant is slow on all tenants; a backfill that's slow on one tenant only blocks that tenant.

### 9.5 Idempotency rules

The backfill operation must be **safe to re-run on already-processed rows**. Pick one:

- Predicate excludes processed: `WHERE NOT processed_flag`.
- Update is naturally idempotent: `UPDATE ... SET email = lower(email) WHERE email <> lower(email)`.
- Track per-row processed state in a side table.

The outer loop is restart-safe via `backfill_runs.cursor` — kill the pod mid-run, restart, resume.

### 9.6 Throughput controls

- **Batch size**: 100-1000 rows. Tune based on row size + downstream pressure.
- **Pacing**: optional sleep between batches (`100ms`) if backfill pressures the DB or downstream APIs.
- **Read-replica preference**: read source data from a replica when possible; writes still hit primary.
- **Resilience**: each batch in its own short transaction. A row failure logs + metric + skip with a `failed_ids` table; never abort the whole job for a single bad row.

### 9.7 Observability

- Metrics: `backfill_processed_total{name, tenant_class}`, `backfill_failed_total`, `backfill_lag_estimate{name}` (gauge — rows remaining).
- Per-batch INFO log: `backfill_batch name=... tenant=... processed=... cursor=...`.
- Alert if a backfill marked `RUNNING` hasn't checkpointed in 10 minutes.

### 9.8 Operator interface

- Admin endpoints: `POST /admin/backfills/{name}/start?tenantId=...` / `pause` / `resume` / `status`.
- Gated by admin role + audit log.
- Stats surfaced in an internal dashboard.

### 9.9 Backfill ↔ Flyway integration

The lifecycle of a schema evolution that needs a backfill:

1. **Flyway migration** ships the enabling schema change (e.g., add `email_normalized` column nullable).
2. **Deploy** the app — it dual-writes or starts populating the new column for new rows.
3. **Admin task** runs the backfill across all tenants — chunked, restartable, observable.
4. **Verify** backfill DONE for every tenant; zero `failed_ids`.
5. **Cutover Flyway migration** (later release) does the destructive contract step: `ALTER ... SET NOT NULL`, `DROP COLUMN email_original`, etc.

Document the contract/timeline in a runbook. The cutover migration MUST NOT ship until backfill verification passes.

### 9.10 Pre-merge checklist (backfill)

- [ ] Backfill is a job/component, not a Flyway SQL file
- [ ] Cursor-based, restartable
- [ ] `backfill_runs` row created with proper status transitions
- [ ] Per-tenant if appropriate; ShedLock if parallel
- [ ] Idempotent (predicate, naturally idempotent update, or row-state)
- [ ] Metrics + alert wired
- [ ] Admin endpoint or CLI gated by admin auth
- [ ] Runbook entry linking schema migration → backfill → cutover migration

## 10. Anti-patterns — Refuse

- Editing a shipped `V*.sql`.
- A migration that doesn't exist for an entity change.
- Backfill loops in app code that should be `UPDATE ... WHERE ...`.
- `DROP COLUMN` in the same release as the code that stops writing it (do two releases — expand/contract).
- Long-running migrations on tables locked by online traffic (use `CONCURRENTLY` / batch).
- Using `dev` profile to `ddl-auto=update` and treating Flyway as optional.
- Migrations that depend on data that may or may not exist (write idempotent SQL).
- Mass `UPDATE` in a Flyway migration that takes minutes (locks the deploy, blocks app startup).
- Backfill with no cursor — restarts from the beginning if it dies.
- Backfill that mutates rows without checking they're not already correct (kills replication latency, blows out WAL).
- "We'll backfill in code on-read" with no completion tracking — eternal slow-path, never converges.
- Backfill blocking app startup. Flyway: never run a slow backfill at boot. A separate admin job: fine.

## References

- `.claude/skills/lib/jabrena/313-frameworks-spring-db-migrations-flyway/references/313-frameworks-spring-db-migrations-flyway.md` — Flyway + Spring reference notes (versioning, programmatic migration, per-schema setup).
