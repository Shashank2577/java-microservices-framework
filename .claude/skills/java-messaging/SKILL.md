---
name: java-messaging
description: Use whenever publishing or consuming Kafka events, designing a topic, choosing a schema, implementing the transactional outbox, or handling consumer failures and DLTs. Covers topic naming, Avro schema evolution, producer/consumer config, the Outbox + Inbox patterns, and dead-letter handling.
---

# Messaging — Kafka

## 1. Topic Naming

`<bounded-context>.<aggregate>.<event-or-command>.v<n>`

Examples:
- `orders.order.placed.v1`
- `orders.order.cancelled.v1`
- `billing.invoice.issued.v1`
- `inventory.commands.reserve-stock.v1`

Rules:
- Lowercase, dash-separated within segments, dots between segments.
- Always include a `.v<n>` suffix. Breaking change → new topic `.v2`; old topic deprecated, not deleted, until consumers migrate.
- `events` vs `commands` namespace: events are facts (past tense, "placed"); commands are requests (imperative, "reserve-stock").

## 2. Partitioning

- **Partition key = aggregate id.** This guarantees per-aggregate ordering ("all events for order X in order").
- Tenant id alone is **not** a good partition key — it creates hot partitions for big tenants. If you must group, use `tenant_id|aggregate_id`.
- Default partition count: 12 (allows 12-way consumer parallelism). Don't change partition count after launch without re-partitioning all consumers.

## 3. Schema — Avro + Schema Registry

- Schemas in `src/main/avro/`. Generated code goes to `build/generated-main-avro-java/`.
- Compatibility mode: **BACKWARD** by default. New consumers can read old data.
- Evolution rules: add optional fields with defaults; never rename, never remove, never narrow types.

```json
{
  "namespace": "com.example.orders.events",
  "type": "record",
  "name": "OrderPlaced",
  "fields": [
    {"name": "event_id", "type": {"type": "string", "logicalType": "uuid"}},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-micros"}},
    {"name": "tenant_id", "type": {"type": "string", "logicalType": "uuid"}},
    {"name": "order_id",  "type": {"type": "string", "logicalType": "uuid"}},
    {"name": "customer_id","type": {"type": "string", "logicalType": "uuid"}},
    {"name": "total",     "type": {"type": "bytes", "logicalType": "decimal", "precision": 19, "scale": 4}},
    {"name": "currency",  "type": "string"}
  ]
}
```

Every event carries: `event_id` (UUID, used for dedup), `occurred_at`, `tenant_id`.

## 4. Standard Headers

Set on every record:
- `X-Tenant-Id` — tenant UUID.
- `X-Correlation-Id` — trace correlation across services.
- `X-Source-Service` — the producing service name.
- `traceparent` — W3C trace context (added by Micrometer Tracing).
- `content-type` — `application/avro` (or `application/json` for ad-hoc).

## 5. Producer Configuration

```yaml
spring.kafka.producer:
  acks: all
  enable-idempotence: true                  # no duplicates on retry
  retries: 2147483647
  properties:
    max.in.flight.requests.per.connection: 5
    delivery.timeout.ms: 120000
    linger.ms: 5
    compression.type: zstd
  key-serializer: org.apache.kafka.common.serialization.StringSerializer
  value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
```
- **Never** publish to Kafka from inside a DB transaction — use the **Outbox** (§7).
- Producer is **transactional** only if you genuinely need exactly-once semantics across topics; default is idempotent (good enough for most cases).

## 6. Consumer Configuration

```yaml
spring.kafka.consumer:
  group-id: ${spring.application.name}
  auto-offset-reset: earliest
  enable-auto-commit: false                # we ack manually
  max-poll-records: 100
  key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
  value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
  properties:
    specific.avro.reader: true
    isolation.level: read_committed
spring.kafka.listener:
  ack-mode: manual
  concurrency: 4
```

```java
@KafkaListener(topics = "orders.order.placed.v1", groupId = "${spring.application.name}")
public void onOrderPlaced(ConsumerRecord<String, OrderPlaced> r, Acknowledgment ack) {
    var event = r.value();
    if (inbox.alreadyProcessed(event.getEventId())) { ack.acknowledge(); return; }
    TenantContext.callWith(tenantFrom(r), () -> {
        useCase.handle(event);
        inbox.markProcessed(event.getEventId(), r.topic(), r.partition(), r.offset());
        return null;
    });
    ack.acknowledge();
}
```

## 7. Transactional Outbox — Mandatory

Any DB write that produces an event uses an outbox row written in the same transaction. A scheduled publisher ships outbox rows to Kafka.

