---
name: java-api-design
description: Use whenever defining a REST endpoint, designing URL paths, versioning, pagination, filtering, sorting, idempotency keys, content negotiation, OpenAPI generation, or response shapes. Encodes the framework's REST conventions so every service exposes the same patterns to clients.
---

# REST API Design — One Set of Rules

Clients should not have to learn a different paging style, a different versioning scheme, or a different idempotency mechanism per service. This skill is the contract.

## 1. URL Design

- **Plural nouns for collections**: `/v1/orders`, `/v1/customers`. Not `/v1/order`.
- **Hierarchical for ownership**: `/v1/orders/{orderId}/lines/{lineId}`. Nest only when the child has no independent identity.
- **kebab-case** in paths: `/v1/api-keys`, not `/v1/apiKeys`.
- **No verbs in paths.** Use HTTP methods. Exception: actions that don't fit CRUD → `POST /v1/orders/{id}/cancel`.
- **Query params** for filtering, sorting, paging. Path params for identifiers.

## 2. Methods

| Method   | Semantics                                                                | Idempotent | Body |
| -------- | ------------------------------------------------------------------------ | ---------- | ---- |
| `GET`    | Read                                                                     | yes        | none |
| `POST`   | Create; or "do this action"                                              | no (require `Idempotency-Key`) | yes |
| `PUT`    | Replace entire resource                                                  | yes        | yes  |
| `PATCH`  | Partial update (RFC 7396 merge patch by default; JSON Patch only when client needs ops) | no | yes |
| `DELETE` | Remove                                                                   | yes        | none |

Return `204 No Content` for successful `DELETE` and `PUT` when no body is meaningful; otherwise return the resource.

## 3. Versioning — URI Path

`/v1/...`, `/v2/...`. Versions are **major contracts**. Breaking change → new version. Old version supported for at least one full client migration cycle (define per project in CLAUDE.md §"Project Details").

- Why URI not header: discoverability, caching, log/trace clarity, easy gateway routing.
- Within a version, all changes must be backward-compatible (add optional fields; never remove or rename; never tighten validation).

## 4. Pagination — Cursor by default

```http
GET /v1/orders?limit=50&cursor=eyJpZCI6IjAxSEs...
```

Response:
```json
{
  "data": [ { "id": "...", ... }, ... ],
  "pagination": {
    "next": "eyJpZCI6IjAxSEt...",        // null when no more
    "prev": null,                          // optional
    "hasMore": true
  }
}
```

- Default `limit=20`, max `limit=100`. Anything above max → `400`.
- Cursors are opaque (base64-encoded JSON or signed token). Clients **do not** parse them.
- Offset paging (`?page=&size=`) is allowed **only** for genuinely small, stable result sets (e.g., enums, fixed lookup tables). Document it explicitly in OpenAPI.
- **Never** return all rows. The contract is: clients call us in a loop until `next` is null.

## 5. Filtering

```http
GET /v1/orders?status=PLACED&customerId=abc&createdAfter=2026-01-01T00:00:00Z
```

- Simple equality: `?field=value`.
- Multiple values: repeat or comma: `?status=PLACED,PAID`.
- Ranges: `<field>After`, `<field>Before`, `<field>Min`, `<field>Max`.
- **No** arbitrary expression language (`?filter=(status eq PLACED) and (total gt 100)`) unless the domain genuinely needs it — then design it explicitly and bake into OpenAPI.

## 6. Sorting

```http
GET /v1/orders?sort=createdAt,desc&sort=total,asc
```

