---
name: java-ops-dr-runbooks
description: Use when defining disaster recovery posture, RPO/RTO targets, backup strategy, Postgres PITR, Kafka DR, runbook templates, or on-call procedures. Covers tested-restore drills (per-tenant restore via schema-per-tenant), Kafka MirrorMaker2/topic backup, application-level recovery (outbox replay, idempotent consumers), and the runbook structure on-call must rely on at 3am.
---

# Ops, DR & Runbooks

DR is a discipline, not a document. Backups you have never restored do not exist. Runbooks you have never executed are fiction. This skill defines the minimum operational posture for every service in the framework.

## 1. RPO/RTO Classification

Every service declares its tier; the tier drives backup config and DR investment. Pick one, write it in the service README.

| Class | Example | RPO | RTO |
| --- | --- | --- | --- |
| Tier-1 | orders, payments, billing | 5 min | 1 hour |
| Tier-2 | user profile, settings | 1 hour | 4 hours |
| Tier-3 | analytics, audit (historical) | 24 hours | 24 hours |

- **RPO** = how much data can we lose? (acceptable data-loss window)
- **RTO** = how long until we are back? (acceptable downtime window)
- Tier-1 implies cross-region replication, ≤5min snapshot/WAL cadence, hot standby. Tier-3 may live with nightly snapshots only.
- Do not let product give you "Tier-1" for everything. Force the conversation; tiers cost real money.

## 2. Postgres Backup & PITR

- **WAL archiving to S3** — continuous. `wal_level=replica` minimum; `replica` is enough for PITR (not for logical CDC; for that see `java-messaging` §10).
- **Base backups daily**, retained 30 days.
- **WAL retention** ≥ 7 days so any point in the last week is recoverable.
- **Managed Postgres** (RDS / CloudSQL / Aurora): point-in-time recovery enabled, retention ≥ 35 days, automated snapshots replicated cross-region.
- Encryption-at-rest on snapshots and on the WAL bucket. KMS keys cross-account-readable for the DR account.

```bash
# Local pg_basebackup, scripted nightly
pg_basebackup -h $PGHOST -U $REPL_USER -D /backup/$(date +%F) \
  -Fp -Xs -P -R --checkpoint=fast
aws s3 sync /backup/$(date +%F) s3://prod-pg-backups/$(date +%F)/
```

## 3. Restore Drill — Quarterly Minimum

Untested backups are a category error. Do this every quarter, with calendar entries and owners:

1. Restore production-class data to a non-prod env from the previous day's snapshot.
2. Measure **actual** RTO. Document the gap vs declared RTO.
3. Verify per-tenant restore (see §4).
4. Capture the drill output (logs, timings, screenshots) to an immutable bucket — compliance auditors will ask.

Pin a runbook step in every Tier-1 service: **"Restore tenant `<slug>` from `<date>`"** with the exact commands. No prose, no "see wiki".

## 4. Per-Tenant Restore — The Schema-Per-Tenant Payoff

One tenant's bad migration → restore **only that tenant's schema**. Other tenants keep running. This is one of the biggest reasons we chose schema-per-tenant (see `java-multi-tenancy` §1).

```bash
# 1. Extract just the affected tenant schema from snapshot
pg_dump -h $SNAPSHOT_HOST -U $USER -d appdb \
  --schema=t_acme_corp --format=custom --file=acme.dump

# 2. Drop the corrupted schema in prod (or restore to side schema and swap)
psql -h $PROD_HOST -U $USER -d appdb \
  -c "ALTER SCHEMA t_acme_corp RENAME TO t_acme_corp_corrupt_$(date +%s);"

# 3. Restore the clean schema
pg_restore -h $PROD_HOST -U $USER -d appdb --schema=t_acme_corp acme.dump

# 4. Bring schema up to current Flyway head
curl -X POST $ADMIN/tenants/acme_corp/migrate

# 5. Replay outbox events from the last N hours (idempotent consumers handle dedup)
curl -X POST $ADMIN/outbox/replay?tenant=acme_corp&since=2h
```

Document and test this procedure. It is genuinely a competitive advantage over shared-schema designs.

## 5. Kafka DR

- **In-cluster replication factor ≥ 3**, `min.insync.replicas=2`. Already default.
- **Cross-region DR**: **MirrorMaker2** replicates topics + consumer offsets to a secondary cluster.
  - `replication.factor=3`, `sync.topic.acls.enabled=true`, `emulate.transactions=false` unless you actually use Kafka transactions.
  - Offsets are translated via `__consumer_offsets` replication + the `OffsetSyncStore` topic; consumers must use the translation API on failover.
