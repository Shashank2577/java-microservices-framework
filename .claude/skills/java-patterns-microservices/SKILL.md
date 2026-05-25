---
name: java-patterns-microservices
description: Use when designing service boundaries, inter-service communication, distributed data consistency, deployment strategies, or resilience policies. Covers decomposition, data (Saga, CQRS, Outbox, Inbox), communication (API Gateway, BFF, discovery), reliability (Circuit Breaker, Bulkhead, Retry), and deployment (Sidecar, Strangler, Blue-Green, Canary).
---

# Microservices Patterns

## 1. Decomposition

### By Business Capability
Service = one business capability owned by one team (orders, billing, inventory). Capabilities map to org structure (Conway's Law).

### By Subdomain (DDD)
Identify bounded contexts via event storming. Each context → one service. Aggregates inside a context never cross service boundaries.

### Strangler Fig
Migrate from monolith by routing traffic for one capability at a time to a new service. The monolith shrinks; the router (gateway) is the constant.

## 2. Data Management

### Database per Service
**Mandatory.** No shared schemas, no cross-service JOINs in SQL.

### Shared Database — Anti-pattern
Only acceptable as a transient state during a Strangler migration. Mark it explicitly with a deprecation date.

### Saga
Manages a transaction that spans services. Two flavors:

**Choreography** — each service reacts to events and publishes its own.
- Pros: no central coordinator, loose coupling.
- Cons: hard to see the whole flow; circular dependencies easy.
- Use for: <4 steps, stable flows.

**Orchestration** — a coordinator service issues commands and tracks state.
- Pros: explicit flow, easy to debug, easy to add steps.
- Cons: coordinator is a focal point.
- Use for: ≥4 steps, complex compensations.
- Implement with a state machine (e.g., Spring Statemachine) + a `saga_instance` table.

**Every saga step needs a compensating action.** If charge succeeds but ship fails, refund.

### CQRS
Separate write model (commands, domain entities, normalized) from read model (queries, denormalized projections).
- Use when read patterns diverge sharply from the write model.
- **Don't reach for it by default** — it doubles the data model.
- Read model updated via events; tolerate eventual consistency in UIs (show "pending").

### Event Sourcing
Persist state as an append-only event stream; rebuild current state by replaying.
- Use for: audit-heavy domains (finance, legal), domains where history *is* the data.
- **Don't** combine ES + CQRS reflexively — each adds complexity.

### Transactional Outbox
**Mandatory** any time a service writes to its DB and must publish an event.
```
@Transactional
public void placeOrder(Order o) {
    orderRepo.save(o);
    outbox.append("orders.order.placed.v1", o.id(), payload);  // same TX
}
// A separate publisher reads `outbox` and ships to Kafka, marks rows as sent.
```
Implementations: poll the table on a schedule, or use Debezium CDC on the outbox table.

### Inbox (Idempotent Consumer)
Dedupe by message ID in a `processed_events(event_id PK, processed_at)` table; insert before processing, swallow duplicates.

### Read Model / Materialized View
Subscribe to events from other services; build a local denormalized view for your queries. Tolerate staleness; surface it.

### API Composition
Aggregator service or BFF calls multiple services to build a composite response. Acceptable for read paths; never for writes (use a saga).

## 3. Communication

### Synchronous — REST
- Use for queries and user-facing reads.
- Always: timeout, retry-with-jitter, circuit breaker.
- Never: long chains of synchronous calls (A → B → C → D). Each hop multiplies failure probability and latency.

### Synchronous — gRPC
Use when you control both sides, want schema-first contracts, and need lower latency / streaming. Heavier tooling.

### Asynchronous — Kafka
Default for inter-service events. See `java-messaging` skill.

### API Gateway
Single entry point for clients. Handles auth, rate limiting, request routing, response composition. Spring Cloud Gateway is the default.

### BFF (Backend for Frontend)
A gateway per client type (web BFF, mobile BFF). Each owns its composition logic and DTO shapes. Use when clients differ enough that one gateway becomes a god-object.

### Service Discovery
- **Server-side**: kubernetes Services + DNS — the platform handles it.
- **Client-side**: Eureka/Consul — only when not on k8s.
Default to platform-provided discovery; don't bring Eureka into a k8s cluster.

### Service Mesh
Istio / Linkerd for mTLS, traffic policies, observability, retries — without app code changes. Use at ≥10 services or when security/compliance demands mTLS everywhere.

## 4. Reliability (Resilience4j defaults)

### Circuit Breaker
Trip when failure rate exceeds threshold (e.g., 50% over 20 calls); open for cooldown (e.g., 30s); half-open to probe.

### Retry with Jitter
Exponential backoff + random jitter to avoid thundering herd. Only retry **idempotent** operations. Default: 3 attempts, base 200ms, jitter 50%.

### Timeout
**Every** outbound call has a timeout. Default: 2s for REST, 5s for DB queries, configurable per call.

### Bulkhead
Isolate resources by caller so one slow downstream can't drain your thread pool. With virtual threads this is less critical, but semaphore bulkheads still bound concurrency.

### Rate Limiter
At the gateway, per client/tenant/route. Local rate limiters inside services for outbound calls.

### Fallback
Cached value, default response, or graceful degradation. **Never** swallow errors silently — log + metric + fallback.

### Dead Letter Topic
Failed Kafka messages → `<topic>.dlt` after N retries. Alert on DLT growth. Provide a redrive endpoint.

```java
@CircuitBreaker(name = "pricing", fallbackMethod = "fallbackQuote")
@Retry(name = "pricing")
@TimeLimiter(name = "pricing")
public CompletableFuture<Quote> quote(Order o) { ... }
private CompletableFuture<Quote> fallbackQuote(Order o, Throwable t) { ... }
```

## 5. Observability

### Health Check API
- `GET /actuator/health/liveness` — process is up (always 200 unless deadlocked).
- `GET /actuator/health/readiness` — dependencies OK; pull from load balancer if not.
- Custom health indicators for tenant DB pool, Kafka producer, downstream services.

### Distributed Tracing
Micrometer Tracing → OpenTelemetry exporter → Tempo/Jaeger. Trace context propagates over HTTP headers (`traceparent`) and Kafka headers (`b3`/`traceparent`).

### Log Aggregation
Structured JSON logs to stdout. One log line per business event with `trace_id`, `tenant_id`, `correlation_id`.

### Application Metrics
- RED metrics per endpoint: Rate, Errors, Duration.
- Domain metrics: `orders.placed.total`, `payments.failed.total{reason=...}`.
- SLO dashboards per service.

## 6. Security

### Access Token (JWT)
- Signed by IdP, validated at the gateway and at each service.
- Carries: `sub`, `tid` (tenant), `roles`, `scopes`, `exp`.
- Services do **not** issue tokens; they validate.

### Token Exchange
Service-to-service calls use a service-account token (client credentials), or propagate the user token if acting on user's behalf.

### mTLS
Service mesh handles it. Otherwise: TLS at the gateway, network-policy isolation inside the cluster.

### Secrets
Vault / k8s Secrets via env vars. Never in Git. Pre-commit `gitleaks` hook required.

## 7. Deployment

### Sidecar
Co-located helper (log shipper, config reloader, mesh proxy). Keep sidecar use **narrow** — it's not a place for business logic.

### Blue-Green
Two prod environments; flip traffic. Zero downtime, easy rollback. Cost: 2× capacity during switch.

### Canary / Rolling
Route a slice of traffic to the new version, scale up if metrics stay green. Use feature flags + per-tenant routing for finer control.

### Configuration Service
Spring Cloud Config or k8s ConfigMap + Reloader. **Never** require restart for non-secret config changes that ops will routinely make.

## 8. HTTP Client Patterns

### 8.1 Client choice
| Client      | Verdict                                                                                       |
| ----------- | --------------------------------------------------------------------------------------------- |
| `RestClient` | **Default.** Spring 6.1+, blocking, fluent, virtual-thread-friendly. Replaces `RestTemplate`. |
| `WebClient` | Reactive only. Use **only** if the call site is already reactive end-to-end.                  |
| Feign       | Legacy. Avoid for new code — extra abstraction, weaker observability, slower evolution.       |
| `RestTemplate` | Deprecated for new code. Migrate.                                                          |

Default to `RestClient` + virtual threads. Reactive is a stack, not a feature — don't mix.

### 8.2 Resilience4j composition order
Wrap **outside-in** in this order:
```
Bulkhead → TimeLimiter → CircuitBreaker → Retry → targetMethod
```
- **Retry inside CircuitBreaker** is wrong: each retry counts as a separate call and will trip the CB on a single bad downstream blip.
- **TimeLimiter outside Retry** bounds *total* time across attempts; if it were inside, each attempt could hit the per-call timeout and total latency explodes.
- **Bulkhead outermost** so saturation rejects fast before consuming the other primitives.

```java
@Bulkhead(name = "pricing", type = Bulkhead.Type.SEMAPHORE)
@TimeLimiter(name = "pricing")
@CircuitBreaker(name = "pricing", fallbackMethod = "fallbackQuote")
@Retry(name = "pricing")
public CompletableFuture<Quote> quote(QuoteRequest r) { ... }
```

### 8.3 Timeouts — never infinite
| Setting        | Default |
| -------------- | ------- |
| connectTimeout | 1s      |
| readTimeout    | 2s      |
| total (TimeLimiter) | 5s |

Tunable per route via `@ConfigurationProperties("http.clients.<name>")`. No call leaves the JVM without a timeout.

### 8.4 Connection pool sizing
Use Apache HttpClient 5 (or Jetty client) under `RestClient`. Rule of thumb:
```
maxPerRoute = ceil(peak_RPS × avg_latency_seconds × safety_factor)
```
Example: 200 RPS × 0.150s × 1.5 = **45** connections per route. `maxTotal` ≈ Σ per-route + headroom.

### 8.5 Trace propagation
`traceparent` header propagates automatically when `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-*` are on the classpath. **Verify** the `ObservationRestClientCustomizer` is registered (it is by default with `spring-boot-starter-actuator`). Drop a test asserting the header appears on outbound calls.

### 8.6 Tenant header propagation
Every outbound call adds `X-Tenant-Id` from `TenantContext`:
```java
@Bean
ClientHttpRequestInterceptor tenantInterceptor() {
    return (req, body, exec) -> {
        String tid = TenantContext.currentTenantId();
        if (tid != null) req.getHeaders().add("X-Tenant-Id", tid);
        return exec.execute(req, body);
    };
}
```
Register on the `RestClient.Builder` bean. Pair with a downstream `OncePerRequestFilter` that reads it back into `TenantContext`.

### 8.7 Idempotency on outbound POST
Partner APIs (payments, shipping, LLM-completion-with-side-effect): generate an idempotency key per *logical* operation, persist it with the request, and **reuse it on retry**. Header is usually `Idempotency-Key: <uuid>`. Without this, Retry × non-idempotent POST = duplicate charges.

### 8.8 JSON config
- Inbound: `FAIL_ON_UNKNOWN_PROPERTIES = false` — tolerate additive schema changes.
- Outbound: `JsonInclude.Include.NON_NULL` — keep payloads tight, avoid `null` semantics.
- Dates: ISO-8601 (`yyyy-MM-dd'T'HH:mm:ss.SSSXXX`). `JavaTimeModule` registered.

### 8.9 Mock servers in tests
WireMock for outbound HTTP contracts and failure injection. See `.claude/skills/lib/jabrena/702-technologies-wiremock/references/702-technologies-wiremock.md`. Cover happy path, 5xx, slow response, and connection reset per client.

### 8.10 Example: `PricingClient`
```java
@Component
@RequiredArgsConstructor
public class PricingClient {
    private final RestClient restClient; // built with builder + tenant interceptor

    @Bulkhead(name = "pricing", type = Bulkhead.Type.SEMAPHORE)
    @TimeLimiter(name = "pricing")
    @CircuitBreaker(name = "pricing", fallbackMethod = "fallbackQuote")
    @Retry(name = "pricing")
    public CompletableFuture<Quote> quote(QuoteRequest req) {
        return CompletableFuture.supplyAsync(() ->
            restClient.post()
                .uri("/v1/quotes")
                .header("Idempotency-Key", req.idempotencyKey())
                .body(req)
                .retrieve()
                .body(Quote.class));
    }
    private CompletableFuture<Quote> fallbackQuote(QuoteRequest r, Throwable t) {
        return CompletableFuture.completedFuture(Quote.cachedOrDefault(r));
    }
}
```

### 8.11 Anti-patterns
- **No timeout on outbound calls** — one slow dep hangs every caller thread.
- **Retry wrapped outside CircuitBreaker** — retries punch through the CB and never let it open.
- **Reusing `RestTemplate` for new code** — deprecated direction; use `RestClient`.
- **Per-call `new RestClient`** — defeats the connection pool; build once, inject.
- **Non-idempotent POST + Retry without idempotency key** — guaranteed duplicate side effects.

## 9. LLM Integration Boundary

### 9.1 Vendor abstraction (domain port)
Define a port in your domain; adapters live in infrastructure. Spring AI is fine internally — **isolate it behind your port** so swapping providers is a config change.
```java
public interface LlmClient {
    LlmResult chat(LlmRequest req);            // sync
    Flux<LlmChunk> stream(LlmRequest req);     // streaming (or SseEmitter wrapper)
}
```
Adapters: `OpenAiLlmClient`, `AnthropicLlmClient`, `AzureOpenAiLlmClient`, `BedrockLlmClient`. Selection via `@ConfigurationProperties("llm.provider")`.

### 9.2 Timeouts
LLMs are slow. Defaults:
| Mode          | Total timeout |
| ------------- | ------------- |
| Sync chat     | 60s           |
| Streaming     | 120s (idle-timeout 20s between chunks) |
| Embedding     | 10s           |

Do **not** apply the global 5s HTTP default to LLM clients — configure a dedicated `RestClient` bean.

### 9.3 Cost budget per call
Track tokens and dollars as Micrometer metrics:
- `llm_tokens_in_total{provider, model, tenant_class}`
- `llm_tokens_out_total{provider, model, tenant_class}`
- `llm_cost_usd_total{provider, model, tenant_class}`
- `llm_latency_seconds_bucket{provider, model}`

Alert on burn rate per tenant_class (free / pro / enterprise). Hard-cap per-tenant via Resilience4j `RateLimiter` keyed on `tenantId`.

### 9.4 Logging and redaction
- Log **only**: prompt hash (SHA-256), response hash, `tokens_in`, `tokens_out`, `model`, `latency_ms`, `tenant_id`, `trace_id`.
- **Never** log raw prompt or completion — PII / secrets / regulated data leak this way.
- If a prompt must be persisted for debug (off by default, feature-flagged), redact known fields (`email`, `ssn`, `card`, `phone`, `address`) before write.

### 9.5 Streaming vs sync
| Use case                          | Mode                                |
| --------------------------------- | ----------------------------------- |
| User-facing chat                  | SSE via Spring `SseEmitter` / `Flux<ServerSentEvent>` |
| RAG retrieval + answer            | Sync (composed with retrieval)      |
| Classification / extraction       | Sync                                |
| Background pipeline (summarize N) | Sync + `@Async` or virtual thread   |

Never block a request thread waiting on a non-streaming LLM call. Use virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) or `@Async`.

