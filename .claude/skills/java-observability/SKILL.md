---
name: java-observability
description: Use whenever adding logging, metrics, traces, or thinking about SLOs/alerts. The three pillars (logs, metrics, traces) wired concretely — Logback JSON + MDC keys, Micrometer + Prometheus + RED/USE metric naming, OpenTelemetry tracing across HTTP + Kafka, structured log fields, sensitive-data redaction, and SLO/alert conventions.
---

# Observability — Three Pillars, Wired

You will not debug production with `println`. The framework wires logs, metrics, and traces from day one with **consistent fields** so an incident is a query, not a 6-hour grep party.

## 1. Mandatory MDC Keys

Every log line in every service carries these keys (set by filters/interceptors; never by business code):

| Key             | Source                                              | Example               |
| --------------- | --------------------------------------------------- | --------------------- |
| `trace_id`      | OpenTelemetry — auto                                | `8af6d2c0a3b1...`     |
| `span_id`       | OpenTelemetry — auto                                | `e1d4c8...`           |
| `tenant_id`     | `TenantContext` (see `java-multi-tenancy`)          | `0d8b…`               |
| `user_id`       | JWT `sub`, if request authenticated                 | `usr_42`              |
| `request_id`    | `X-Request-Id` header or generated                  | `req_…`               |
| `correlation_id`| `X-Correlation-Id` header — same value across hops  | `corr_…`              |
| `service`       | `spring.application.name`                           | `orders-svc`          |

Async / virtual threads: use `TaskDecorator` to copy MDC. Kafka consumers: re-populate MDC from message headers before invoking the listener.

## 2. Logging — Logback JSON to stdout

`logback-spring.xml`:
```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>trace_id</includeMdcKeyName>
            <includeMdcKeyName>span_id</includeMdcKeyName>
            <includeMdcKeyName>tenant_id</includeMdcKeyName>
            <includeMdcKeyName>user_id</includeMdcKeyName>
            <includeMdcKeyName>request_id</includeMdcKeyName>
            <includeMdcKeyName>correlation_id</includeMdcKeyName>
            <includeMdcKeyName>service</includeMdcKeyName>
            <customFields>{"env":"${SPRING_PROFILES_ACTIVE:-local}"}</customFields>
            <fieldNames>
                <timestamp>ts</timestamp>
                <level>level</level>
                <thread>thread</thread>
                <logger>logger</logger>
                <message>msg</message>
                <stackTrace>stack</stackTrace>
            </fieldNames>
        </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="STDOUT"/></root>
    <logger name="org.springframework"      level="WARN"/>
    <logger name="org.hibernate.SQL"        level="WARN"/>
    <logger name="com.example"              level="INFO"/>
</configuration>
```

Never write log files. Stdout is the contract; the platform (Loki/ELK) does aggregation.

### Log levels — policy

| Level | Use for                                                                              |
| ----- | ------------------------------------------------------------------------------------ |
| ERROR | Unexpected exception; downstream failure that breaks the request; 5xx surface.       |
| WARN  | Recoverable failure; validation error; retry happened; degraded mode.                |
| INFO  | Business events: order placed, payment captured, tenant provisioned. **One per event, not one per step.** |
| DEBUG | Verbose plumbing; turn on per-class to investigate. Off in prod by default.          |
| TRACE | Reserved for tight-loop debugging. Never enabled in shared env.                      |

### Logging code rules

- Parameterized only: `log.info("placed order={} customer={}", orderId, customerId)`. **Never** string concat.
- Pass throwable **last** for the stack trace: `log.error("kafka send failed topic={}", topic, ex);`.
- Log each error **once**, at the boundary that handles it. Catch + log + rethrow = duplicate stack traces.
- One INFO per business event, structured fields, not freeform sentences.
- **Never** log: passwords, tokens, full credit cards, full SSN, raw request bodies that may contain PII.

### Redaction

A `LoggingMask` utility for known-sensitive fields:
```java
log.info("user_signup email={} ip={}", mask.email(req.email()), mask.ip(req.ip()));
// "j***@example.com", "203.0.113.x"
```
Wire a Logback `MessageConverter` for blanket redaction patterns (16-digit cards, AWS keys, etc.) as a safety net.

## 3. Metrics — Micrometer + Prometheus

`pom`/`build.gradle`: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`. `application.yml`:
```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.prometheus.enabled: true
  metrics.tags:
    service: ${spring.application.name}
    env: ${SPRING_PROFILES_ACTIVE:local}
