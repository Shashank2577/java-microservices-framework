---
name: java-async-sync-jobs
description: Use when choosing between synchronous and asynchronous execution, when building scheduled/cron jobs, or when implementing fire-and-forget workflows. Covers REST sync, @Async with virtual threads, Spring @Scheduled, ShedLock for distributed locking, Quartz for complex schedules, and idempotency rules for jobs.
---

# Sync, Async & Scheduled Jobs

## 1. Decision Tree — Sync vs Async

```
Does the caller need the answer right now to proceed?
├─ Yes  → Sync (REST or in-process call)
│        - User-facing reads (GET /orders/{id})
│        - Validations the user is waiting on
│        - Operations < ~500ms latency budget
└─ No   → Async
         ├─ Will it run inside the same JVM, short-lived?
         │   └─ @Async (virtual threads) or ApplicationEventPublisher
         ├─ Other services care about the outcome?
         │   └─ Kafka event (via Outbox)
         └─ Periodic, time-based?
             └─ Scheduled job (§4)
```

**Rule:** any external HTTP call inside a `@Transactional` block is a bug. Either move it before/after the TX, or publish an event from the TX (Outbox) and let an async handler do the HTTP.

## 2. Synchronous — REST

### Inbound (controller)
```java
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
class OrderController {
    private final PlaceOrderUseCase placeOrder;
    private final GetOrderUseCase getOrder;

    @PostMapping
    ResponseEntity<OrderResponse> place(
        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
        @Valid @RequestBody PlaceOrderRequest req) {
        var id = placeOrder.place(req.toCommand(idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/orders/" + id)).body(OrderResponse.of(id));
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable UUID id) { return getOrder.byId(new OrderId(id)); }
}
```
- Every mutating endpoint requires `Idempotency-Key`.
- Validation via `@Valid` + Bean Validation.
- Errors via `@ControllerAdvice` returning RFC 7807 `ProblemDetail`.

### Outbound (HTTP client)
Use Spring's `RestClient` (Spring Boot 3.2+). Apply Resilience4j to every external dep.
```java
@Bean PricingClient pricingClient(RestClient.Builder b, PricingProps p) {
    return RestClient.builder()
        .baseUrl(p.baseUrl())
        .requestInterceptor(traceContextPropagator())
        .build();
}
@CircuitBreaker(name="pricing")
@Retry(name="pricing")
@TimeLimiter(name="pricing")
public CompletableFuture<Quote> quote(Order o) { ... }
```

## 3. Asynchronous — In-process

### `@Async` (with virtual threads)
```java
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean(name = "applicationTaskExecutor")
    AsyncTaskExecutor asyncExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}

@Service
class EmailSender {
    @Async
    public void sendWelcome(UserId id) { ... }   // returns immediately; runs on a virtual thread
}
```
- `@Async` calls **must** be on a bean method called from *another* bean (proxy gotcha).
- Don't `@Async` anything that needs the request's `SecurityContext` or `TenantContext` without explicit propagation — use `TaskDecorator`.

### `ApplicationEventPublisher` (in-JVM observer)
Use for **in-process** decoupling only. Listeners run synchronously by default; mark `@Async` to detach.

**Never** rely on `ApplicationEventPublisher` for cross-service signaling. That's Kafka + Outbox (see `java-messaging` and `java-patterns-microservices`).

### `CompletableFuture` for parallel fan-out
```java
var quoteF  = CompletableFuture.supplyAsync(() -> pricing.quote(o), vtExecutor);
var taxF    = CompletableFuture.supplyAsync(() -> tax.calc(o), vtExecutor);
var shipF   = CompletableFuture.supplyAsync(() -> shipping.quote(o), vtExecutor);
var combined = CompletableFuture.allOf(quoteF, taxF, shipF).join();
```

## 4. Scheduled / Cron Jobs

### Pick the Right Tool

| Tool                    | Use For                                                                     |
| ----------------------- | --------------------------------------------------------------------------- |
| `@Scheduled`            | Simple recurring tasks, single instance, no persistence of schedule/state.  |
| `@Scheduled` + **ShedLock** | Same as above, multiple service replicas — one runs at a time.           |
| **Quartz**              | Persistent jobs, misfire policies, dynamic schedules, per-tenant schedules. |
| **Kafka + delayed retry**| Job that's really an event with backoff (`retry-after` semantics).         |

