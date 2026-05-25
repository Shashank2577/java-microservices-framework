---
name: java-multi-tenancy
description: Use whenever working on anything tenant-aware — request handling, DB access, Kafka producer/consumer, scheduled jobs, or onboarding a new tenant. Covers the schema-per-tenant model, TenantContext propagation, Hibernate MultiTenantConnectionProvider, Flyway per-schema migrations, and Kafka tenant header propagation.
---

# Multi-Tenancy — Schema-per-Tenant

The framework defaults to **schema-per-tenant** on Postgres. One database, many schemas. Each tenant has its own copy of all business tables. The `public` schema holds the tenant registry and shared metadata.

## 1. Tenant Registry

```sql
-- in public schema, V001
CREATE TABLE public.tenants (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  slug            VARCHAR(64)  NOT NULL UNIQUE,
  schema_name     VARCHAR(63)  NOT NULL UNIQUE,        -- pg identifier limit
  status          VARCHAR(32)  NOT NULL,               -- PROVISIONING|ACTIVE|SUSPENDED|DEACTIVATED
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT schema_name_format CHECK (schema_name ~ '^t_[a-z0-9_]{1,60}$')
);
```
- `schema_name` is always prefixed `t_` and lowercased — avoids reserved words and ambiguity with `public`.
- `slug` is what humans use (subdomain, header value); `schema_name` is the physical mapping.

## 2. TenantContext

```java
public final class TenantContext {
    private static final ScopedValue<TenantId> CURRENT = ScopedValue.newInstance();
    // (use ScopedValue on JDK 21 preview / 22+; otherwise use ThreadLocal copied via TaskDecorator)

    public static <R> R callWith(TenantId t, Callable<R> c) throws Exception {
        return ScopedValue.where(CURRENT, t).call(c::call);
    }
    public static TenantId current() {
        if (!CURRENT.isBound()) throw new IllegalStateException("No tenant in context");
        return CURRENT.get();
    }
    public static Optional<TenantId> currentOptional() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
```
- Prefer `ScopedValue` (JDK 21) — it propagates correctly across virtual threads.
- Fallback: `ThreadLocal` + `TaskDecorator` for `@Async` propagation + MDC for logging.

## 3. Resolution — Servlet Filter

Resolution order: `X-Tenant-Id` header → JWT claim `tid` → subdomain. First non-empty wins. Reject the request if none resolves to an active tenant.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // after security context, before controllers
class TenantResolverFilter extends OncePerRequestFilter {
    private final TenantRegistry registry;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        var tenant = resolve(req)
            .flatMap(registry::findActive)
            .orElseThrow(() -> new TenantUnresolvedException("no active tenant"));
        try {
            MDC.put("tenant_id", tenant.id().toString());
            TenantContext.callWith(tenant.id(), () -> { chain.doFilter(req, res); return null; });
        } catch (Exception e) { throw new ServletException(e); }
        finally { MDC.remove("tenant_id"); }
    }

    private Optional<TenantSlug> resolve(HttpServletRequest req) {
        var h = req.getHeader("X-Tenant-Id");
        if (h != null && !h.isBlank()) return Optional.of(new TenantSlug(h));
        var jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var tid = jwt.getClaimAsString("tid");
        if (tid != null) return Optional.of(new TenantSlug(tid));
        var host = req.getServerName();
        var sub = host.split("\\.")[0];
        return sub.isBlank() ? Optional.empty() : Optional.of(new TenantSlug(sub));
    }
}
```

## 4. Hibernate Multi-Tenant DataSource

```java
@Configuration
class MultiTenancyConfig {

    @Bean CurrentTenantIdentifierResolver currentTenantResolver() {
        return new CurrentTenantIdentifierResolver() {
            public String resolveCurrentTenantIdentifier() {
                return TenantContext.currentOptional()
                    .map(TenantId::value).map(UUID::toString)
                    .orElse("public");                       // for repos on shared tables
            }
            public boolean validateExistingCurrentSessions() { return true; }
        };
    }