### 9.6 Fallback policy
Order of preference when the primary model fails or times out:
1. Smaller/cheaper model of the same provider (e.g., `gpt-4o` → `gpt-4o-mini`).
2. Alternate provider adapter (if multi-vendor enabled).
3. Deterministic rule-based response (regex / lookup).
4. Cached "safe" answer from a curated FAQ.
5. **Last resort**: structured error with a user-visible message, never a stack trace.

Never fail silently. Never return raw provider exceptions to the user.

### 9.7 Rate limiting
- **Outbound** (your service → provider): per-tenant, Resilience4j `RateLimiter` keyed on `tenantId`. Prevents one tenant burning the shared API quota.
- **Inbound** (user → your service): per-user / per-API-key at the gateway. Tighter limits on LLM routes than on CRUD.

### 9.8 Idempotency and caching
Identical (model, prompt, tenant) tuples may be cached for a short TTL (60s default; up to 5m for deterministic temperature=0 calls). Cache key **must** include `tenantId` — never share completions across tenants. Bypass cache when prompts contain user-specific context unless that context is part of the key.

### 9.9 Prompt-injection defense
Treat LLM output as **UNTRUSTED**:
- Never `eval` / compile / exec LLM-generated code.
- Never auto-execute LLM-emitted SQL — parameterize or use a strict allow-list of operations.
- Never feed LLM output back into a system prompt without sanitization (strip instructions, ignore-previous patterns, role-switch attempts).
- Validate structured output against a schema (JSON Schema / Bean Validation) before use; reject on mismatch.