### `@Scheduled` with ShedLock (the default)
```java
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
class SchedulingConfig {
    @Bean LockProvider lockProvider(DataSource ds) {
        return new JdbcTemplateLockProvider(ds, "shedlock");   // table created via Flyway
    }
}

@Service
class OutboxPublisher {
    @Scheduled(fixedDelay = 1000)         // every 1s after previous finishes
    @SchedulerLock(name = "outboxPublisher", lockAtLeastFor = "PT1S", lockAtMostFor = "PT30S")
    public void publishPending() { ... }
}
```
Flyway migration for ShedLock:
```sql
CREATE TABLE shedlock(
  name        VARCHAR(64)  PRIMARY KEY,
  lock_until  TIMESTAMPTZ  NOT NULL,
  locked_at   TIMESTAMPTZ  NOT NULL,
  locked_by   VARCHAR(255) NOT NULL
);
```

### Cron Expression Format (Spring uses 6 fields)
```
┌───────────── second (0-59)
│ ┌───────────── minute (0-59)
│ │ ┌───────────── hour (0-23)
│ │ │ ┌───────────── day of month (1-31)
│ │ │ │ ┌───────────── month (1-12 or JAN-DEC)
│ │ │ │ │ ┌───────────── day of week (0-7 or SUN-SAT, 0 and 7 both = SUN)
* * * * * *
```
- Always set the timezone explicitly: `@Scheduled(cron = "${app.jobs.cleanup.cron}", zone = "UTC")`.
- Drive cron strings from config so ops can change them without redeploy.

### Quartz — When You Need It
Use when you need:
- Persisted jobs surviving restarts.
- Dynamic schedule changes from the running app.
- Misfire handling (what to do if the app was down at fire time).
- Per-tenant schedules.

```java
@Configuration
class QuartzConfig {
    @Bean JobDetail reconcileJob() {
        return JobBuilder.newJob(ReconcileJob.class)
            .withIdentity("reconcile").storeDurably().build();
    }
    @Bean Trigger reconcileTrigger(JobDetail reconcileJob) {
        return TriggerBuilder.newTrigger().forJob(reconcileJob)
            .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?").inTimeZone(TimeZone.getTimeZone("UTC")))
            .build();
    }
}
```

## 5. Distributed Locks & Idempotency

### Decision matrix — which lock to use

| Mechanism | When | Strengths | Weaknesses |
| --- | --- | --- | --- |
| Postgres advisory lock (`pg_advisory_lock` / `pg_try_advisory_xact_lock`) | Default. Lock scoped to existing Postgres connection, no extra infra | TX-scoped option, free with our DB, observable in `pg_locks` | Lock keys are int8; map UUIDs via hashing |
| ShedLock (JDBC) | Single-flight `@Scheduled` jobs across replicas | Tiny lib, table-based, declarative | Coarse — one named lock per job; not for fine-grained per-resource |
| Redis Redlock / RLock (Redisson) | High-throughput, low-latency, non-DB-scoped resources | Fast | Adds Redis dep; correctness debates around Redlock — use Redisson's `FairLock` with care |
| App-level synchronization (`synchronized`, `ReentrantLock`) | In-JVM only | Trivial | Useless across replicas |

**Framework default:** Postgres advisory locks for per-resource serialization, ShedLock for cron single-flight. Redis only when measurably faster.

### Postgres advisory locks — patterns

**Pattern A: Transaction-scoped advisory lock for per-resource serialization**
```java
@Transactional
public void processOrder(OrderId id) {
    jdbc.execute("SELECT pg_advisory_xact_lock(?)", lockKey(id));   // released on commit/rollback
    // safe: only one tx for this order at a time
}
private long lockKey(OrderId id) { return (long) id.value().hashCode() << 32 | someNamespace; }
```
Include a small namespace prefix to avoid collisions across different lock domains.

**Pattern B: Session-scoped lock for non-tx work**
```java
try (var conn = ds.getConnection()) {
    boolean acquired = conn.createStatement().executeQuery("SELECT pg_try_advisory_lock(" + key + ")").next();
    if (!acquired) return;
    try { /* work */ } finally {
        conn.createStatement().execute("SELECT pg_advisory_unlock(" + key + ")");
    }
}
```

Warnings:
- `pg_advisory_lock` (session-scoped) leaks the lock if the connection dies before unlock — prefer `pg_advisory_xact_lock` when possible.
- Pool connections + locks together can deadlock — keep critical sections small.

### Idempotency Key Table

```sql
CREATE TABLE idempotency_keys (
    key             VARCHAR(128) NOT NULL,
    tenant_id       UUID         NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT          NOT NULL,
    response_body   BYTEA        NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, key)
);
CREATE INDEX ix_idem_expires ON idempotency_keys(expires_at);
```