### Schema (per service)
```sql
CREATE TABLE outbox (
  id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  topic         VARCHAR(255) NOT NULL,
  key           VARCHAR(255) NOT NULL,
  headers       JSONB        NOT NULL DEFAULT '{}'::jsonb,
  payload       BYTEA        NOT NULL,                  -- Avro bytes
  aggregate_id  UUID         NOT NULL,
  tenant_id     UUID         NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  sent_at       TIMESTAMPTZ  NULL,
  attempts      INT          NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_pending ON outbox(created_at) WHERE sent_at IS NULL;
```

### Publish path (use-case)
```java
@Transactional
public OrderId place(PlaceOrderCommand cmd) {
    var order = ...;
    orderRepo.save(order);
    outbox.append("orders.order.placed.v1", order.id().toString(), serialize(toAvro(order)));
    return order.id();
}
```

### Publisher (scheduled)
```java
@Scheduled(fixedDelay = 500)
@SchedulerLock(name = "outbox-" + spring.application.name)
public void flush() {
    List<OutboxRow> batch = outboxRepo.fetchPending(100);   // FOR UPDATE SKIP LOCKED
    for (var row : batch) {
        try {
            kafka.send(toRecord(row)).get(5, SECONDS);
            outboxRepo.markSent(row.id());
        } catch (Exception e) {
            outboxRepo.incrementAttempts(row.id());
            if (row.attempts() >= 5) outboxRepo.parkInDlt(row.id());
        }
    }
}
```
`SELECT ... FOR UPDATE SKIP LOCKED` makes the publisher safe with multiple replicas (in addition to ShedLock).

For higher volume, replace polling with **Debezium CDC** on the outbox table.

## 8. Inbox (Idempotent Consumer)

