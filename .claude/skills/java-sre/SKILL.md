---
name: java-sre
description: Use whenever defining SLIs/SLOs, designing alerts, building runbooks, planning on-call rotations, running incidents, scheduling chaos experiments or DR drills, or doing capacity planning. The Site Reliability Engineering posture — error budgets, multi-window multi-burn-rate alerts, PagerDuty integration, blameless post-mortems, AWS FIS chaos, service maturity scoring. Complements [java-observability] (telemetry plumbing) and [java-ops-dr-runbooks] (DR mechanics).
---

# Site Reliability Engineering — Production Discipline

Reliability is not "monitoring." It is a quantified contract with the customer, alerted on the rate of broken promises, paged to a human, post-mortemed, and improved. This skill is the framework's SRE posture.

Grounded in Google's SRE book (Beyer, Jones, Petoff, Murphy — particularly the chapters on SLOs and alerting on burn rate). Adapted for ASG Edge Plus on AWS EKS.

## 1. SLI / SLO / Error Budget

| Term | Definition | Example |
| ---- | ---------- | ------- |
| **SLI** | Service Level Indicator — a measurement of one quality attribute | "% of `GET /contracts/:id` calls returning `2xx` within 500ms over a rolling 28d" |
| **SLO** | Service Level Objective — the target value of an SLI | "≥ 99.9%" |
| **Error Budget** | `1 - SLO` over the window | 0.1% of 28d = **40 minutes 19 seconds** |
| **Burn Rate** | How fast the budget is being consumed relative to the window | 14.4× burn = 1-hour budget exhausted in 5 minutes |

The error budget is **spendable**. Burn it on feature velocity. When it's exhausted, the freeze in §11 kicks in.

## 2. Three SLOs Every Service Must Declare

Every service declares **at least three** SLIs and their SLO targets:

1. **Latency SLI** — `p99 < target` for representative endpoints / topics.
2. **Availability SLI** — `success_rate > target` where success = `2xx` or `3xx` (HTTP) or `acked` (Kafka consume).
3. **Freshness SLI** (where applicable) — time from event source to processed result. Example: `ASC842` recomputes complete within 4h of the underlying lease event.

Some services add:
- **Correctness SLI** — % of expected balanced/reconciled outputs (accounting services). Hub-and-spoke per [ADR-007] makes this measurable.
- **Durability SLI** — % of events successfully outboxed + delivered.

## 3. SLO Tiers

Every service is assigned a tier. The tier sets the SLO target.

| Tier | Availability | p99 Latency | Error budget / month | Examples |
| ---- | ------------ | ----------- | -------------------- | -------- |
| **Tier-1** | 99.9% | 500ms | 43 min 12s | APIGateway, AuthenticationService, AccountingEngine hub, ContractLeaseService |
| **Tier-2** | 99.5% | 1.5s | 3h 36min | ASC842 spoke, IFRS16 spoke, NotificationService, DocumentService |
| **Tier-3** | 99.0% | 5s | 7h 12min | ReportingService, internal admin tools, batch jobs |

Tier assignment is governed by the platform team; changes require an RFC.

## 4. `slo.yaml` — Single Source of Truth

Every service checks in `slo.yaml` at repo root. This file drives:
- Prometheus alerting rules (rendered by CI into Helm chart values).
- The Backstage service catalog ([java-code-quality] §13).
- The SLO dashboard (Managed Grafana).
- The on-call routing in PagerDuty.

```yaml
# services/contract-service/slo.yaml
service: contract-service
tier: 1
owner_team: team-contract
pagerduty_service_key: PXXXXXX
slos:
  - name: api_availability
    sli:
      type: ratio
      good: 'sum(rate(http_server_requests_seconds_count{service="contract-service",status=~"2..|3.."}[5m]))'
      total: 'sum(rate(http_server_requests_seconds_count{service="contract-service"}[5m]))'
    target: 0.999
    window: 28d
  - name: api_latency_p99
    sli:
      type: latency_quantile
      metric: http_server_requests_seconds
      quantile: 0.99
      filter: 'service="contract-service",endpoint=~"/api/v1/contracts.*"'
    target_seconds: 0.5
    window: 28d
  - name: outbox_freshness
    sli:
      type: histogram_quantile
      metric: outbox_publish_lag_seconds
      quantile: 0.95
      filter: 'service="contract-service"'
    target_seconds: 30
    window: 7d
```