Algorithm at the controller / use-case:
1. Compute `request_hash = sha256(canonicalJson(body))`.
2. `INSERT ... ON CONFLICT DO NOTHING` returning the existing row.
3. If insert succeeded (new key) → process; on success update `response_status` + `response_body`.
4. If conflict (existing key) → compare `request_hash`:
   - Same → return stored response with `Idempotency-Replayed: true` header.
   - Different → return 422 with `IDEMPOTENCY_KEY_CONFLICT`.
5. Background job purges rows past `expires_at`.

Cross-link to `java-api-design` §8 (idempotency-key contract) and `java-api-errors` §7 (idempotency error response).

### Work-queue semantics

When jobs are events on Kafka rather than `@Scheduled`:
- Consumer dedupes by message ID in the inbox table (link to `java-messaging` skill).
- For per-aggregate serialization while processing concurrent events, combine: Kafka partition key = aggregate id (gives ordering) + advisory lock per aggregate inside the handler (defensive belt against re-balances).

### Pre-Merge Checklist (small)
- [ ] Distributed locks chosen with a reason (default: Postgres advisory)
- [ ] Critical section bounded; no external HTTP inside a held lock
- [ ] Idempotency table TTL set; purge job in place
- [ ] Inbox dedup wired for any Kafka consumer

## 6. Job Design Rules

1. **Idempotent.** Re-running the same job for the same window must produce the same result. Use a `job_runs(name, window_start, window_end, status, started_at, finished_at)` table to track windows.
2. **Bounded.** Process in batches with explicit page size; don't `findAll()`.
3. **Resumable.** Persist progress (last processed ID, last cursor). On restart, continue from there.
4. **Observable.** Each run emits structured logs (`job.start`, `job.batch`, `job.end`) + Micrometer timer + count metric.
5. **Tenant-aware.** For per-tenant jobs, iterate tenants explicitly; set `TenantContext` for each tenant's work; never assume default tenant.
6. **Failure-aware.** Catch, log, increment `job.failed.total{job=...}`, alert. Never let an exception kill the scheduler thread.
7. **Single-flight in production.** With multiple replicas: ShedLock (single instance) or Quartz with clustered JobStore.

## 7. Spring Batch & Partitioned Processing

### When to reach for Spring Batch
- `@Scheduled` + chunking in app code = fine for one-off recurring jobs (see §4-§6).
- **Spring Batch** when:
  - Multi-step ETL with persisted job state (resume from where it stopped).
  - Operational monitoring needed (Spring Batch Admin / built-in REST view).
  - Need parallel partitions across machines/threads.
  - Need restartability semantics that survive crashes (last commit point recorded in BATCH_* tables).

If a job is single-step and < 1 hour, prefer the chunked `CommandLineRunner` pattern (see `java-migrations` §9). Use Batch when you need the lifecycle machinery.

### Setup
- `spring-boot-starter-batch` + a small Flyway migration for BATCH_* tables (or `spring.batch.jdbc.initialize-schema=always` in dev only — never in prod).
- Disable auto-startup: `spring.batch.job.enabled=false`. Trigger jobs explicitly via REST or `@Scheduled`.

### Job structure — concrete
```java
@Configuration
@RequiredArgsConstructor
class MonthlyInvoiceJobConfig {
    private final JobRepository jobRepository;
    private final PlatformTransactionManager tx;
    private final InvoiceItemReader reader;
    private final InvoiceProcessor processor;
    private final InvoiceWriter writer;

    @Bean
    Step generateStep() {
        return new StepBuilder("generate-invoices", jobRepository)
            .<RawCharge, Invoice>chunk(200, tx)
            .reader(reader).processor(processor).writer(writer)
            .faultTolerant().skipPolicy(new MaxAttemptsSkipPolicy(5))
            .listener(new MetricsStepListener())
            .build();
    }

    @Bean
    Job monthlyInvoiceJob(Step generateStep, Step closeStep) {
        return new JobBuilder("monthly-invoice", jobRepository)
            .start(generateStep).next(closeStep).build();
    }
}
```
- Chunk size = 100-1000; tune for memory + DB round-trips.
- `faultTolerant` + skip policy = bad rows skipped, not job-aborting (logged + metric).
- Listeners emit step lifecycle events to Micrometer / structured logs.

### Reader / Writer patterns
- Reader: `JdbcPagingItemReader` for cursor-stable DB reads; `JpaPagingItemReader` only when entity hydration needed; `FlatFileItemReader` for CSV (stream-based, low memory). **Default: JDBC paging.**
- Writer: `JdbcBatchItemWriter` for bulk writes. Outbox writes from a batch step go in the same chunk transaction (per `java-messaging` outbox pattern). **Never write to Kafka directly from a batch step** — outbox publisher handles it.

