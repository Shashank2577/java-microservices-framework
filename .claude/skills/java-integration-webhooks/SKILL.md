---
name: java-integration-webhooks
description: Use when implementing inbound webhook receivers (Stripe, GitHub, Slack, Twilio etc.) or outbound webhooks delivered to customers. Covers HMAC signature verification, replay defense, accept-fast/process-async pattern, idempotency, durable outbound queue with retries, signing customer payloads, secret rotation, and local testing via tunnels + WireMock.
---

# Integration — Webhooks

Webhooks are the most common production hazard in B2B services: untrusted input arriving over HTTP, duplicated, late, signed with vendor-specific schemes, and expected to never lose data. Treat them like Kafka with worse semantics.

## 1. Inbound Webhooks — The Hazard

- Vendors deliver **duplicates** routinely (network blips, their own retries, replays from their dashboards).
- Delivery is **out of order** and sometimes **hours late** (Stripe will replay days-old events after an outage).
- Retry behaviour varies: Stripe retries on 5xx with exponential backoff for ~3 days; GitHub retries 8 times over ~3h; Slack retries 3 times in 10 min; Twilio retries once. **Document per provider in a registry.**
- Sign with HMAC-SHA256 over `timestamp.body`. **Always verify the signature on the raw bytes before parsing JSON** — parsing untrusted input is a timing/DoS/parser-exploit surface.

| Provider | Header | Algo | Replay header | Retries on 5xx |
|---|---|---|---|---|
| Stripe | `Stripe-Signature` | HMAC-SHA256 (`t=...,v1=...`) | timestamp in header | Yes (~3d) |
| GitHub | `X-Hub-Signature-256` | HMAC-SHA256 (`sha256=...`) | `X-GitHub-Delivery` | Yes (~3h) |
| Slack | `X-Slack-Signature` | HMAC-SHA256 (`v0=...`) | `X-Slack-Request-Timestamp` | 3× / 10m |
| Twilio | `X-Twilio-Signature` | HMAC-SHA1 (URL+params) | none | 1× |

## 2. Verification — Concrete Pattern (Stripe-style)

```java
public final class StripeSignatureVerifier {
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private final byte[] secret;        // from Vault, bytes

    public boolean verify(String header, byte[] rawBody, Instant now) {
        var parts = parseHeader(header);                       // t=..., v1=...
        long ts = Long.parseLong(parts.get("t"));
        String sigHex = parts.get("v1");
        if (sigHex == null) return false;

        // 1. Replay window — reject old timestamps BEFORE crypto
        if (Math.abs(now.getEpochSecond() - ts) > MAX_AGE.toSeconds()) return false;

        // 2. Compute HMAC over "t.body"
        byte[] signed = (ts + ".").getBytes(StandardCharsets.UTF_8);
        byte[] payload = ByteBuffer.allocate(signed.length + rawBody.length)
            .put(signed).put(rawBody).array();

        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }

        byte[] expected = mac.doFinal(payload);
        byte[] provided = HexFormat.of().parseHex(sigHex);

        // 3. Constant-time compare — never String.equals on signatures
        return MessageDigest.isEqual(expected, provided);
    }
}
```

Controller binds **raw bytes** (`byte[]`), not the parsed DTO:

```java
@PostMapping(path = "/v1/webhooks/stripe", consumes = "application/json")
public ResponseEntity<Void> stripe(@RequestHeader("Stripe-Signature") String sig,
                                   @RequestBody byte[] body) {
    if (!verifier.verify(sig, body, Instant.now())) return ResponseEntity.status(401).build();
    inbox.enqueue("stripe", body);    // verified — safe to parse downstream
    return ResponseEntity.ok().build();
}
```

## 3. Accept-Fast, Process-Async