CI lints the file (schema + Prometheus query validity), renders alerts, and fails the build if `slo.yaml` is missing or invalid.

## 5. Alert Design — Burn Rate, Not Thresholds

**Refuse to alert on raw values** ("p99 > 500ms"). They are noisy and don't track customer impact. Alert on **how fast we are burning the budget**.

We use Google's **multi-window multi-burn-rate** alerts. Two pages, one ticket:

| Severity | Burn rate × | Short window | Long window | Budget consumed | Action |
| -------- | ----------- | ------------ | ----------- | --------------- | ------ |
| **Page (fast)** | 14.4 | 5 min | 1 h | 2% in 1h | Wake up on-call |
| **Page (slow)** | 6 | 30 min | 6 h | 5% in 6h | Page on-call (less urgent) |
| **Ticket** | 1 | 6 h | 3 d | 10% in 3d | Open a ticket for next business day |

### PromQL — fast page for Tier-1 availability

```promql
(
  (
    sum(rate(http_server_requests_seconds_count{service="contract-service",status!~"2..|3.."}[5m]))
    /
    sum(rate(http_server_requests_seconds_count{service="contract-service"}[5m]))
  ) > (14.4 * 0.001)
  AND
  (
    sum(rate(http_server_requests_seconds_count{service="contract-service",status!~"2..|3.."}[1h]))
    /
    sum(rate(http_server_requests_seconds_count{service="contract-service"}[1h]))
  ) > (14.4 * 0.001)
)
```

The `AND` of short + long windows is **the** trick — fast detect, low false-positive.

### Rendered into Alertmanager

```yaml
groups:
  - name: contract-service.slo
    rules:
      - alert: ContractServiceAvailabilityFastBurn
        expr: <PromQL above>
        for: 2m
        labels:
          severity: page
          slo: api_availability
          service: contract-service
          runbook: https://backstage/runbooks/contract-service/api-availability-fast-burn
        annotations:
          summary: "Fast burn on contract-service availability — 1-hour budget at risk in 5 minutes"
```

### Page → PagerDuty

Alertmanager → PagerDuty integration → service-specific routing → on-call schedule. Every alert label `service` maps to a PagerDuty service key (sourced from `slo.yaml`).

## 6. Runbooks — Every Alert, No Exceptions

Every alert MUST link to a runbook. CI fails if an alert is added without a runbook URL.

```
services/contract-service/runbooks/
├── api-availability-fast-burn.md
├── api-latency-p99-burn.md
├── outbox-freshness-burn.md
└── kafka-consumer-lag.md
```

### Runbook Template

```markdown
# Runbook: <alert name>

**Severity:** Page (SEV-2)
**Owning team:** team-contract
**On-call:** PagerDuty ContractService

## Symptom
What does the customer see? What does the dashboard show?

## Dashboard
- [Service SLO Dashboard](https://grafana/asgedge/contract-service-slo)
- [Service Golden Signals](https://grafana/asgedge/contract-service-rate-error-duration)
- [Kafka lag](https://grafana/asgedge/kafka-consumer-lag?service=contract-service)
- [DB performance insights](aws-console-link)

## Immediate Triage (5 min)
1. Confirm scope — single tenant or all? (Grafana SLO panel split by `tenant_id` label.)
2. Recent deploy? Check ArgoCD / GitHub Actions in the last 60 min.
3. Database healthy? RDS Performance Insights: any blocking queries / wait events?
4. Kafka lag spike? Check consumer group.

## Common Causes
| Cause | Evidence | Fix |
| ----- | -------- | --- |
| Deploy regression | Correlates with deploy timestamp | Rollback via ArgoCD (see [java-container-deploy]) |
| Noisy neighbor tenant | One tenant dominates request volume | Apply per-tenant rate limit at gateway |
| Outbox stuck | `outbox_publish_lag_seconds` rising | See `outbox-freshness-burn.md` |
| DB connection pool exhausted | `hikaricp.connections.pending` > 0 | Restart pod; investigate slow query |

## Escalation
- 10 min, no progress → escalate to platform on-call
- 30 min, still SEV-2 → declare SEV-1, page Incident Commander

## After Incident
- Post-mortem template ([java-sre] §8)
- Update this runbook with what you learned
```

Runbooks are checked into the service repo. The link in the alert points at the GitHub-rendered version (or Backstage TechDocs).