```

### Naming convention — `<noun>_<noun>_<unit>_<suffix>`

| Pattern                                   | Example                                                  |
| ----------------------------------------- | -------------------------------------------------------- |
| `<domain>_<event>_total`                  | `orders_placed_total`, `payments_failed_total`           |
| `<domain>_<event>_duration_seconds`       | `orders_persist_duration_seconds` (histogram)            |
| `<resource>_<state>_count`                | `outbox_pending_count` (gauge)                           |
| `http_server_requests_*`                  | provided by Spring; tags `method`, `uri`, `status`, `outcome` |
| `kafka_consumer_lag_records`              | per topic/partition                                      |

Always include `tenant_id` as a label only when cardinality is bounded (≤ a few hundred). For huge tenant sets, use `tenant_class` (small/medium/large) instead — labels with thousands of values blow up Prometheus.

### What to measure

| Area     | Method | Metrics                                                              |
| -------- | ------ | -------------------------------------------------------------------- |
| HTTP     | **RED** | Rate (req/s), Errors (5xx %), Duration (p50/p95/p99)                |
| Kafka    | **RED** | Throughput, error rate, consumer lag                                |
| DB pool  | **USE** | Utilization (active/total), Saturation (waiting), Errors           |
| JVM      | **USE** | CPU, heap, GC pauses, threads                                       |
| Business | custom | `orders_placed_total{tenant_class=...}`, `payments_failed_total{reason=...}` |

### Custom metric

```java
@Service
@RequiredArgsConstructor
class OrderMetrics {
    private final MeterRegistry registry;
    private final Counter placed = Counter.builder("orders_placed_total")
        .description("Total orders placed").register(registry);
    public void recordPlaced(Order o) {
        placed.increment();        // optionally tag with tenant_class
    }
}
```

Do **not** use `Timer.builder().tag("orderId", id)` — that's high-cardinality death.

## 4. Tracing — OpenTelemetry

The Spring Boot OTel starter auto-instruments HTTP, Kafka, JDBC, and Resilience4j. Manual spans for business operations:

```java
@Autowired Tracer tracer;

public void handle(PlaceOrderCommand cmd) {
    var span = tracer.spanBuilder("place_order")
        .setAttribute("tenant_id", cmd.tenant().toString())
        .setAttribute("customer_id", cmd.customer().toString())
        .startSpan();
    try (var scope = span.makeCurrent()) {
        ...
    } catch (Throwable t) {
        span.recordException(t); span.setStatus(StatusCode.ERROR);
        throw t;
    } finally {
        span.end();
    }
}
```

### Trace propagation

- HTTP: W3C `traceparent` — handled by the OTel starter.
- Kafka: `traceparent` in headers — handled by the OTel Kafka instrumentation. Verify in tests that the consumer span is a child of the producer span.
- Async (@Async, ThreadPool): use `Context.taskWrapping(executor)` from the OTel API.

### Sampling
- Prod default: parent-based + 10% root sampling. Adjust per service based on traffic.
- Always sample errors (rule-based sampling).

## 5. Distributed Logs Correlation

Three IDs that travel with the request and let you join logs, traces, and metrics:

- `trace_id` — same across all services for one user-initiated transaction.
- `correlation_id` — caller-provided (or generated at the edge); useful for human-readable correlation across systems that don't share trace context (e.g., webhook deliveries).
- `request_id` — unique per HTTP request inside a single service.

The edge service generates `correlation_id` if absent and propagates it via header + Kafka header.

## 6. SLOs and Alerting

Each service ships with:
- An `slo.yaml` describing the SLIs (latency p95, success ratio) and SLO targets per endpoint or domain.
- Alert rules-as-code in the repo (Prometheus rules, derived from SLO):
  - **Page** on burn-rate alerts (multi-window: 1h fast burn AND 6h slow burn).
  - **Ticket** on infrequent symptoms (DLT growth, outbox lag > threshold, dependency-check critical CVE).

Default SLOs for a customer-facing service:
- Availability ≥ 99.9% (≤ 43 min/month error budget).
- Latency p95 < 500ms, p99 < 1.5s.

## 7. Actuator Endpoints — What to Expose

| Endpoint                  | Exposure                                                            |
| ------------------------- | ------------------------------------------------------------------- |
| `health` / `health/liveness` / `health/readiness` | Public (load balancer).                     |
| `info`                    | Public.                                                             |
| `prometheus`              | **Cluster-internal only** (NetworkPolicy or `management.server.port` on a separate non-exposed port). |
| `heapdump`, `threaddump`, `env`, `mappings`, `configprops` | Disabled, or admin-only with strong auth. Never internet-exposed. |

## 8. Load Testing & Capacity Planning

Load tests are SLO-driven, not vibes-driven. A pass means the SLO held at target RPS. Never run "to see what happens" — always with a hypothesis.

### Toolchain

| Tool | Use |
| ---- | --- |
| **k6** (default) | Scripted JS, CI-friendly, Prometheus output. Framework default. |
| Gatling | Scala/Java DSL. Choose if the team prefers Scala. |
| JMeter | Mature, GUI-heavy. Legacy; not recommended for new work. |
| Locust | Python-friendly. Good for non-trivial scenario distributions. |

### Test types every service has

| Type     | Goal                                  | Cadence    |
| -------- | ------------------------------------- | ---------- |
| Smoke    | <1 min sanity check on every PR       | per PR     |
| Baseline | 5–15 min steady state at expected RPS | nightly    |
| Soak     | 1–4 h at expected load                | weekly     |
| Stress   | Ramp until break                      | quarterly  |
| Spike    | Sudden ramp to 5–10× normal           | quarterly  |

### k6 script structure

```js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const placeOrderLatency = new Trend('place_order_latency_ms');

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: 200,            // 200 RPS
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 50,
    },
  },
  thresholds: {
    'http_req_duration{name:POST /v1/orders}': ['p(95)<500', 'p(99)<1500'],
    'http_req_failed': ['rate<0.005'],
  },
};