- HTTP handler verifies signature → inserts raw event into the **webhook inbox** → returns 200. **No downstream calls, no DB joins, no enrichment.**
- An asynchronous worker (`@Scheduled` + ShedLock, or a Kafka consumer if the event is re-published internally) processes the row.
- Target handler latency: **< 200ms p99**. Stripe times out at 30s, but a slow webhook back-pressures their delivery pipeline and gets you flagged.
- Never call downstream services or do heavy work inside the webhook handler — vendors timeout fast and start retrying, which amplifies load exactly when you can least afford it.

## 4. Idempotency — Keyed by Vendor `event_id`

Inbox table — composite PK ensures dedup across providers:

```sql
CREATE TABLE webhook_inbox (
  provider     VARCHAR(64)  NOT NULL,
  event_id     VARCHAR(128) NOT NULL,
  raw_body     BYTEA        NOT NULL,
  received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ  NULL,
  attempts     INT          NOT NULL DEFAULT 0,
  last_error   TEXT         NULL,
  PRIMARY KEY (provider, event_id)
);
CREATE INDEX ix_webhook_inbox_pending ON webhook_inbox(provider, received_at)
  WHERE processed_at IS NULL;
```

```java
public void enqueue(String provider, byte[] body) {
    String eventId = registry.get(provider).eventId(body);    // provider-specific extraction
    try {
        jdbc.update("INSERT INTO webhook_inbox(provider, event_id, raw_body) VALUES (?,?,?)",
                    provider, eventId, body);
    } catch (DuplicateKeyException e) {
        // Already received — return 200 to vendor, do nothing
    }
}
```

Provider-specific `event_id` extraction:

| Provider | Source |
|---|---|
| Stripe | `id` field on every event |
| GitHub | `X-GitHub-Delivery` header (UUID) |
| Slack | `event_id` field (Events API) |
| Twilio | `MessageSid` / `CallSid` (depends on event type) |

**Pin which header/field per provider** in the registry — don't guess at runtime.

## 5. Multi-Provider Registry

```java
public interface WebhookProvider {
    String name();                                        // "stripe"
    boolean verify(Map<String,String> headers, byte[] body);
    String eventId(byte[] body, Map<String,String> headers);
    String topic(byte[] body);                            // routing key for async worker
}
```

```java
@RestController
public class WebhookController {
    private final Map<String, WebhookProvider> providers;  // by name, Spring-injected

    @PostMapping("/v1/webhooks/{provider}")
    public ResponseEntity<Void> handle(@PathVariable String provider,
                                       @RequestHeader Map<String,String> headers,
                                       @RequestBody byte[] body) {
        var p = providers.get(provider);
        if (p == null) return ResponseEntity.notFound().build();
        if (!p.verify(headers, body)) {
            meter.signatureFailed(provider);
            return ResponseEntity.status(401).build();
        }
        inbox.enqueue(p.name(), p.eventId(body, headers), body);
        return ResponseEntity.ok().build();
    }
}
```

- One URL pattern, one auth chokepoint. Adding a provider = implementing the interface and adding a bean.
- Each provider's secret loaded via `@ConfigurationProperties` from Vault. Never in `application.yml`.

## 6. Secret Rotation

- Each provider config holds **N current secrets** (typically 2: `primary` + `previous`). Verifier tries each; success on any = pass.
- After the rotation window (vendor-defined; Stripe gives 24h on the old endpoint secret), drop `previous`.
- Rotation runbook:
  1. Generate new secret in vendor dashboard.
  2. Add as `primary`; demote existing `primary` to `previous`.
  3. Deploy.
  4. Wait > vendor rotation window.
  5. Remove `previous`. Deploy.
- Alert on `webhook_inbound_signature_failed_total` spike during a rotation window — usually means you forgot step 2.

## 7. Outbound Webhooks — The Framework

Customer-configurable URLs. Each subscription:

```sql
CREATE TABLE webhook_subscription (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID         NOT NULL,
  target_url      TEXT         NOT NULL,
  topics          TEXT[]       NOT NULL,
  signing_secret  BYTEA        NOT NULL,    -- per-subscription
  state           VARCHAR(16)  NOT NULL,    -- active|disabled|suspended
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

- **Per-subscription secret** so a customer can rotate theirs without affecting others.
- `state=suspended` after sustained failures (see §7 retry policy).

Delivery pipeline:

```sql
CREATE TABLE webhook_outbox (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  subscription_id UUID         NOT NULL REFERENCES webhook_subscription(id),
  tenant_id       UUID         NOT NULL,
  event_id        UUID         NOT NULL,
  topic           VARCHAR(255) NOT NULL,
  payload         JSONB        NOT NULL,
  attempts        INT          NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  sent_at         TIMESTAMPTZ  NULL,
  dead            BOOLEAN      NOT NULL DEFAULT false
);
CREATE INDEX ix_webhook_outbox_due ON webhook_outbox(next_attempt_at)
  WHERE sent_at IS NULL AND dead = false;
```

- Producer use-case writes a row in the same transaction as the business change (same outbox discipline as `java-messaging` §7).
- Scheduled worker (ShedLock) ships rows.

Signing the payload (per-subscription):

```java
String body = mapper.writeValueAsString(event);
long ts = Instant.now().getEpochSecond();
byte[] toSign = (ts + "." + body).getBytes(UTF_8);
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(sub.signingSecret(), "HmacSHA256"));
String v1 = HexFormat.of().formatHex(mac.doFinal(toSign));

HttpRequest req = HttpRequest.newBuilder(URI.create(sub.targetUrl()))
    .timeout(Duration.ofSeconds(10))
    .header("Content-Type", "application/json")
    .header("X-Signature", "t=" + ts + ",v1=" + v1)
    .header("X-Webhook-Id", row.id().toString())
    .header("X-Webhook-Timestamp", String.valueOf(ts))
    .header("X-Tenant-Id", sub.tenantId().toString())
    .POST(BodyPublishers.ofString(body))
    .build();
```

Retry policy — exponential backoff with jitter:

| Attempt | Delay |
|---|---|
| 1 | immediate |
| 2 | 1 min |
| 3 | 5 min |
| 4 | 30 min |
| 5 | 2 h |
| 6 | 8 h |
| 7-10 | 1 d |

After attempt 10 (~24h elapsed) → `dead=true`. Expose admin endpoint to redeliver. After N consecutive dead rows for a subscription, flip `state=suspended` and email the customer.

## 8. Customer-Visible Delivery Log

```sql
CREATE TABLE webhook_delivery (
  id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  subscription_id       UUID         NOT NULL,
  event_id              UUID         NOT NULL,
  attempt               INT          NOT NULL,
  status_code           INT          NULL,
  response_body_excerpt VARCHAR(2000) NULL,   -- truncate; never store full body
  attempted_at          TIMESTAMPTZ  NOT NULL,
  next_retry_at         TIMESTAMPTZ  NULL,
  error                 TEXT         NULL
);
```

- `GET /v1/webhooks/subscriptions/{id}/deliveries` — paginated, tenant-scoped.
- `POST /v1/webhooks/subscriptions/{id}/deliveries/{deliveryId}/redeliver` — re-enqueues the original payload with a fresh `webhook_outbox` row.
- Customers stop opening support tickets ("did event X get sent?") when they can see it themselves.

## 9. Observability

| Metric | Labels | Alert on |
|---|---|---|
| `webhook_inbound_total` | `provider`, `status` | — |
| `webhook_inbound_signature_failed_total` | `provider` | rate spike (auth / rotation issue) |
| `webhook_inbound_processing_lag_seconds` | `provider` | p95 > 60s |
| `webhook_outbound_attempts_total` | `subscription_id` | — |
| `webhook_outbound_failed_total` | `subscription_id`, `status_code` | sustained 5xx for one subscription |
| `webhook_outbound_dead_total` | `tenant_id` | growth (customer endpoint down) |

- Logs: log `event_id`, `provider`, `subscription_id`, `attempt`, `status_code`. **Never the raw payload** — webhook bodies routinely contain PII, card metadata, customer emails.
- Trace: continue `traceparent` from controller into the async worker via stored header.

## 10. Local Testing

- **Inbound**: ngrok / Cloudflare Tunnel exposes local app to vendor sandbox. Stripe CLI (`stripe listen --forward-to localhost:8080/v1/webhooks/stripe`) and GitHub's "Redeliver" button in the webhook UI give cheap replay.
- **Outbound**: WireMock simulates customer endpoints, including failure modes:

```java
wireMock.stubFor(post("/customer-endpoint")
    .inScenario("retry").whenScenarioStateIs(STARTED)
    .willReturn(serverError())
    .willSetStateTo("recovered"));