> See [java-ops-dr-runbooks] for **DR-specific** runbooks (cross-region restore, RDS failover, MSK MM2 cutover). [java-sre] runbooks cover steady-state operations; DR runbooks cover catastrophic events.

## 7. On-Call Rotation

| Element | Convention |
| ------- | ---------- |
| Tool | PagerDuty |
| Schedule | 7-day rotation per service domain (e.g., `contract-team-oncall`, `accounting-team-oncall`, `platform-team-oncall`) |
| Coverage | Follow-the-sun where team is multi-region; otherwise primary + secondary in same TZ |
| Escalation | L1 (primary) → 5 min → L2 (secondary) → 10 min → L3 (manager/IC) |
| Handoff | Friday 09:00 local: outgoing pages incoming, runs the open-incidents/open-tickets review |

> **Open question:** specific PagerDuty schedule structure (single rotating schedule vs domain-paired schedules) depends on team org and headcount. To be set per team in Backstage `catalog-info.yaml`.

**On-call expectations:**
- Acknowledge within 5 min.
- Have a laptop, VPN access, AWS SSO logged in, kubectl context set to prod.
- Read the runbook before acting.
- File a ticket for every page, even if self-resolving.

## 8. Incident Response

### Severity Definitions

| SEV | Definition | Examples |
| --- | ---------- | -------- |
| **SEV-1** | Customer-impacting; multi-tenant or critical-tenant outage; data integrity at risk; revenue loss | API gateway down; AccountingEngine producing wrong balances; tenant data leak |
| **SEV-2** | Degraded service; subset of tenants or non-critical functionality; auto-recovery uncertain | Single tenant slow; one report failing; Kafka consumer lag |
| **SEV-3** | Internal; no immediate customer impact | Drift detected; dev-env outage; SLO burn but budget OK |

### Roles (SEV-1 / SEV-2)

| Role | Responsibility |
| ---- | -------------- |
| **Incident Commander (IC)** | Owns the incident. Coordinates, decides, escalates. Does NOT touch keyboards. |
| **Tech Lead** | Drives technical investigation. Calls out hypotheses + actions. |
| **Communications Lead** | Status page updates; customer comms; internal Slack updates every 15 min. |
| **Scribe** | Timeline + decisions in the Slack channel; feeds post-mortem. |

For SEV-1: all roles staffed by separate humans. For SEV-2: IC may also be Tech Lead.

### Status Page

We use [statuspage.io] (or AWS-hosted equivalent — TBD). IC owns status updates:
- First update within 5 min of declaration.
- Subsequent updates every 15 min until resolved.
- Post-incident update within 24h.

### Slack Channel

Auto-created on incident declaration: `#inc-YYYYMMDD-<short-slug>`. Bot pulls in IC, on-calls for affected services, and posts status-page links + dashboard links.

## 9. Post-Mortems — Blameless, Five-Whys

**Every SEV-1 and SEV-2 gets a post-mortem within 5 business days.** SEV-3 optional but encouraged.

### Template (checked in to `incidents/YYYY-MM-DD-<slug>.md`)

```markdown
# Incident: <title>

- Date / time: 2026-06-12 14:32 UTC
- Severity: SEV-1
- Duration: 47 minutes
- Customer impact: 14% of API requests failed for tenants `acme`, `warby`
- Services: contract-service, api-gateway
- Authored by: <IC>
- Reviewed by: <peers>

## Summary
<2-3 sentences. What happened, what we did, what the impact was.>

## Timeline
- 14:32 — Alert `ContractServiceAvailabilityFastBurn` fires.
- 14:33 — On-call acks. Reviews dashboard.
- 14:38 — Tech Lead hypothesizes recent deploy (14:25). Rollback initiated.
- 14:45 — Rollback complete. Error rate begins falling.
- 15:01 — Error rate normalized. Status page: investigating → resolved.
- 15:19 — Incident closed.

## Root Cause (Five Whys)
1. Why did the API return 500s? — NullPointerException in `ContractValidator`.
2. Why was there an NPE? — New nullable field on the request DTO.
3. Why wasn't it caught in test? — Test fixture used the old DTO.
4. Why didn't the contract test catch it? — Contract spec lagged the DTO.
5. Why did the contract spec lag? — No CI step enforces DTO ↔ contract parity.

## Action Items
| # | Owner | Due | Description |
| - | ----- | --- | ----------- |
| 1 | @alice | 2026-06-19 | Add CI check: DTO field count == contract field count. |
| 2 | @bob | 2026-06-26 | Backfill contract tests for `ContractValidator` null cases. |
| 3 | @carol | 2026-06-19 | Update `contract-service/runbooks/...` with this signature. |

## What Went Well
- Rollback time < 7 min from alert.
- Status page updated promptly.

## What Could Have Gone Better
- Alert fired but on-call was investigating an unrelated ticket; ack took 1 min longer than ideal.

## Lessons Learned (Permanent)
- DTO changes require contract-test sync; tooling will enforce.
```