### 9.10 RAG note
For retrieval-augmented generation against Postgres, store embeddings in `pgvector` with an HNSW index. Keep retrieval and generation as separate observable spans. See §10 below for the full pattern.

### 9.11 Example: minimal `LlmClient` port + OpenAI adapter
```java
public interface LlmClient {
    LlmResult chat(LlmRequest req);
}

@Component
@RequiredArgsConstructor
public class OpenAiLlmClient implements LlmClient {
    private final RestClient llmRestClient;        // dedicated bean, 60s timeout
    private final MeterRegistry meters;

    @TimeLimiter(name = "llm")
    @CircuitBreaker(name = "llm", fallbackMethod = "fallback")
    public LlmResult chat(LlmRequest req) {
        var resp = llmRestClient.post()
            .uri("/v1/chat/completions")
            .body(OpenAiPayload.from(req))
            .retrieve()
            .body(OpenAiResponse.class);

        meters.counter("llm_tokens_in_total",
            "provider", "openai", "model", req.model()).increment(resp.usage().promptTokens());
        meters.counter("llm_tokens_out_total",
            "provider", "openai", "model", req.model()).increment(resp.usage().completionTokens());

        return LlmResult.of(resp.firstChoice(), resp.usage());
    }

    private LlmResult fallback(LlmRequest req, Throwable t) {
        return LlmResult.cachedOrSafeDefault(req);
    }
}
```