wireMock.stubFor(post("/customer-endpoint")
    .inScenario("retry").whenScenarioStateIs("recovered")
    .willReturn(ok()));
```

- **Contract test**: record a real provider request once (`stripe trigger checkout.session.completed --print` → save bytes), replay in CI against the verifier and inbox. Pin the signature secret as a test fixture.

## 11. Multi-Tenancy

- **Inbound**: derive `tenant_id` from the event — Stripe Connect events carry `account`; GitHub installation events carry `installation.id`. Look up tenant; populate `TenantContext` in the async worker, **not** the HTTP handler (handler must stay fast).
- Optionally use a path-scoped URL (`/v1/webhooks/{provider}/{tenant_slug}`) when the vendor doesn't include tenant info in the body — Slack per-workspace tokens, custom integrations.
- **Outbound**: `tenant_id` set from the `TenantContext` of the producing use-case; copied onto the `webhook_outbox` row at write time. The delivery worker reads it from the row — never from `TenantContext` (worker isn't tenant-scoped).

## 12. Anti-Patterns — Refuse

- Calling downstream services / DB writes from inside the HTTP webhook handler.
- Verifying signature **after** parsing JSON (timing-attack and parser-DoS surface).
- `String.equals()` for signature compare — use `MessageDigest.isEqual`.
- A single global webhook secret instead of per-provider (and per-subscription outbound).
- Outbound delivery called synchronously from a use-case.
- "We retry 3 times and give up" with no DLT, no replay, no customer visibility.
- Logging raw webhook bodies — PII leak.
- Storing provider secrets in `application.yml` or env vars instead of Vault.
- Letting `state=active` stay set after sustained delivery failure — suspend and tell the customer.
- Trusting vendor `tenant_id` claims without lookup against your own subscription table.

## 13. Pre-Merge Checklist

- [ ] HMAC verification runs BEFORE JSON parse (raw `byte[]` controller arg)
- [ ] Constant-time signature compare (`MessageDigest.isEqual`)
- [ ] Replay window enforced (timestamp ≤ 5 min)
- [ ] Inbox dedup by `(provider, event_id)` — `DuplicateKeyException` swallowed and 200 returned
- [ ] Webhook handler returns < 200ms p99 (no downstream calls, no joins)
- [ ] Async worker uses ShedLock + tenant context populated from row
- [ ] Outbound delivery via durable queue with exponential backoff + dead-letter + redrive endpoint
- [ ] Per-subscription signing secret; rotation supported with N current secrets
- [ ] Metrics + alerts wired (signature failures, outbound dead growth)
- [ ] No raw payload in logs
- [ ] Provider registry entry includes: header name, event_id source, retry behaviour, replay window
- [ ] Local test harness: ngrok config documented OR WireMock contract test for the provider

## 14. References

- `.claude/skills/lib/jabrena/124-java-secure-coding/references/124-java-secure-coding.md` — crypto + timing-safe comparisons.
- `.claude/skills/lib/jabrena/702-technologies-wiremock/references/702-technologies-wiremock.md` — local mock for outbound tests.
- `java-messaging` §7-8 — outbox/inbox conventions mirrored here for HTTP delivery.