**Blameless rule:** the post-mortem names systems, processes, and gaps — never individuals. "On-call missed it" → "the alert routing did not differentiate this signal from background noise."

## 10. Chaos Engineering — AWS FIS

Per [aws-platform] §4 chaos engineering is a quarterly minimum, run via AWS Fault Injection Service. Plan and run experiments **on schedule, in production-like environments first, then prod with traffic-shift safety nets**.

Standard experiments (start dev → staging → prod, with go/no-go review at each transition):

| Experiment | Frequency | Action |
| ---------- | --------- | ------ |
| Random pod kill | Weekly in dev, monthly in staging, quarterly in prod | `aws-fis-actions:eks-pod-cpu-stress` + `terminate-pod` |
| Latency injection on inter-service calls | Monthly | `iam-roles:eks-inject-latency` 200ms on chosen service mesh edge |
| RDS failover drill | Quarterly | `aws:rds:failover-db-cluster` on a non-prod env, observe behavior |
| AZ blackhole | Quarterly | Network ACL deny on one AZ subnet for 10 min, observe routing |
| Kafka broker termination | Quarterly | Terminate one MSK broker, observe consumer recovery |
| Region failover | Annually | Full DR drill per [java-ops-dr-runbooks] |

**Every experiment:**
- Has a hypothesis ("if we kill a pod, traffic re-routes within 30s and SLO is unaffected").
- Has a stop condition (CloudWatch alarm that auto-aborts the experiment).
- Produces a report.

A failed hypothesis is the most valuable outcome — fix the gap.

## 11. Error Budget Policy

When a service's 28-day error budget exhausts:

| Budget remaining | Status | Policy |
| ---------------- | ------ | ------ |
| > 50% | Green | Normal velocity. |
| 25–50% | Yellow | All deploys require deploy-gate ack. Increase canary duration to 1h. |
| < 25% | Amber | Non-critical changes require team lead approval. Focus shifts to reliability work. |
| < 0% (exhausted) | **Red** | **Freeze non-critical changes** until budget recovers. Only fixes for the SLI driving the burn. SRE + service team meet daily until green. |

Budget status is automated — Grafana dashboard + a daily Slack post per service to the team channel.

## 12. Capacity Planning

Quarterly review, per service. Inputs:

- HPA min/max replicas (currently sized; project 1 quarter ahead at expected traffic growth).
- KEDA min/max for Kafka-driven scaling — projected lag at peak.
- RDS:
  - Storage growth rate (Performance Insights + `pg_database_size`).
  - Connection count vs HikariCP pool size vs RDS `max_connections`.
  - IOPS headroom on gp3.
- MSK:
  - Partition count per topic vs producer/consumer parallelism.
  - Disk usage per broker.
- EKS:
  - Karpenter node-hours vs CloudWatch budget.
  - Per-namespace ResourceQuota headroom.

Outputs:
- Update `slo.yaml` if SLO will tighten/loosen at scale.
- Open Terraform PRs for capacity increases (RDS class, MSK brokers, MSK partitions).
- Update FinOps forecast.

## 13. Cost as a Reliability Dimension

A runaway autoscaler is a reliability failure. We treat **cost anomaly = potential reliability issue**.

- **AWS Cost Anomaly Detection** monitor type `DIMENSIONAL` keyed on `TAG:Component` ([aws-platform] §20).
- Anomalies > $200/day on any one component → **PagerDuty ticket** (SEV-3).
- Daily anomalies > $1000/day on prod → **PagerDuty page** (SEV-2).
- Suspicious pattern: cost spike with no traffic spike → autoscaler runaway, scale-out bug, or zombie resource.