    @Bean MultiTenantConnectionProvider multiTenantConnectionProvider(DataSource ds, TenantRegistry registry) {
        return new AbstractDataSourceBasedMultiTenantConnectionProviderImpl() {
            protected DataSource selectAnyDataSource() { return ds; }
            protected DataSource selectDataSource(Object tenantId) { return ds; }
            public Connection getConnection(Object tenantId) throws SQLException {
                var conn = ds.getConnection();
                var schema = "public".equals(tenantId) ? "public" : registry.schemaFor((String) tenantId);
                try (var st = conn.createStatement()) {
                    st.execute("SET search_path TO " + quote(schema) + ", public");
                }
                return conn;
            }
            public void releaseConnection(Object tenantId, Connection c) throws SQLException {
                try (var st = c.createStatement()) { st.execute("SET search_path TO public"); }
                c.close();
            }
        };
    }
}
```
`application.yml`:
```yaml
spring.jpa.properties.hibernate:
  multiTenancy: SCHEMA
  tenant_identifier_resolver: com.example.config.HibernateCurrentTenantResolver
  multi_tenant_connection_provider: com.example.config.HibernateMultiTenantConnectionProvider
```

## 5. Flyway — Per-Tenant Migrations

- `public` schema: shared metadata (`tenants`, `shedlock`). Migrations in `db/migration/public/`.
- Per-tenant: each tenant's schema gets the same migration set. Migrations in `db/migration/tenant/`.

Per-tenant runner (idempotent — safe to call on every boot and on new-tenant provisioning):
```java
@Service
@RequiredArgsConstructor
class TenantMigrator {
    private final DataSource ds;
    private final TenantRegistry registry;

    public void migrateAll() { registry.allActive().forEach(this::migrate); }

    public void migrate(Tenant t) {
        Flyway.configure()
            .dataSource(ds)
            .schemas(t.schemaName())
            .defaultSchema(t.schemaName())
            .locations("classpath:db/migration/tenant")
            .placeholders(Map.of("tenant_schema", t.schemaName()))
            .createSchemas(true)
            .load()
            .migrate();
    }
}
```
Run shared-schema migration first at app boot; per-tenant migrations on boot **and** on tenant provisioning.

## 6. Onboarding a Tenant

1. POST `/admin/tenants {slug}` → application:
   - Validates slug format.
   - Inserts row in `public.tenants` with `status=PROVISIONING`.
   - Calls `TenantMigrator.migrate(t)`.
   - Seeds bootstrap data (default roles, settings).
   - Sets `status=ACTIVE`.
2. Audit log entry.
3. Optionally publish `tenant.provisioned.v1` event.

## 7. Kafka — Tenant Propagation

### Producer
Inject the tenant header into every record.
```java
@Component
@RequiredArgsConstructor
class TenantAwareKafkaTemplate {
    private final KafkaTemplate<String, byte[]> template;