### 9.12 Anti-patterns
- **Logging raw prompts/completions** — PII and secret leakage at scale.
- **Sharing completion cache across tenants** — data exfiltration vector.
- **Auto-executing LLM-emitted SQL or shell** — prompt injection becomes RCE.
- **No per-tenant rate limit on LLM calls** — one tenant exhausts the global quota.
- **Coupling business code to a vendor SDK directly** — swapping providers becomes a rewrite.

## 10. Vector Search & RAG

### 10.1 Vector storage — pgvector first
- **Default vector store: `pgvector` extension in Postgres.** One DB to manage, transactional with business data, schema-per-tenant gives free isolation.
- Switch to a dedicated vector DB (Pinecone, Weaviate, Qdrant, Milvus) **only** when measurements show pgvector can't scale (≥10M vectors per tenant + sub-100ms p95 required).
- Embedding dimension is pinned to the model. OpenAI `text-embedding-3-small` = 1536, BGE-small = 384. Put it in config; don't hardcode at the call site.

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunks (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID         NOT NULL,
    chunk_index   INT          NOT NULL,
    content       TEXT         NOT NULL,
    embedding     vector(1536) NOT NULL,
    metadata      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

-- HNSW for fast ANN; tune m / ef_construction per workload
CREATE INDEX ix_document_chunks_embedding ON document_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

### 10.2 Chunking
- **Token-aware splitter**, not character count. Chunk size 300–800 tokens, overlap 50–100 tokens.
- Preserve structural boundaries (headings, paragraphs, code blocks). Don't split mid-sentence.
- Persist provenance (`document_id`, `chunk_index`, optional `page`, `section`) so the UI can cite sources.
- Re-chunk on document update; **never** patch chunks in place — versions drift.

### 10.3 Ingestion pipeline
Async, never inline with the request:
```
document write → outbox → `document.upserted.v1` Kafka → ingester → chunk → embed → upsert
```
Embedding API calls follow §9 LLM rules (timeout, retry-with-backoff, per-tenant rate limit). Emit `embedding_tokens_total{tenant, model}` as a cost metric.

### 10.4 Retrieval query
```sql
SELECT id, document_id, content, 1 - (embedding <=> :query_embedding) AS score
  FROM document_chunks
 WHERE tenant_id = :tenant
   AND metadata @> :filter
 ORDER BY embedding <=> :query_embedding
 LIMIT :k;
```
- `<=>` cosine distance matches `vector_cosine_ops`.
- `LIMIT` typically 5–20 candidates → optional rerank → top-3 used as prompt context.
- **Pre-filter** (`tenant_id`, `metadata @> ...`) before the vector search. Post-filter wastes work.

### 10.5 Hybrid retrieval (BM25 + vector)
For factual / keyword-heavy queries, combine Postgres FTS (see `java-patterns-database` §9) with vector search. Score-fuse via Reciprocal Rank Fusion (RRF). Adds latency — only enable when measured to help.

### 10.6 Reranking
Optional cross-encoder (BGE-reranker, Cohere Rerank) rescoring the top-K. Adds 100–500ms but lifts precision when candidates look similar. Skip until measured.

### 10.7 Multi-tenancy
- `tenant_id` column + predicate in **every** retrieval query. Indexed.
- A single shared table is fine until you have >1M chunks/tenant or compliance demands per-tenant indexes.
- **Cross-tenant retrieval is a security incident.** Treat it as such.

### 10.8 Prompt construction
Template:
```
[SYSTEM] You answer using only the provided context. Cite sources by [doc_id#chunk].
[CONTEXT]
---
{{chunk_1.content}}  <source: {{chunk_1.document_id}}#{{chunk_1.chunk_index}}>
---
{{chunk_2.content}}  <source: ...>
---
[USER] {{question}}
```
- Cite chunks in the response (`document_id` + `chunk_index`) so the UI renders a link to source.
- If total tokens > model limit, drop lowest-scored chunks until it fits.

### 10.9 Eval and drift
- Curated eval set per tenant or per use case: `(question, expected_sources)`.
- Track: retrieval **recall@k**, answer **faithfulness** (answer reflects only retrieved sources), answer **helpfulness**.
- Run on every ingester change; chart over time.

### 10.10 Caching
- Cache the **query embedding** for repeated user queries (TTL 60s; key includes `tenantId` + `userId`). Saves API calls + latency.
- **Do not cache the LLM response.** Answers are user-specific and context-bound — see §9.8 anti-pattern.

### 10.11 Cost controls
- Per-tenant monthly embedding token budget. Alert at 80%.
- Rate-limit ingestion per tenant so a bulk upload can't drain shared quota.
- Asymmetric models when budget matters: smaller model for ingestion, larger for query.

### 10.12 Anti-patterns
- Vector search without `tenant_id` filter.
- Chunks split by character count, not tokens.
- Patching chunks in place on doc update (versions drift).
- Caching LLM responses (cross-user contamination).
- `IVFFLAT` chosen blindly; **HNSW is the default** for most workloads.
- Embedding the entire document instead of chunks (loses precision).
- Pasting retrieved chunks directly into a system prompt without delimiters (prompt-injection vector).
- Reranking when not measured to help.

### 10.13 Pre-merge checklist
- [ ] `tenant_id` filter on all vector queries
- [ ] Chunks token-aware with overlap
- [ ] HNSW index tuned for the workload
- [ ] Ingestion via outbox → Kafka, not inline
- [ ] Cost metric emitted; budget alerts configured
- [ ] Citation surface in API response

### 10.14 Reference
- [pgvector](https://github.com/pgvector/pgvector) — extension docs, index tuning.
- Lewis et al., "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks" (2020) — original RAG paper.

## 11. Anti-patterns — Refuse

- **Distributed monolith**: services that must deploy together because of tight coupling. Fix by removing shared libs that leak domain types.
- **Chatty interfaces**: 1 user action → 20 service calls. Aggregate at the BFF or denormalize the read model.
- **Synchronous chain of death**: A → B → C → D → E sync. Replace inner hops with events or denormalization.
- **Shared database**: see §2.
- **Distributed transactions (2PC/XA)**: use sagas.
- **Spring events for cross-service**: they don't cross JVMs. Use Kafka + Outbox.
- **Catch-and-ignore**: every catch logs + metric + fallback.
- **No timeout on outbound calls** — one slow dep hangs every caller thread.
- **Retry wrapped outside CircuitBreaker** — retries punch through the CB and never let it open.
- **Reusing `RestTemplate` for new code** — deprecated direction; use `RestClient`.
- **Per-call `new RestClient`** — defeats the connection pool; build once, inject.
- **Non-idempotent POST + Retry without idempotency key** — duplicate side effects guaranteed.
- **Logging raw LLM prompts/completions** — PII and secret leakage at scale.
- **Sharing LLM completion cache across tenants** — data exfiltration vector.
- **Auto-executing LLM-emitted SQL or shell** — prompt injection becomes RCE.
- **No per-tenant rate limit on LLM calls** — one tenant exhausts the global quota.
- **Coupling business code to a vendor LLM SDK directly** — swapping providers becomes a rewrite.
- **Vector search without `tenant_id` filter** — cross-tenant data leak.
- **Character-count chunking** — breaks mid-sentence, kills retrieval quality.
- **`IVFFLAT` chosen blindly over HNSW** — HNSW is the default for most workloads.
- **Embedding whole documents instead of chunks** — loses precision and citations.

## 12. Selection Cheat Sheet

| Problem                                                  | Pattern                                  |
| -------------------------------------------------------- | ---------------------------------------- |
| "Write to DB and publish event"                          | Transactional Outbox                     |
| "Consumer might see this twice"                          | Inbox / Idempotent Consumer              |
| "Multi-step business workflow across services"           | Saga (orchestration preferred ≥4 steps)  |
| "Read patterns differ from write model"                  | CQRS + Materialized View                 |
| "Audit / time-travel is the requirement"                 | Event Sourcing                           |
| "Composite response from many services"                  | API Composition / BFF                    |
| "Downstream flaky"                                       | Circuit Breaker + Retry + Timeout        |
| "One slow dep killing my pool"                           | Bulkhead                                 |
| "Slow rollout"                                           | Canary + Feature Flags                   |
| "Migrating off monolith"                                 | Strangler Fig + API Gateway              |
| "Outbound HTTP to internal service"                      | `RestClient` + Resilience4j + interceptors |
| "Calling an LLM provider"                                | `LlmClient` port + adapter + per-tenant RateLimiter |
| "Semantic search over our documents"                     | pgvector + HNSW + tenant-scoped retrieval |
| "Answer questions grounded in our docs"                  | RAG: retrieve top-K → rerank → prompt with citations |
| "Need to mock outbound HTTP in tests"                    | WireMock                                 |

## Reference

- `.claude/skills/lib/jabrena/125-java-concurrency/references/125-java-concurrency.md` — async, virtual threads, `@Async`, executor sizing (relevant for §9.5 streaming/sync and §8.10 `CompletableFuture` usage).
- `.claude/skills/lib/jabrena/702-technologies-wiremock/references/702-technologies-wiremock.md` — outbound HTTP contract and failure-mode testing (relevant for §8.9 and §9 adapter tests).