Runbook: `runbooks/_platform/cost-anomaly.md` — first action is `kubectl top pods` + Karpenter status, not "ignore until tomorrow."

## 14. Post-Incident Metrics

Tracked quarterly per service in the Backstage catalog:

| Metric | Definition |
| ------ | ---------- |
| **MTTD** | Mean Time To Detect — alert fire time − incident start time |
| **MTTR** | Mean Time To Recover — incident resolved time − alert fire time |
| **MTBF** | Mean Time Between Failures — between SEV-1/SEV-2 incidents |
| **Incident count** | By severity, by service |
| **Action item closure rate** | % of post-mortem action items closed by their due date (target ≥ 80%) |

Tracked in a dashboard; reviewed at monthly platform-ops meeting.

## 15. Service Maturity Score

Every service has a "production readiness" score, evaluated quarterly. Green / Amber / Red on each line; service overall = lowest line.

| Check | Green | Amber | Red |
| ----- | ----- | ----- | --- |
| `slo.yaml` exists + valid | All three SLOs declared | One missing | None / invalid |
| Runbook per alert | Every alert links to a runbook | 90%+ | < 90% |
| Post-mortem on every SEV-1/2 in last quarter | Yes | 90%+ | < 90% |
| DR drill in last quarter | Yes | Last drill within 6 months | Older |
| Chaos test in last quarter | Yes | Within 6 months | Older |
| On-call rotation staffed | ≥ 4 humans on the rotation | 3 | < 3 |
| Observability complete | Logs + metrics + traces + golden dashboards | One missing | Multiple missing |
| ArchUnit + quality gates passing | All clean | Known waiver | Failing |
| Backstage `catalog-info.yaml` accurate | Yes | Drift | Missing |
| Action-item closure ≥ 80% | Yes | 50–80% | < 50% |

Backstage shows the score per service. Sales/Customer Success can see "Tier-1 services in Green" at a glance. **A red service is blocked from onboarding a new Tier-1 tenant until amber.**

## 16. Cross-References

- [java-observability] — what gets emitted; how MDC + traceId carry through; OpenTelemetry config (the *plumbing* of telemetry)
- [java-ops-dr-runbooks] — DR-specific runbooks: cross-region restore, RDS failover mechanics, MSK MM2 cutover, RPO/RTO measurement
- [aws-platform] — Managed Prometheus, Managed Grafana, X-Ray, FIS, Cost Anomaly Detection
- [java-code-quality] — Backstage catalog where the service maturity score and `slo.yaml` are surfaced
- [java-container-deploy] — graceful shutdown, probes, PDB (what reliability looks like in the manifest)
- [java-iac-terraform] — drift detection feeds the alert pipeline
- [aws-cognito] — Cognito regional-outage failure mode + accepted RTO
- [java-messaging] — Kafka SLIs (consumer lag, outbox publish lag)

## 17. Anti-patterns — Refuse

- Alerting on raw thresholds. Always burn-rate.
- Pages without runbooks. Block the merge.
- Post-mortems that name individuals. Blameless or revise it.
- "We'll write the SLO after the service is live." No — `slo.yaml` is a gate.
- Skipping chaos experiments because "we're too busy." That's exactly when they're needed.
- Letting error budget recovery take a back seat to features when budget is exhausted.
- Treating cost anomalies as "the finance team's problem." Reliability problem.
- One-person on-call rotations. Single point of human failure.
- Ignoring SEV-3s. They become SEV-1s in 6 months.
- Hard-coding pager numbers in alerts. Routing via `slo.yaml` only.
- DR drills only in DR env. The point is to exercise the cross-region cutover; that requires touching real prod-equivalent infra.
- "Quarterly chaos" that's been postponed twice this year. The schedule is the schedule.

## 18. Reference

- Beyer/Jones/Petoff/Murphy — *Site Reliability Engineering* (Google), free at https://sre.google/sre-book/
- Beyer et al. — *The Site Reliability Workbook*, https://sre.google/workbook/
- [Google's multi-window multi-burn-rate alerts (SRE Workbook ch.5)](https://sre.google/workbook/alerting-on-slos/)
- [AWS Fault Injection Service](https://docs.aws.amazon.com/fis/)
- [PagerDuty incident response docs](https://response.pagerduty.com/)
- [Prometheus alerting best practices](https://prometheus.io/docs/practices/alerting/)