    public void send(String topic, String key, byte[] payload) {
        var record = new ProducerRecord<>(topic, null, key, payload);
        record.headers().add("X-Tenant-Id", TenantContext.current().value().toString().getBytes(UTF_8));
        template.send(record);
    }
}
```

### Consumer
Set the context before invoking the listener.
```java
@Component
class TenantAwareConsumerInterceptor implements RecordInterceptor<String, byte[]> {
    public ConsumerRecord<String, byte[]> intercept(ConsumerRecord<String, byte[]> r, Consumer<String,byte[]> c) {
        var h = r.headers().lastHeader("X-Tenant-Id");
        if (h == null) throw new IllegalStateException("missing tenant header on " + r.topic());
        var tid = new TenantId(UUID.fromString(new String(h.value(), UTF_8)));
        TenantContext.callWith(tid, () -> r);    // wrap dispatch
        return r;
    }
}
```
(Wire this into the `ConcurrentKafkaListenerContainerFactory`.)

## 8. Scheduled Jobs — Tenant Loop

Tenant-scoped jobs iterate tenants explicitly:
```java
@Scheduled(cron = "${jobs.reconcile.cron}", zone = "UTC")
@SchedulerLock(name = "reconcileAllTenants")
public void run() {
    for (Tenant t : registry.allActive()) {
        TenantContext.callWith(t.id(), () -> { reconcile.run(); return null; });
    }
}
```
Single-tenant cross-cutting jobs (cleanup of `public.audit_log`) run without tenant context.

## 9. Security Notes

- Authorization checks **always** include `tenant_id` predicate, even though `search_path` constrains visibility. Belt and suspenders.
- The `JwtAuthenticationConverter` extracts roles/scopes per tenant from JWT claims.
- Cross-tenant access (support staff acting on tenant X) requires explicit `support:impersonate` scope + audit-logged tenant switch.

## 11. Cost Attribution & Tenant Sizing

"Why is our AWS bill up 40% this quarter?" should be answerable in 5 minutes with per-tenant attribution. Pricing tiers, contract renewals, capacity planning, identifying expensive tenants — all need this data.

### Levels of attribution

| Level | What | How |
| --- | --- | --- |
| Compute (k8s) | CPU/memory by pod, mapped to service | Pod labels + Kubecost / OpenCost |
| DB | Disk + IO per tenant schema | Postgres `pg_database_size('t_<slug>')` + `pg_stat_user_tables` |
| Kafka | Bytes in/out per topic, per tenant header | Custom consumer/producer metrics with `tenant_class` label |
| Storage (S3) | Bytes + requests per tenant prefix | S3 storage lens with tenant-prefix dimension |
| LLM / 3rd party | Tokens + calls per tenant | Already metric'd in `java-patterns-microservices` §9 |
| Email/SMS | Messages per tenant | Metric'd in `java-integration-notifications` |

### Metric labels — `tenant_class`, not `tenant_id`

- Prometheus cardinality matters. **Don't** label every metric with `tenant_id` (thousands of tenants × hundreds of series = OOM).
- Use `tenant_class` (`small` / `medium` / `large` / `enterprise`) for *most* metrics.
- For high-value tenants (top 50 by revenue or top 10 by usage), opt in to full `tenant_id` labeling via a controlled allowlist.

### Tenant sizing

Run a daily job that computes per-tenant resource usage:
- DB schema size (Postgres `pg_database_size`)
- Row count by main aggregate (`orders`, `customers`)
- Last activity timestamp
- Average daily request count (from access logs)

Persist to a `tenant_usage` table (or warehouse). One row per tenant per day. Promote/demote tenant tier based on usage thresholds.

### `tenant_class` derivation

```sql
SELECT
    id,
    CASE
        WHEN db_size_bytes > 100*1024*1024*1024 THEN 'enterprise'
        WHEN db_size_bytes > 10*1024*1024*1024  THEN 'large'
        WHEN db_size_bytes > 1*1024*1024*1024   THEN 'medium'
        ELSE 'small'
    END AS class
