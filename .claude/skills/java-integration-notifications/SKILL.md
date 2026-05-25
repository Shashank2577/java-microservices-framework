---
name: java-integration-notifications
description: Use when sending email, SMS, push, or in-app notifications. Covers outbox-driven sends (never inline), provider abstraction (SES/SendGrid/Postmark/Twilio/FCM/APNS), template engines + localization, suppression lists, bounce/complaint webhook ingestion, transactional vs marketing separation, per-tenant from-address + DKIM/SPF/DMARC posture, and retry/DLT semantics.
---

# Integration — Notifications

## 1. Never Send Inline from the Request Thread

User clicks "Reset password". Controller calls SMTP. SMTP times out 30s later. User retries. Two reset emails on the way. Now do this with 10k SMS/min and a flaky carrier.

Rule: **no notification leaves the request thread**. Use-case writes an outbox row in the business transaction. The notification-sender consumes from Kafka and calls the provider. Request returns in <50ms regardless of provider health.

## 2. The Pipeline

```
use-case --writes--> outbox (same TX) --publisher--> Kafka topic
                                                       |
                                                       v
                                        notification-sender service
                                                       |
                                                       v
                                            provider adapter (SES/Twilio/FCM)
```

- Outbox = source of truth. Provider 5xx is retried independently of the originating request.
- Topics: `notifications.email.requested.v1`, `notifications.sms.requested.v1`, `notifications.push.requested.v1`.
- Partition key = recipient id (per-recipient ordering for receipts/follow-ups).
- See `java-messaging` for outbox + DLT details; this skill is about what goes into the envelope.

## 3. Provider Abstraction

Ports in `domain`; adapters in `infrastructure`.

```java
public interface EmailGateway {
    SendResult send(EmailMessage msg);   // SendResult = providerId, accepted|rejected|deferred
}
public interface SmsGateway  { SendResult send(SmsMessage msg); }
public interface PushGateway { SendResult send(PushMessage msg); }
```

- Adapters: `SesEmailGateway`, `SendgridEmailGateway`, `PostmarkEmailGateway`, `TwilioSmsGateway`, `FcmPushGateway`, `ApnsPushGateway`.
- Pick via property — `app.notifications.email.provider=ses|sendgrid|postmark` — bound by `@ConditionalOnProperty` autoconfig.
- Integration tests use `InMemoryEmailGateway` that records sends; production reads from properties. Never `Mockito.mock` the gateway across the service boundary — use the in-memory adapter so the contract is exercised.

## 4. Templates — Engine + Storage

- **Thymeleaf** (or Pebble) for HTML email. Plain-text fallback rendered from the same template via a `text` fragment.
- Layout: `src/main/resources/templates/email/<key>.html` + companion `<key>.txt` + `<key>.subject` (Spring `MessageSource` key).
- Each template declares its required variables in a header comment; renderer fails fast on missing keys (no silent `${user.name}` → empty string).

```html
<!-- templates/email/password_reset.html
     vars: userFirstName, resetUrl, expiresMinutes -->
<th:block th:text="#{password_reset.subject(${userFirstName})}">Reset your password</th:block>
<p th:text="#{password_reset.greeting(${userFirstName})}">Hi Alex,</p>
<p th:text="#{password_reset.body(${expiresMinutes})}">Reset within 30 minutes.</p>
<a th:href="${resetUrl}" th:text="#{password_reset.cta}">Reset password</a>
```

## 5. Localization

- `Locale` resolved per recipient: user setting > tenant default > `en`.
- `MessageSource` reads `messages_<lang>.properties`; date/number via `DateTimeFormatter.ofLocalizedDate(...).withLocale(...)`.
- CI test renders every template in every supported locale and fails on a missing `MessageSource` key. Translation gaps are caught at PR time, not in production.

## 6. Transactional vs Marketing — Separate Everything

| | Transactional | Marketing |
|---|---|---|
| Examples | Password reset, receipt, alert | Campaign, weekly digest |
| Must arrive? | Yes | No (recipient may unsubscribe) |
| Sender | `noreply@<tenant>.example.com` | `news@<tenant>.example.com` |
| Provider account | Dedicated | Separate |
| IP pool | Dedicated, warmed | Separate, warmed |
| Domain | Distinct subdomain | Distinct subdomain |
| Unsubscribe header | No | Yes (RFC 8058 one-click) |

A marketing-campaign bounce storm must **never** sink the deliverability of password resets. Codify the split in a `NotificationKind` enum (`TRANSACTIONAL`, `MARKETING`) and route on it from the use case to the topic to the provider account.

## 7. Suppression List

```sql
CREATE TABLE email_suppressions (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  UUID NULL,                  -- NULL = org-wide
  email      CITEXT NOT NULL,
  reason     VARCHAR(32) NOT NULL,       -- HARD_BOUNCE | COMPLAINT | USER_UNSUBSCRIBE | MANUAL
  source     VARCHAR(64) NOT NULL,       -- which webhook/admin recorded it
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, email)
);
```

- Sender checks suppression **before every send**. Match wins → skip, write audit row (`notification_skipped` with reason).
- Marketing: also honour per-list unsubscribe.
- Mirror table for `sms_suppressions` (phone) and `push_suppressions` (device token).

## 8. Bounce / Complaint Webhook Ingestion

- SES → SNS → HTTP webhook to notification-sender. Twilio + Postmark + SendGrid all have their own; treat each as an integration (see `java-integration-webhooks` for signature verification + idempotency).
- Hard bounce or complaint → insert into suppression list with `source='ses-sns'`.
- Soft bounce: increment a counter; do **not** auto-suppress (transient). Optional: suppress after N consecutive soft bounces over M days.
- Manual override (admin) removes a row; automated expiry for soft-bounce-derived entries after ~6 months.