```sql
CREATE TABLE processed_events (
  event_id    UUID         PRIMARY KEY,
  topic       VARCHAR(255) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
- Consumer first inserts into `processed_events` (UNIQUE violation = duplicate → ack and return).
- Then runs the business logic in the **same transaction**.

## 9. Dead-Letter Topics

Spring Kafka built-in retry + DLT:
```java
@Bean
DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template,
        (rec, ex) -> new TopicPartition(rec.topic() + ".dlt", rec.partition()));
    var handler = new DefaultErrorHandler(recoverer,
        new ExponentialBackOffWithMaxRetries(5));        // 5 retries, exponential
    handler.addNotRetryableExceptions(IllegalArgumentException.class);  // bad payloads → straight to DLT
    return handler;
}
```
- Alert on DLT lag/growth.
- Provide a **redrive endpoint**: read DLT, re-publish to original topic with `x-redriven=true` header.
- Track redrive count to avoid loops.

## 10. CDC & Warehouse Handoff

### 10.1 Debezium as an outbox publisher (alternative to polling)

When the polling outbox publisher (§7) can't keep up — high volume, or you want minimal latency from DB commit to Kafka — use **Debezium** to stream changes out of the outbox table directly into Kafka.

- Setup: Debezium connector on Kafka Connect, configured against the Postgres logical replication slot, filtered to the `outbox` table only.
- Postgres requires `wal_level=logical` and replication slots — coordinate with ops; this is not a free config flip on managed Postgres (RDS/Aurora/CloudSQL need parameter-group changes + reboot).
- The connector publishes one Kafka record per `outbox` row inserted, with the destination topic taken from a `topic` column (Debezium's **Outbox Event Router** SMT).
- Align the `outbox` table with what the SMT expects: `id`, `aggregate_id`, `aggregate_type`, `type`, `payload`. Our §7 schema already has `id`, `aggregate_id`, `topic`, `payload`; add `aggregate_type` and `type` columns to satisfy the SMT.
- Mark rows as sent? Debezium doesn't need `sent_at` — it tracks LSN itself. Keep rows for short retention (24h) for audit, then purge with a scheduled job. Drop the `attempts`/`sent_at` columns from the polling design when switching.

```properties
# debezium-outbox.properties (Kafka Connect)
connector.class=io.debezium.connector.postgresql.PostgresConnector
plugin.name=pgoutput
slot.name=orders_outbox_slot
publication.name=orders_outbox_pub
table.include.list=public.outbox
tombstones.on.delete=false
transforms=outbox
transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
transforms.outbox.route.by.field=topic
transforms.outbox.table.field.event.key=aggregate_id
transforms.outbox.table.field.event.payload=payload
```

Tradeoffs vs polling publisher:

| | Polling publisher (default) | Debezium CDC |
| --- | --- | --- |
| Infra | None new | Kafka Connect + replication slot |
| Latency DB → Kafka | 500ms–1s (poll interval) | <100ms typical |
| Operational complexity | Low | Higher (slot, connector lifecycle, replication lag) |
| Throughput ceiling | ~10k rows/s/replica | Effectively WAL throughput |
| When to switch | Default | Sustained outbox lag > N seconds, or volume > ~10k/s |

### 10.2 Operational topics vs analytics topics

Two different audiences, two different contracts:

- **Operational topics** (`orders.order.placed.v1`) — consumed by other services for business logic. Schema = stable contract. BACKWARD compatibility only. Lifetime measured in years.
- **Analytics / warehouse topics** (`analytics.orders_raw.v1`) — consumed by the data warehouse (Snowflake, BigQuery, Redshift). Denormalized, wide, evolves more freely under producer control. Lifetime measured in months between schema generations.

**Rule: the data team does NOT consume operational topics directly.** Reasons:
- Operational events are partial ("placed" without final state) — confusing for analytics.
- Coupling operational schema to analytics breaks both teams every time either side changes.
- The DW wants wide denormalized rows; operations want narrow events.

### 10.3 The warehouse handoff pattern

Three legal patterns; in order of preference:

1. **CDC of operational tables → warehouse staging** (no Kafka topic in between, or a `cdc.*` namespace).
   - Debezium connector publishes table changes to a Kafka Connect sink for the warehouse, or to `cdc.<schema>.<table>` topics that the warehouse loader reads.
   - Best for: raw operational data into a Bronze/Raw layer. The warehouse owns Silver/Gold transforms downstream.

2. **Service publishes explicit analytics events to `analytics.*` topics.**
   - The service decides what analytics needs — curated, wide events.
   - Best for: domain-meaningful events (e.g., `analytics.order_lifecycle.v1` aggregates placed/paid/shipped into one record).
   - More producer work but cleaner schemas for analysts.

3. **Periodic snapshot exports.**
   - Batch nightly export of selected tables to S3/GCS as Parquet.
   - Best for: slowly-changing dimensions (customers, products).
   - Avoid for facts (transactions) — use CDC.

**Forbidden:**
- The warehouse reading directly from the operational Postgres replica. Tight coupling, blast radius on every schema change, bad operational hygiene.
- The data team subscribing to operational topics meant for service-to-service communication.

### 10.4 Schema evolution at the warehouse boundary

- Analytics topics: producer-driven evolution; communicate breaking changes via deprecation period (dual-write `analytics.orders.v1` and `analytics.orders.v2` for 30 days).
- Schema registry: a separate subject namespace (`analytics.*`) so registry policies can differ — operational stays BACKWARD, analytics may allow FORWARD compatibility.
- Field naming for the warehouse: `snake_case`, fully qualified, no domain abbreviations (warehouse readers don't know your shorthand).

### 10.5 Tenant data isolation in the warehouse

- Every analytics event carries `tenant_id`.
- The warehouse loader partitions tables by `tenant_id`; row-level security in the warehouse enforces tenant-scoped queries.
- Data-residency rules: per-tenant region pinning at the loader (e.g., EU-only tenants → EU warehouse cluster only).

### 10.6 Observability for the CDC pipeline

- Metrics: Debezium connector replication lag (`debezium_connector_replication_lag_seconds`), outbox-to-Kafka latency p95, sink connector commit lag, replication slot size.
- Alert on: replication slot growth (unbounded → disk fills), connector `FAILED` state, sustained lag > SLO.
- Dashboards: connector status, lag, throughput, error rate — same panel set as consumer lag in §6.

## 11. Anti-patterns — Refuse

- Publishing from inside `@Transactional` directly to `KafkaTemplate` (use Outbox).
- Topic without `.v<n>` suffix.
- Schema changes that break BACKWARD compatibility without a new topic version.
- Consumer that does not dedupe.
- Catching exception and ack-ing without DLT (silent message loss).
- Cross-service synchronous chains where async would do.
- Sharing a Schema Registry subject across two unrelated topics.
- Using Kafka headers for business data — headers are for plumbing (tenant, correlation, trace), payload is for data.
- Data warehouse reading directly from operational Postgres replicas.
- Operational topics consumed by the analytics pipeline.
- Schema Registry shared between operational and analytics namespaces.
- Replication slot left behind after disconnecting a Debezium connector (eats disk fast — drop the slot explicitly).
- Analytics events without `tenant_id`.

## 12. References

- `.claude/skills/lib/jabrena/314-frameworks-spring-kafka/references/314-frameworks-spring-kafka.md` — Spring for Apache Kafka reference notes (producer/consumer config, listener containers, error handlers).