FROM tenant_usage
WHERE day = current_date;
```

Update `TenantContext` to expose `tenant_class()` so metric labels can use it without a per-request lookup.

### Per-tenant chargeback math

Daily snapshot writes a `tenant_costs(day, tenant_id, compute_usd, db_usd, storage_usd, kafka_usd, llm_usd, total_usd)` row.

- Compute: pod CPU/memory by request fraction × pod cost.
- DB: schema-size × storage-price + query-count × IO-price approximation (rough; refine over time).
- Kafka: `bytes_in + bytes_out` × broker-cost-per-byte.
- LLM: actual token usage × per-token price by model.
- Storage: actual bytes from S3 × storage tier price.

It doesn't need to be perfect; it needs to be **directionally accurate and consistent**.

### Surfacing cost

- Internal dashboard: top 20 tenants by cost, trend, cost per dollar of revenue.
- Customer-facing usage page (selectively): "your storage used", "your API calls this month".
- Alerts on per-tenant cost anomalies — a sudden 10× spike could be a misbehaving client or a runaway script.

### Tier-based controls

- `small` tier: tighter rate limits, lower SLA targets, shared compute.
- `enterprise` tier: dedicated namespaces, higher rate limits, premium SLA.
- Tier transitions automated where possible; communicated to sales/CS.

### Performance isolation

- Database: per-tenant connection-pool limits via PgBouncer (when one tenant runs away with connections).
- Kafka: per-tenant consumer-group quota.
- LLM: per-tenant token budget (see `java-patterns-microservices` §9.6).
- Background jobs: fair scheduling — round-robin or weighted by tier across tenants.

### Cost optimization patterns

- Inactive tenant policy: tenants with no activity for N days → cold storage tier, reduced replicas, eventual archive.
- Tenant offboarding: data deletion + cost log finalized + final invoice.
- Cold/warm data tiering inside the tenant: archive old `orders` to a slower storage class.

### Multi-region cost

- Cross-region replication ≠ free. Per-tenant region pinning (per `java-multi-tenancy` and `java-data-governance`) keeps costs predictable.
- Multi-region active-active: 2× compute + 2× storage + replication bandwidth. Reserve for enterprise-tier tenants with explicit need.

### Observability

- `tenant_db_size_bytes{tenant_class}` gauge — daily snapshot.
- `tenant_api_requests_total{tenant_class, route, status}` — RED for top tiers, aggregated for others.
- `tenant_cost_usd_total{tenant_class, day}` daily counter.
- `tenant_cost_anomaly_total` — alert source.

## 12. Control-Plane / Standard Tenant

In a multi-tenant SaaS, there's always one **privileged tenant** that is *itself the dashboard for managing all other tenants*. Common names: control plane tenant, system tenant, standard tenant, root tenant, platform tenant. Pick one — this framework uses **`standard tenant`** (slug = `standard`, schema = `t_standard`).

It's a real tenant in `public.tenants`. It uses the same schema-per-tenant machinery. But:
- It's bootstrapped at first deploy, never deleted.
- Its users see admin UIs that other tenants don't.
- Its API surface includes the **tenant management** endpoints (provision, suspend, billing rollups).
- Cross-tenant operations are allowed *only* from this tenant + with special scopes.

### Bootstrapping the standard tenant

On first application startup (or via a migration):

```java
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class StandardTenantBootstrapper implements ApplicationRunner {
    private final TenantRegistry registry;
    private final TenantMigrator migrator;

    public void run(ApplicationArguments args) {
        if (registry.findActive(new TenantSlug("standard")).isPresent()) return;

        var standard = new Tenant(TenantId.newId(), new TenantSlug("standard"),
            "t_standard", TenantStatus.ACTIVE, isStandard: true);
        registry.save(standard);
        migrator.migrate(standard);
        seedSystemRoles(standard);    // PLATFORM_ADMIN, SUPPORT, BILLING_OPS
        seedBootstrapUser(standard);  // from env var STANDARD_ADMIN_EMAIL
    }
}
```

Idempotent: re-running is a no-op once standard exists. Add `is_standard BOOLEAN NOT NULL DEFAULT false` to `public.tenants` so code can identify it without slug comparison.

### What lives in the standard tenant

| Feature | Why in standard, not regular |
| --- | --- |
| Tenant provisioning UI | Operates on `public.tenants` and triggers per-tenant migrations |
| Billing rollups dashboard | Reads `tenant_costs` across all tenants |
| Customer-support impersonation | Switches context into a target tenant (audit-logged) |
| Health overview (per-tenant DLT lag, error rates) | Cross-tenant aggregation |
| Feature-flag management | Per-tenant flag rollouts |
| Audit log viewer (any tenant by support) | Privileged read |
| User-and-role admin for a customer tenant | Support-driven; otherwise tenant's own admin does it |

### What does NOT belong in the standard tenant

- Regular business data (orders, customers' invoices) — those live in customer tenants.
- Code-level "platform" data (config, secrets) — those are in their own systems (Vault, k8s ConfigMap).
- Generic tenant features that customers also use — those go in regular tenants.

### Special roles + scopes

System roles seeded in the standard tenant only:
- `PLATFORM_ADMIN` — can provision/suspend tenants, manage system roles, view all audit logs
- `SUPPORT` — can impersonate customer tenants (with audit), view (not edit) customer data
- `BILLING_OPS` — read all `tenant_costs`, run invoices

Special scopes JWT-issued to these roles only: `platform:tenants:write`, `platform:tenants:read`, `platform:audit:read`, `platform:impersonate`, `platform:billing:write`.

### Cross-tenant access — only from standard

Repository methods that need to operate across tenants live in a dedicated `PlatformRepository` interface, exposed only to use-cases running in the standard tenant. They explicitly take `TenantId` as a parameter (no implicit context).

```java
public interface PlatformTenantRepository {
    List<Tenant> all();
    Optional<Tenant> findById(TenantId id);
    void create(Tenant t);
    void suspend(TenantId id, String reason);
}

@Service
@PreAuthorize("hasAuthority('SCOPE_platform:tenants:write')")
class SuspendTenantUseCase {
    private final PlatformTenantRepository platformRepo;