- **RPO** is bounded by MM2 mirror lag — alert on it; expect seconds, fail the SLO if minutes.
- **Failover playbook** (write this down, practice it):
  1. Stop the dead-cluster producers (firewall or DNS).
  2. Repoint `spring.kafka.bootstrap-servers` to the DR cluster.
  3. Reset consumer group offsets via MM2's `RemoteClusterUtils.translateOffsets()` for each group.
  4. Restart services. Watch consumer lag.

If you have never executed this drill, you do not have Kafka DR.

## 6. Application-Level Recovery Primitives

The application contributes to recovery — it is not all infra.

- **Idempotent consumers + inbox** (`java-messaging` §8) → safe to replay topics from earlier offsets without double-processing.
- **Outbox** (`java-messaging` §7) → the DB is the source of truth; events can be re-published from Postgres if Kafka was lost.
- **Backfill jobs** → see `java-migrations` §9 for restartable, idempotent data jobs.
- **Read-only mode flag** per service → during a restore window, accept reads but reject writes with a 503; far better than corrupted writes layered on a partial restore.

## 7. Recovery Scenarios — What To Write Down

For every **Tier-1** service, a `docs/recovery-scenarios.md` covering at minimum:

| Scenario | Frequency in practice |
| --- | --- |
| Single tenant data corruption | High — most common real incident |
| Full DB lost / region failure | Low, but high blast radius |
| Kafka cluster lost | Medium |
| Bad deploy (rollback procedure) | Weekly-ish |
| Secret leaked (rotation procedure) | Quarterly-ish |
| Bad migration shipped (forward-fix) | Monthly-ish |

Each scenario has four fixed sections: **detection signals**, **immediate response**, **mitigation**, **root-cause + post-mortem owner**. No prose essays — bullets and commands.

## 8. Runbook Template

Every service: `docs/runbooks/<runbook-name>.md`. Required sections, in this order:

1. **Symptom** — what on-call observes (alert text, dashboard pattern).
2. **Dashboard / log query link** — direct URL, not "search Grafana".
3. **First 5 commands** — verbatim, copy-pasteable, no placeholders without examples.
4. **Decision tree** — if X then Y; if A then B.
5. **Mitigation steps** — what to actually do.
6. **Rollback** — when mitigation fails.
7. **Escalation** — who to page next, with how-to-reach (PagerDuty schedule link).
8. **Post-incident** — what to capture for the post-mortem.

### Example: `runbooks/kafka-consumer-lag-orders-placed.md`

```markdown
# Runbook: orders-svc — consumer lag on orders.order.placed.v1

**Symptom**: PagerDuty "orders-svc-consumer-lag-high" — lag > 10k for 5 min.
**Dashboard**: https://grafana.prod/d/orders-kafka?var-topic=orders.order.placed.v1
**Logs**: https://logs.prod/search?q=service:orders-svc+kafka

## First 5 commands
1. `kubectl -n prod get pods -l app=orders-svc` — are consumers up?
2. `kubectl -n prod logs -l app=orders-svc --tail=200 | grep -i kafka`
3. `kafka-consumer-groups --bootstrap-server $KBS --describe --group orders-svc`
4. `kubectl -n prod top pods -l app=orders-svc` — CPU/mem saturated?
5. `psql -h $PGHOST -d appdb -c "SELECT count(*) FROM processed_events WHERE processed_at > now() - interval '5 min';"`

## Decision tree
- Pods not running → scale up; check HPA, node pressure.
- Pods running, CPU 100% → scale `concurrency` in listener; add replicas.
- Pods idle, lag growing → check downstream (DB slow, DLT growing, poison message).
- DLT growing fast → poison message; pull payload, redrive after fix.

## Mitigation
1. Scale: `kubectl scale deploy/orders-svc --replicas=8`.
2. If poison: `curl -X POST $ADMIN/dlt/replay?topic=orders.order.placed.v1.dlt&from=<offset>`.

## Rollback
Revert replica count to baseline. Open Sev-3 if not resolved in 30 min.

## Escalation
Primary on-call orders-svc → backend platform on-call → engineering manager.

## Post-incident
Capture: lag chart, deploy timeline (last 24h), DLT message sample, root-cause.
Owner: primary on-call. Due: 48h after resolution.
```

Every runbook owned by a named human, dated, reviewed quarterly.

## 9. On-Call Handoff

- **Weekly rotation**, defined primary and secondary. Secondary exists so primary can sleep.
- **Handoff doc** updated every Monday — open incidents, planned changes this week, anything fragile.
- **Severity matrix**:

| Sev | Definition | Response | Pages? |
| --- | --- | --- | --- |
| Sev-1 | Customer-facing outage, data loss | 15 min | Yes |
| Sev-2 | Degraded service, workaround exists | 1 hour | Yes |
| Sev-3 | Internal degradation, no customer impact | Same day | Ticket |
| Sev-4 | Cosmetic, no impact | Next sprint | Ticket |

Sev-1/2 page. Sev-3/4 file a ticket. No exceptions — paging fatigue kills response.

## 10. Chaos & Game Days

- **Quarterly game day per Tier-1 service**: simulate one scenario from §7 in staging; measure RTO; refine the runbook.
- Pick a different scenario each quarter; do not just keep restoring the easy one.
- Optionally fault-injection (Chaos Monkey-style) — **only after** the team has runbooks and has rehearsed them. Chaos without runbooks is just damage.

## 11. Backups for Non-DB State

The DB is not the only stateful surface.

- **S3 / blob storage**: cross-region replication on, versioning on, MFA-delete on prod buckets.
- **Vault / secrets**: snapshot + unseal-key custody per Vault docs; rehearse unseal annually.
- **Kafka Schema Registry**: nightly JSON dump of all subjects to S3. Without this, a registry loss bricks every Avro consumer.
- **Object lock / immutability** on the backup bucket — ransomware will try to delete your backups first.

## 12. Cost Notes

DR is expensive. Tier the investment honestly.

- Tier-1: cross-region replicas, hot standby, ≤5min RPO. Real money.
- Tier-2: warm standby in a second AZ, cross-region snapshots. Moderate.
- Tier-3: nightly snapshot only. Cheap.
- Document the cost/risk tradeoff per service in an **ADR** (see `java-code-quality` §12). "We accept 24h RPO on analytics because the cost of <5min is $Xk/month and the data is reconstructible from operational topics."

## 13. Compliance Hooks

- Many audits (SOC 2, ISO 27001, HIPAA, PCI) require **quarterly restore drills with evidence**. Store drill outputs in an immutable bucket with retention ≥ 1 year.
- **Data residency**: document which tenants live in which region. Per-tenant region pinning at the registry level (`tenants.region` column); DR replication must respect residency (EU tenants do not replicate to US DR).
- Encryption keys: KMS key rotation policy documented; cross-region key access tested.

## 14. Anti-Patterns — Refuse

- "We have backups but never restored" — you do not have backups.
- Restore drills that only test the easiest scenario.
- Runbooks that link to wikis that link to wikis (depth > 1). At 3am, on-call wants the command, not a tour.
- Runbooks without timestamps or owners — they rot fast.
- An on-call alert with no runbook link.
- DR plan in a Google Doc that no one can find at 3am.
- "We'll fail over manually" — never tested = does not work.
- Tier classifications that are aspirational rather than budgeted.
- "Restore takes about an hour" — measure it; do not guess.
- Single on-call with no secondary — burnout is a Sev-1.

## 15. Pre-Merge / Pre-Launch Checklist

Before any service ships to prod:

- [ ] Service declares RPO/RTO tier in README.
- [ ] WAL archiving / PITR confirmed for its DB; managed-DB retention ≥ 35 days.
- [ ] Per-tenant restore procedure documented with exact commands.
- [ ] At least one runbook per known alert; every alert links to its runbook.
- [ ] Quarterly drill scheduled on the team calendar with named owner.
- [ ] DR cost documented in an ADR.
- [ ] Schema Registry backup job in place (if the service uses Avro).
- [ ] On-call rotation defined; primary + secondary assigned.
- [ ] Severity matrix referenced in the alert routing config.

## 16. References

- Postgres docs — Continuous Archiving and Point-in-Time Recovery: https://www.postgresql.org/docs/current/continuous-archiving.html
- Kafka MirrorMaker2: https://kafka.apache.org/documentation/#georeplication
- Debezium replication slot ops (for CDC-based outbox DR): https://debezium.io/documentation/reference/stable/connectors/postgresql.html
- `.claude/skills/lib/jabrena/030-architecture-adr-general/references/030-architecture-adr-general.md` — ADR template for documenting DR/cost tradeoffs.
- `java-multi-tenancy` §1 (tenant registry), §5 (per-tenant Flyway) — schema model that makes per-tenant restore tractable.
- `java-messaging` §7 (outbox), §8 (inbox), §10 (CDC) — application-level recovery primitives.
- `java-migrations` §9 — restartable backfill jobs for post-restore reconciliation.