## 9. Per-Tenant From-Address + DKIM/SPF/DMARC

- Tenant onboarding flow captures: from-address, reply-to, custom sending domain.
- Admin tool registers domain with provider (SES domain identity / SendGrid sender auth), surfaces DKIM CNAMEs + SPF include + DMARC TXT for the tenant to publish.
- Verification polled until provider reports `verified`; only then are sends from that domain enabled. Until verified, fall back to `noreply@notifications.<your-org>.com` with a banner in the UI warning the tenant.
- Production DMARC policy: `p=reject` on transactional domains. `p=quarantine` minimum on marketing. Anything less invites spoofing.

## 10. Retry + DLT

- Provider 429 or 5xx → retry at the Kafka consumer with `DefaultErrorHandler` + `ExponentialBackOffWithMaxRetries(5)`; total budget ~30 min.
- 4xx other than 429 → permanent. Do **not** retry. Record `notification_failed` with the provider error code, ack, move on.
- After max retries → DLT (`notifications.email.requested.v1.dlt`). Alert on DLT growth. Provide a redrive endpoint with `x-redriven=true` header to break loops.

## 11. SMS Specifics (Twilio)

- Per-message cost is real money. Emit `sms_cost_usd_total{tenant_class}` from the gateway adapter.
- Per-tenant **daily cap** (configurable) enforced at the sender. Exceeded → drop + alert; do not silently throttle into next day.
- 160-char hard cap for single-segment GSM-7; emoji and accents reduce it. Validate length **before** send; refuse or split deliberately, never let the carrier silently charge for 4 segments.
- Phone numbers: E.164 only. Validate with **libphonenumber** at the edge; reject at the use case if not valid.

## 12. Push (FCM / APNS)

- Device-token registry: `(user_id, tenant_id, device_id, token, platform, last_seen_at)`. Tokens are not stable forever.
- Provider feedback (FCM `NotRegistered`, APNS `Unregistered`) → evict token from registry immediately.
- iOS and Android payload shapes differ (APS dict vs `notification`/`data`). Domain emits a `PushPayload` record (`title`, `body`, `data: Map<String,String>`, `priority`); each adapter serialises per platform.

## 13. In-App Notifications

- Separate table `inbox_notifications(id, tenant_id, user_id, kind, payload jsonb, read_at, created_at)`.
- Written **transactionally** with the business operation that produced it — same TX, no outbox.
- Surface via REST: `GET /api/inbox?unread=true` paginated. Optional WebSocket/SSE channel `inbox.<userId>` for live updates.
- Mark read: `PATCH /api/inbox/{id} {"read": true}`. Bulk mark-all-read as a single endpoint.

## 14. Observability

Counters and timers (Micrometer):
- `notification_sent_total{kind, channel, provider, tenant_class}`
- `notification_failed_total{kind, channel, reason}`
- `notification_suppressed_total{reason}`
- `notification_send_duration_seconds{channel, provider}` (timer)
- `notification_template_render_duration_seconds{template}`

Alerts:
- Bounce rate > 2% over 1h (deliverability cliff approaches).
- Provider 5xx rate spike (> baseline + 3σ).
- Suppression-list daily growth > N (signals a broken acquisition flow).
- DLT depth > 0 sustained > 5 min.

## 15. Multi-Tenancy

- Every notification row + outbox row + Kafka event carries `tenant_id`. Validated on the consumer side before render.
- Provider account selection is keyed by tenant: a misconfigured tenant **must not** silently fall back to a shared account (cross-tenant deliverability bleed).
- Cross-tenant send (event tenant ≠ recipient tenant) = hard error + alert. Never paper over.

## 16. Anti-Patterns — Refuse

- Sending inline from the request thread (`mailSender.send(...)` in a controller).
- Same domain for transactional + marketing.
- No suppression list (sending to known hard bounces).
- Hard-coded `from` address (cross-tenant leakage; tenant A's reset arrives from tenant B's domain).
- Templates as Java string literals or `String.format` in service code (no localization, no preview, no review).
- Provider SDK calls scattered through business code (`SesClient` instantiated in a use case).
- Ignoring bounce/complaint webhooks (reputation collapses, then everything dies at once).
- Unlimited send rate per tenant (one runaway loop = five-figure Twilio bill).
- Logging full notification body including PII / reset tokens.
- Re-rendering the same template on every send instead of caching the compiled form.

## 17. Pre-Merge Checklist

- [ ] Send goes via outbox, not inline.
- [ ] Template in `resources/templates/`, all supported locales present in `messages_*.properties`.
- [ ] Suppression check before send (email, SMS, or push as applicable).
- [ ] Transactional vs marketing routed to separate topic + provider account.
- [ ] Per-tenant from-address respected; fallback only for unverified tenants and visibly flagged.
- [ ] Retry policy configured; DLT wired; alert on DLT depth.
- [ ] Metrics emitted per send (sent / failed / suppressed / duration).
- [ ] PII / secrets not in logs.
- [ ] E.164 validation for SMS; segment count validated.

## 18. References

- `.claude/skills/java-messaging/SKILL.md` — outbox, DLT, consumer retry (this skill rides on that pipeline).
- `.claude/skills/java-integration-webhooks/SKILL.md` — bounce/complaint webhook ingestion (signature verification, idempotency).
- `.claude/skills/lib/jabrena/302-frameworks-spring-boot-rest/references/302-frameworks-spring-boot-rest.md` — i18n / `MessageSource` integration.
- `.claude/skills/lib/jabrena/314-frameworks-spring-kafka/references/314-frameworks-spring-kafka.md` — consumer error handler + DLT recoverer.