    @Transactional
    public void suspend(TenantId target, String reason) {
        if (!isStandardTenant(TenantContext.current()))
            throw new AccessDeniedException("cross-tenant ops only from standard");
        platformRepo.suspend(target, reason);
        audit.record("tenant.suspended", Map.of("tenant_id", target, "reason", reason));
        events.publish(new TenantSuspended(target, reason, Instant.now()));
    }
}
```

The `isStandardTenant` check is belt-and-suspenders with the scope check.

### Support impersonation

Most cross-tenant access by humans is **impersonation**: a SUPPORT user temporarily acts as someone in a customer tenant.

Mechanics:
1. SUPPORT user POSTs `/v1/platform/impersonate { target_tenant, target_user, reason }`.
2. Service mints a short-lived impersonation JWT (15 min): `sub = target_user`, `tid = target_tenant`, custom claim `impersonating_by = support_user_id`.
3. UI uses that token; every action audited with both identities.
4. Token cannot be refreshed; must re-request after 15 min.

### Standard tenant in the gateway

Routing rules:
- `/platform/**` → only accepts JWTs with `tid = standard tenant id`.
- All other `/v1/**` routes → reject JWTs that *would* allow cross-tenant access from a non-standard tenant.

### Schema migrations for standard

The standard tenant gets:
- All regular tenant migrations (it's a tenant, after all — needs `users`, `roles`, etc.).
- PLUS additional migrations in `db/migration/standard/` for platform-only tables (e.g., `support_tickets`, `platform_announcements`).

Apply order: shared `public` → standard-specific → regular per-tenant.

### Audit — every standard-tenant action

The framework's `audit_log` is hash-chained (see `java-data-governance` §5). Every cross-tenant action by a PLATFORM_ADMIN / SUPPORT user gets an audit row: `actor` (support user), `target_tenant`, `action`, `before`/`after`, `reason` (mandatory text). Auditors look here first.

### Onboarding a new tenant from the standard dashboard

UI: PLATFORM_ADMIN clicks "Create tenant" → form → backend runs the onboarding flow (per existing §6). The form must enforce: slug format, a real owner email, initial plan (drives rate limit / cost class), region (drives data residency).

### Migration safety

A bad migration applied to standard tenant first (canary) catches issues before customer tenants. Make this part of every migration runbook:

1. Apply to standard schema.
2. Verify (smoke test against standard's data).
3. Apply to remaining tenants in waves.

## 13. Anti-patterns — Refuse

- Setting `search_path` from controller code or use-case (only the connection provider does this).
- A `tenant_id` column in tenant-schema tables (redundant; the schema *is* the boundary).
  - Exception: keep `tenant_id` in cross-tenant tables under `public` (audit, billing rollups).
- Caching across tenants without a tenant-scoped key (Caffeine/Redis keys must include `tenantId`).
- Reading from one tenant's schema and writing to another in the same TX.
- Background work that "defaults to tenant X" — always require explicit context.
- High-cardinality metrics labeled by `tenant_id` (Prometheus blow-up); use `tenant_class` + allowlist.
- No daily usage snapshot — can't answer "why is the bill up".
- Tier transitions done manually (drift, missed upgrades).
- Cost data living in vendor consoles only (no internal source of truth).
- Charging customers by usage without instrumentation supporting the bill.
- Treating all tenants identically when usage varies 1000×.
- Cross-tenant SQL from a non-standard tenant.
- Platform endpoints reachable from customer-tenant tokens.
- Standard tenant features mixed into regular tenant code (no `if (isStandardTenant(...))` checks in business logic).
- Impersonation tokens without a hard expiry.
- Direct DB writes to manage tenants (always through `PlatformTenantRepository` + use-case).
- "We'll add audit later" for platform actions — this is exactly where audit matters most.

## Pre-merge checklist

- [ ] New tables / topics carry `tenant_id` for attribution.
- [ ] Metrics use `tenant_class`, not `tenant_id`, unless on the allowlist.
- [ ] Daily usage snapshot covers any new resource type.
- [ ] Tier-based rate limit / quota wired for new endpoints.
- [ ] If touching cross-tenant code: gated by standard-tenant check + scope check.
- [ ] Audit event emitted with `reason`.
- [ ] New platform endpoint added to gateway `/platform/**` route, JWT validates `is_standard`.