export default function () {
  const res = http.post(__ENV.BASE_URL + '/v1/orders', /* body */, {
    headers: {
      'Authorization': `Bearer ${__ENV.JWT}`,
      'X-Tenant-Id': __ENV.TENANT,
      'Idempotency-Key': crypto.randomUUID(),
    },
    tags: { name: 'POST /v1/orders' },
  });
  check(res, { 'is 201': r => r.status === 201 });
  placeOrderLatency.add(res.timings.duration);
  sleep(0.1);
}
```

Threshold-driven (failed threshold = build fails). Tag requests per endpoint for per-route metrics. Use a real JWT against a load-test tenant; **don't disable auth**.

### Test environment

- A dedicated load-test env mirroring prod topology (k8s resources, DB sizing, Kafka cluster size). Not "prod with a /loadtest path."
- Same Postgres major version, Kafka version, JVM flags.
- Synthetic data seeded ahead of the run, not generated during it.

### What to measure

| Metric                              | Target                          |
| ----------------------------------- | ------------------------------- |
| p50 / p95 / p99 latency per endpoint | matches SLO                    |
| Error rate                          | < SLO budget allowance          |
| Throughput (RPS sustained)          | meets capacity goal             |
| JVM heap usage                      | stable, no growth               |
| GC pause time                       | within budget (10ms for ZGC)    |
| DB connection pool wait             | < 5ms                           |
| Kafka consumer lag                  | flat                            |
| Outbox publisher lag                | flat                            |

### Capacity planning workflow

1. **Measure** peak production RPS from `http_server_requests_seconds_count`.
2. **Headroom**: target 50% utilization at peak (= 2× peak capacity available).
3. **Per-pod capacity**: from load test, find max sustainable RPS per pod at SLO.
4. **Pod count** = `ceil(peak / per-pod) × headroom`.
5. **Re-evaluate** quarterly or after architecture changes.

### Per-tenant capacity

- Tenant-shaped load test: simulate top-tier tenant working set; verify a single tenant doesn't starve the platform.
- Noisy-neighbor scenario: 99 small tenants + 1 large. Verify the rate limiter holds.

### CI integration

- Smoke = `./gradlew loadTestSmoke` runs k6 against the just-deployed preview env. PR fails if smoke fails.
- Baseline = nightly GitHub Action against staging.
- Soak / stress = manual trigger or weekly schedule.

### Observability of the load test itself

Stream k6 → Prometheus → Grafana dashboard with: RPS, latency percentiles, errors — alongside the service's own RED metrics. Same dashboard for prod and load test; only the time range / source differs.

### What load tests don't catch

- Race conditions under specific concurrency timing — use stress/chaos.
- Data growth issues — use a large-data soak (seed 10× current prod volume).
- Hot-tenant scenarios — see per-tenant section above.

## 9. Anti-patterns — Refuse

- `System.out.println` in any code path.
- Logging strings with concatenation (`+`).
- High-cardinality metric labels (`orderId`, `email`, full URL with IDs).
- Logging at INFO inside a tight loop (1 log per business event, not per row).
- Catching to log and re-throw (double-logs); pick one.
- Trace context lost across `CompletableFuture.supplyAsync(...)` — use OTel context wrapping.
- Heapdump / env exposed without auth.
- Dashboard with no SLO; alert with no runbook.
- Load tests without thresholds (just generating data, no pass/fail).
- Running load tests in production without isolation.
- Disabling auth/rate-limit "to test the service in isolation."
- Synthetic data that doesn't mirror production cardinality.
- Reading load-test results without comparing to SLO.

## 10. Pre-Merge Checklist

- [ ] No `println` / `System.out`.
- [ ] New business events emit one INFO log (structured) and one metric.
- [ ] New errors logged at ERROR with stack trace, **once**.
- [ ] Tenant ID present in MDC for any tenant-scoped path.
- [ ] No high-cardinality labels introduced.
- [ ] If endpoint is new, OpenAPI + alert rule + SLO updated.

## 11. Reference (deep dive)

- `.claude/skills/lib/jabrena/181-java-observability-logging/references/181-java-observability-logging.md` — SLF4J + Logback patterns, parameterized logging, redaction
- `.claude/skills/lib/jabrena/182-java-observability-metrics-micrometer/references/182-java-observability-metrics-micrometer.md` — Micrometer registry, counter/gauge/timer, naming
- `.claude/skills/lib/jabrena/183-observability-tracing-opentelemetry/references/183-observability-tracing-opentelemetry.md` — OTel SDK, span attributes, context propagation