- One `sort` param per field, comma-separated `field,direction`. Default direction `asc`.
- Allowed sort fields are explicit per endpoint (don't accept arbitrary column names — SQL-injection vector).
- Default sort always stable (include `id` as tie-breaker).

## 7. Sparse Fieldsets

```http
GET /v1/orders/{id}?fields=id,total,status
```

Only when payloads are large and clients care. Off by default — adds complexity, makes caching trickier.

## 8. Idempotency Keys (mutations)

Every `POST`, `PATCH`, `DELETE` that creates or modifies data accepts:

```http
Idempotency-Key: <client-generated-uuid-v4>
```

Server stores `(key, request-hash, response-status, response-body)` for ≥24h.

- Same key + same body → replay stored response with header `Idempotency-Replayed: true`.
- Same key + different body → `422 Unprocessable Entity` with code `IDEMPOTENCY_KEY_CONFLICT`.
- Missing on a mutating endpoint → `400 Bad Request` (project decision: enforce strict or allow).

## 9. Conditional Requests — ETags

For long-lived resources, include `ETag` on `GET`; accept `If-Match` / `If-None-Match` on `PUT`/`PATCH`/`DELETE`. Mismatch → `412 Precondition Failed`.

## 10. Content Negotiation

- Default `Content-Type: application/json` / `Accept: application/json`. UTF-8.
- Error bodies: `application/problem+json` (see `java-api-errors`).
- File downloads: `application/octet-stream` + `Content-Disposition`.

## 11. Internationalization & Localization Pipeline

### 11.1 Locale Resolution

`Accept-Language: en-US, fr;q=0.9` honored for human-readable messages (validation `message`, problem `detail`). Resolution order — first hit wins:

1. **User preference** — `locale` claim on JWT, or persisted user setting.
2. **Tenant default** — per-tenant configured fallback (e.g., a French-only enterprise tenant).
3. **`Accept-Language` header** — RFC 7231 quality-value parsed.
4. **App default** — `en` as last resort.

```java
@Bean
public LocaleResolver localeResolver() {
    AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
    resolver.setDefaultLocale(Locale.ENGLISH);
    resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN));
    return resolver;
}
```

Wrap with a custom resolver that consults JWT/user setting/tenant before falling through to `Accept-Language`. Always set `Content-Language` on responses that carry localized strings.

### 11.2 MessageSource

```java
@Bean
public ReloadableResourceBundleMessageSource messageSource() {
    var ms = new ReloadableResourceBundleMessageSource();
    ms.setBasenames("classpath:i18n/messages", "classpath:i18n/validation");
    ms.setDefaultEncoding("UTF-8");
    ms.setCacheSeconds(60);            // reloadable in non-prod; tune for prod
    ms.setFallbackToSystemLocale(false);
    return ms;
}
```

`messages_<lang>.properties` per language. Use `MessageSourceAccessor` in services; never hand-format strings.

### 11.3 Plurals — ICU MessageFormat

`java.text.MessageFormat` doesn't handle plural rules properly (Slavic languages have 4+ forms). Use ICU4J:

```properties
cart.items={count, plural, one {# item} other {# items}}
```

```java
String text = icuMessageSource.getMessage("cart.items", Map.of("count", 5), locale);
```

### 11.4 Per-Tenant Overrides

White-label tenants need brand-specific wording. Layer overrides on top of the base bundle:

- `messages_<lang>.properties` — application default.
- `messages_<tenant>_<lang>.properties` — tenant override, loaded from DB or filesystem.

A custom `MessageSource` chains tenant → app default. Cache aggressively; invalidate on tenant config change.

### 11.5 Data Formatting — Do Not Localize on Server

| Data type   | API returns                              | Client renders                |
| ----------- | ---------------------------------------- | ----------------------------- |
| Date / time | ISO 8601 / RFC 3339 (`Instant`)          | User locale + timezone.       |
| Currency    | ISO 4217 code + minor-unit integer       | Locale-formatted with symbol. |
| Numbers     | Plain JSON numbers (no thousands sep)    | Locale-formatted.             |
| Booleans    | `true` / `false`                         | "Yes"/"Non"/"Ja"/...          |

**Exception**: free-text human-readable strings — problem `detail`, validation messages, notification body — are localized server-side because the server owns the message catalog.

### 11.6 Time Zones

API uses UTC `Instant`. User TZ is **display-side** concern:

- Persisted user preference, or
- `X-Timezone: Europe/Paris` header for endpoints that genuinely need server-side TZ awareness (rare — usually report generation, billing-period boundaries).

Never accept TZ-less local datetimes from clients. Always `Instant` or `OffsetDateTime`.

### 11.7 Translation Pipeline

- **Source of truth**: properties files in the repo. Translators don't touch the repo.
- **Sync**: CI job pushes new keys to Phrase / Lokalise / Transifex on merge to `main`.
- **Pull back**: translators commit via the platform; a scheduled CI job opens PRs with translated bundles.
- **Gate**: a build step verifies every supported locale has every key — missing keys fail the build. No silent fallback in production deployments.

```bash
# CI gate (pseudo)
./gradlew i18nCheck   # fails if any messages_<lang>.properties is missing a key from messages.properties
```

### 11.8 RTL Languages

For Arabic, Hebrew, Persian: the API returns the text; **layout is the client's responsibility**. Don't pre-render `‫`-wrapped strings server-side. Don't add direction hints in JSON.

### 11.9 Anti-patterns

- Returning localized strings without `Content-Language` response header.
- Hardcoded English in `@NotNull(message="...")` — use `{validation.notNull}` keys.
- Server-side formatting of numbers / currency / dates as locale-aware strings.
- Locale fallback to default that silently masks missing translations (must fail loudly in CI, gracefully at runtime with logging).
- Storing translations in a separate service from where the code lives — drift is guaranteed.
- Using `Accept-Language` and ignoring user preference from JWT/profile.

## 12. Timestamps

- All timestamps **ISO 8601 UTC with `Z` suffix**: `2026-05-25T14:30:00Z`.
- Type `java.time.Instant` in Java. Jackson with `WRITE_DATES_AS_TIMESTAMPS=false` and `JavaTimeModule`.

## 13. IDs in URLs

- UUID v7 (time-ordered) by default. Lowercase, with hyphens (RFC 4122 canonical).
- **Never** integer sequences exposed to clients (enumeration risk + cross-tenant leak surface).

## 14. Response Envelopes

Default to **no envelope** for single resources:
```json
{ "id": "...", "total": 12.34, "status": "PLACED" }
```

For collections, use a `data + pagination + meta` envelope (§4).

Avoid generic `{ "success": true, "data": ... }` wrappers — HTTP status already signals success. Wrappers add noise.

## 15. OpenAPI — Source of Truth

- **springdoc-openapi** generates the spec from controllers + DTOs.
- Spec available at `/v3/api-docs` and Swagger UI at `/swagger-ui.html` (gated by auth in non-local profiles).
- The PR build **publishes the spec as an artifact**. Clients are generated from this spec, not hand-written.
- Every controller method has `@Operation(summary, description)` + `@ApiResponses` documenting status codes including `400`, `401`, `403`, `404`, `409`, `5xx`. The error response schema = `ProblemDetail` (see `java-api-errors`).
- Examples in `@Schema` for every DTO — drives generated docs and consumer-side tests.

## 16. Hypermedia / HATEOAS

Off by default. The cost (clients building generic crawlers) rarely pays off in B2B SaaS APIs. If a domain genuinely needs it (e.g., state machine with dynamic next actions), add `_links` selectively, not everywhere.

## 17. Caching

- `Cache-Control: private, max-age=...` on idempotent reads where appropriate.
- `ETag` for conditional GETs.
- `Cache-Control: no-store` on anything tenant-sensitive that clients should not cache cross-user.

## 18. Streaming, SSE & WebSockets

### 18.1 Default — Server-Sent Events

Server-push is **SSE** unless you have a concrete bidirectional requirement.

```java
@GetMapping(value = "/v1/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@PathVariable String id,
                         @RequestHeader(value = "Last-Event-Id", required = false) String lastId) {
    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
    jobEventBus.subscribe(id, lastId, emitter);
    return emitter;
}
```

Spring MVC `SseEmitter` works well with virtual threads — Spring WebFlux + `Flux<ServerSentEvent>` is fine too but only worth it if the rest of the service is reactive.

**SSE strengths**: HTTP/1.1, plays well with proxies, browser `EventSource` auto-reconnects, same auth as REST (cookie or `Authorization`), trivial to debug with `curl -N`.

**SSE weaknesses**: unidirectional only; head-of-line blocking on HTTP/1.1 — **terminate behind HTTP/2** at the gateway so concurrent streams don't starve regular requests.

### 18.2 WebSocket — when bidirectional is real

Use Spring WebSocket (raw, or STOMP for browser clients). Don't reach for it just because "real-time" — SSE covers 80% of real-time UI updates.

```java
@Configuration
@EnableWebSocketMessageBroker
public class WsConfig implements WebSocketMessageBrokerConfigurer {
    public void registerStompEndpoints(StompEndpointRegistry r) {
        r.addEndpoint("/ws").setAllowedOriginPatterns("https://*.example.com");
    }
}
```

**Auth**: validate JWT in the `CONNECT` frame (STOMP header or first message). **Never** pass tokens in the WebSocket URL — they leak into proxy logs and browser history. Rebuild `SecurityContext` per inbound message via a `ChannelInterceptor`; the connection-time auth doesn't propagate automatically.

### 18.3 Backpressure

- `SseEmitter` — set a timeout; on send-failure (client gone), the emitter completes with error — clean up subscriptions.
- WebSocket — `setSendBufferSizeLimit` and `setSendTimeLimit`; exceeding limits closes the session.
- Document throughput envelope per endpoint. When overwhelmed, the app must **drop** (with a counter metric) or **throttle**, never queue unboundedly.

### 18.4 Sticky Sessions & Reconnection

- **WebSocket** needs sticky sessions across replicas (k8s `sessionAffinity: ClientIP`, or ingress sticky-cookie). Brokered backends (Redis/RabbitMQ relay) avoid pinning but add ops cost.
- **SSE** typically doesn't need stickiness because reconnect re-establishes from any pod — but persisted event ids must be shared (DB or Redis).
- **SSE reconnection**: support `Last-Event-Id`. Every emitted event has an `id:`; clients send it on reconnect; server replays from that point.

```
id: 4823
event: token
data: {"text":"Hello"}

id: 4824
event: done
data: {}
```

### 18.5 Heartbeats

Proxies kill idle connections. Send a heartbeat every 15s.

- SSE: `:ping\n\n` comment frame, or `event: ping\ndata: {}\n\n`.
- WebSocket: native `PING` frame at the protocol level.

### 18.6 Long-Running Mutations

Two acceptable shapes — pick one per endpoint:

1. **Async + status endpoint**:
   - `POST /v1/exports` → `202 Accepted` + `Location: /v1/exports/{jobId}` + body `{ "jobId": "...", "status": "PENDING" }`.
   - Client polls `GET /v1/exports/{jobId}` or subscribes to SSE.
2. **SSE event stream tracking the job**:
   - `POST /v1/exports` → `202` with `Location: /v1/exports/{jobId}/events` (SSE endpoint).

Never block a request thread for >5s synchronously.

### 18.7 LLM Streaming

SSE is the canonical pattern. Each token = one event; final event = completion sentinel.

```
event: token
data: {"text":"Hel"}

event: token
data: {"text":"lo"}

event: done
data: {"usage":{"in":42,"out":18},"finishReason":"stop"}
```

Wire the LLM SDK's streaming callback directly into the `SseEmitter`. Don't accumulate the whole response first — the point is incremental delivery.

### 18.8 Tenant Context Propagation

- WebSocket: set tenant on connect via interceptor; cache on the session. Easy to forget on per-message handlers — enforce via a central `ChannelInterceptor`.
- SSE: tenant is already in the request `SecurityContext`; propagate to the emitter's executor (virtual-thread inheritance helps).

### 18.9 Anti-patterns

- WebSocket without auth on connect (anonymous channel by default = data leak).
- SSE without `Last-Event-Id` support — clients can't resume after a 30s network blip.
- Long-polling instead of SSE — legacy pattern, use SSE.
- Blocking a request thread on a synchronous LLM call instead of streaming.
- Persisting business state inside the `SseEmitter` or WebSocket session (lost on disconnect — keep state in DB/cache, the emitter is a delivery channel).
- Putting the JWT in the WebSocket URL (`ws://.../socket?token=...`).
- Returning `200 OK` immediately for a long-running mutation with `{"status":"PENDING"}` — use `202 Accepted` + Location.

## 19. GraphQL & gRPC (when REST isn't enough)

REST is the default. Reach for these only when the constraints below actually apply.

### 19.1 GraphQL — when

- A single client (typically mobile) needs **flexible projections** across many entities, and round-trip cost is a real bottleneck.
- N+1 problems are solvable with **DataLoader** batching patterns.
- Use `spring-boot-starter-graphql` (Spring for GraphQL, built on graphql-java).

### 19.2 GraphQL — opinions

- **One schema per service**. Federation only when you have ≥3 services exposing GraphQL — otherwise it's overhead without payoff.
- **Pagination**: Relay-style connections — `edges { node, cursor }`, `pageInfo { hasNextPage, endCursor }`. Cursor-based, consistent with §4.
- **Errors**: GraphQL standard `errors` array, with `extensions` carrying our `code` and `traceId` — same shape as `java-api-errors` so clients can reuse handling logic.
- **Auth**: same JWT as REST. Per-field `@PreAuthorize` via Spring Security's GraphQL integration. Don't authorize at the resolver entry only — fields are independently reachable.
- **DataLoader** for every entity-id batch fetch. No exceptions. An N+1 in a GraphQL resolver is a production incident waiting to happen.
- **Persisted queries** in production — clients register operations by hash, server rejects ad-hoc strings. Shrinks attack surface and improves caching.
- **Depth and complexity limits** configured (`maxQueryDepth`, `maxQueryComplexity`). Without them, GraphQL is a DoS vector.
- **Subscriptions** over WebSocket only when truly needed — they carry the WebSocket ops cost (§18.2). SSE-based subscriptions are an option in some setups; prefer SSE if push is unidirectional.

### 19.3 gRPC — when

- **Service-to-service**, high volume, low latency, schema-first contract.
- **Internal-only**. Never expose gRPC directly to public clients without a gateway translation layer (gRPC-Web / REST transcoding) — public clients shouldn't depend on HTTP/2 framing or protobuf tooling.

### 19.4 gRPC — opinions

- `grpc-spring-boot-starter` + protobuf in `src/main/proto`. Generated stubs land in `build/generated/source/proto`.
- **Versioned package names**: `example.orders.v1`. A breaking change → new `v2` package, both served in parallel during migration. Mirrors the URI-versioning policy in §3.
- Same **Resilience4j** wiring as REST (`@CircuitBreaker`, `@Retry`, `@TimeLimiter` work on any blocking call).
- **Streaming**: bidirectional for chat-like or coordination flows; server-streaming for feed-style pushes; otherwise unary.
- **Auth**: same JWT, validated by a `ServerInterceptor`. Don't roll a separate token scheme.
- **Logging / tracing interceptor** so distributed-tracing IDs match the REST path — same `traceparent` propagation rules.

### 19.5 Decision Matrix

| Need                                                          | Choose                          |
| ------------------------------------------------------------- | ------------------------------- |
| Public API consumed by varied clients                         | REST                            |
| Mobile client with bandwidth concerns, many entity projections | GraphQL                         |
| Internal service-to-service, low latency, schema-first        | gRPC                            |
| Streaming (server-push to UI)                                 | SSE > WebSocket                 |
| Streaming (bidirectional, interactive)                        | WebSocket or gRPC bidi          |

### 19.6 Anti-patterns

- Exposing gRPC directly to the public internet without a gateway translation layer.
- GraphQL without depth/complexity limits (DoS surface).
- Federating GraphQL before you have ≥3 services that actually expose GraphQL.
- N+1 in a GraphQL resolver — always go through DataLoader.
- Breaking a gRPC schema without bumping the package to `v2`.
- Reinventing JWT auth for GraphQL or gRPC — reuse the REST chain.
- GraphQL mutations that bypass the same validation/idempotency rules as REST (§8).

## 20. Headers — Standard

| Header                       | Direction | Use                                          |
| ---------------------------- | --------- | -------------------------------------------- |
| `Authorization: Bearer ...`  | in        | JWT.                                         |
| `X-Tenant-Id`                | in        | Tenant override (admin/support only).        |
| `Idempotency-Key`            | in        | Mutations.                                   |
| `If-Match` / `If-None-Match` | in        | Conditional.                                 |
| `Accept-Language`            | in        | i18n.                                        |
| `X-Correlation-Id`           | in/out    | Cross-system correlation.                    |
| `traceparent`                | in/out    | W3C trace context (set by OTel).             |
| `Idempotency-Replayed: true` | out       | Set when replaying.                          |
| `RateLimit-Remaining`        | out       | RFC 9239 (optional, recommended).            |

## 21. Anti-patterns — Refuse

- Verbs in URLs (`/getOrder`, `/createCustomer`).
- Plural inconsistency (`/v1/order/{id}/lines`).
- `GET` with side effects.
- `?page=` with no maximum.
- Returning DB column names (`snake_case`) in JSON. Use `camelCase` in JSON, mapped from `snake_case` columns.
- Exposing IDs from other tenants by accident (always scope queries by `tenantId`).
- Spec drift: hand-edited OpenAPI YAML diverging from controllers.
- 200 with `{"error":...}` body. Use 4xx/5xx + Problem.

## 22. Pre-Merge Checklist

- [ ] URL plural, kebab-case, hierarchical when appropriate.
- [ ] HTTP method semantics match action.
- [ ] Mutations accept `Idempotency-Key`.
- [ ] Pagination cursor-based with bounded `limit`.
- [ ] Sort fields explicitly allowlisted.
- [ ] Timestamps `Instant` UTC.
- [ ] OpenAPI annotations present; spec regenerates clean.
- [ ] Error responses documented as `ProblemDetail`.

## 23. Reference (deep dive)

- `.claude/skills/lib/jabrena/302-frameworks-spring-boot-rest/references/302-frameworks-spring-boot-rest.md` — Spring REST patterns, content negotiation, status codes, controller idioms
- `.claude/skills/lib/jabrena/701-technologies-openapi/references/701-technologies-openapi.md` — OpenAPI 3 generation, springdoc, schema/example annotations
- `.claude/skills/lib/jabrena/303-frameworks-spring-boot-validation/references/303-frameworks-spring-boot-validation.md` — request-validation patterns aligned with the contract