### Partitioning — parallel by tenant
```java
@Bean
Step partitionedStep(Step workerStep) {
    return new StepBuilder("partitioned-generate", jobRepository)
        .partitioner("workerStep", tenantPartitioner())
        .step(workerStep)
        .taskExecutor(virtualThreadExecutor())
        .gridSize(8)
        .build();
}

@Bean
Partitioner tenantPartitioner() {
    return gridSize -> tenantRegistry.allActive().stream()
        .collect(Collectors.toMap(
            t -> "tenant-" + t.slug(),
            t -> {
                var ec = new ExecutionContext();
                ec.put("tenantId", t.id().toString());
                return ec;
            }));
}
```
- One partition per tenant; worker step picks up its tenant from `ExecutionContext`.
- Worker step sets `TenantContext` before processing via a `@BeforeStep` listener.
- Tenant fairness: order by last-run-time or by data-size estimate.

### Multi-tenant safety
- Every step boundary sets `TenantContext` from partition metadata.
- Cross-tenant data movement in a single step = bug; ArchUnit can't enforce, but add an explicit check.

### Restartability
- `RestartParameters`: jobs identified by params (e.g., `month=2026-05`). Re-running with same params resumes; different params = new run.
- Avoid timestamps in params (they make every run "new"); use logical period.
- Idempotent step writes: processor produces stable output for same input.

### Observability
- `spring_batch_step_seconds{step,status}` — built-in.
- Custom: `batch_chunk_processed_total{job,step}`, `batch_skips_total{job,step,reason}`.
- One INFO log per chunk commit (not per item).
- Alert: job duration > 2× historical p95, skip count > threshold.

### Operator interface
- Admin endpoint to launch a job: `POST /admin/batch/{jobName}/launch?params=...`. Auth-gated, audit-logged.
- List recent runs with status, parameters, exit code, duration.
- Stop endpoint that signals graceful shutdown.

### Distributed (multi-node) batch
- For very large jobs, use Spring Cloud Task + Spring Cloud Data Flow OR partitioned step with remote workers (RabbitMQ / Kafka as the partition channel).
- Don't reach for this until single-JVM partitioning isn't enough.

### When NOT to use Spring Batch
- One-off DB backfill: use `java-migrations` §9 pattern (lighter, no BATCH_* tables required).
- High-throughput event stream: that's Kafka + a consumer, not Batch.
- Real-time inference / pipelines: use messaging, not Batch.

### Pre-merge checklist
- [ ] Job has logical parameters (no current timestamp)
- [ ] Steps are restartable
- [ ] Tenant context set at each partition boundary
- [ ] Outbox used for any side-effect events
- [ ] Metrics + alerting wired
- [ ] BATCH_* tables provisioned via Flyway

## 8. Inbound Async — Kafka Consumer
See `java-messaging` skill. Key points:
- Manual ack mode.
- Dedupe via inbox.
- Errors → DLT after N attempts.
- Backpressure: `max.poll.records` tuned to processing time.

## 9. Anti-patterns — Refuse

- `Thread.sleep()` in business code.
- New raw threads (`new Thread(...)`) outside test scaffolding.
- `@Async` on a method called via `this.foo()` from the same class (proxy doesn't apply).
- `@Scheduled` running on every replica without ShedLock (means N duplicate runs).
- A job that reads "everything since the beginning of time" each tick (must use a cursor / time window).
- HTTP / Kafka publish inside a `@Transactional` block (use Outbox).
- A `try { ... } catch(Exception e) { /* swallow */ }` in a scheduled method (kills observability).
- Holding an advisory lock across an HTTP call or other slow I/O.
- Idempotency table with no `expires_at` or no purge job (table grows forever).
- `Thread.sleep()` / blocking wait inside a held lock — keep critical sections tight.
- Redis Redlock without a fencing token / lock TTL (split-brain on GC pauses or network partitions).
- Using `pg_advisory_lock` (session-scoped) when `pg_advisory_xact_lock` would do — leaks on connection death.
- Spring Batch for a < 1 min job (overkill).
- `JpaPagingItemReader` on millions of rows (slow + hydrates entities).
- Writing to Kafka directly from a Batch step (use outbox).
- Job parameters that include current timestamp (no restartability).
- Partition keys ignoring tenant (cross-tenant work in one step).
- `spring.batch.jdbc.initialize-schema=always` in prod.
- Skipping all errors silently (no metric / no DLT row).

## References

- Spring Batch reference docs
- `.claude/skills/lib/jabrena/125-java-concurrency/references/125-java-concurrency.md` — threading concerns in partitioned steps
