---
name: java-config
description: Use whenever defining application configuration, profiles, externalized properties, secrets, or feature flags. Covers Spring profiles strategy, externalized config order, @ConfigurationProperties with validation, secrets via Vault/AWS SM/k8s Secrets, and feature flags via Unleash/Togglz with a flag-lifecycle policy.
---

# Configuration Management

Config decisions made wrong on day 1 cost months to fix. This skill is the framework's policy on **where config lives, how it's loaded, and how it changes without redeploying**.

## 1. Profiles — One per Environment

| Profile  | Purpose                                                                                |
| -------- | -------------------------------------------------------------------------------------- |
| `local`  | Developer machine. Docker-compose dependencies. Loose defaults. Verbose logs.          |
| `test`   | CI test runs. Testcontainers. Deterministic clocks. **Never** activated in deployed envs. |
| `dev`    | Shared dev cluster.                                                                    |
| `stg`    | Staging — prod-shaped, low traffic.                                                    |
| `prod`   | Production.                                                                            |

Activate via `SPRING_PROFILES_ACTIVE=stg`. Never hardcode an active profile in code.

Layered config files:
- `application.yml`             — non-secret defaults that apply everywhere.
- `application-<profile>.yml`   — per-env overrides (non-secret).
- Env vars                      — secrets and per-instance overrides; **always** win over files.

## 2. The Config Resolution Order

Spring's order (highest precedence first), applied in this framework:
1. Command-line args (`--server.port=8081`).
2. **Env vars** (e.g., `SPRING_DATASOURCE_PASSWORD`). All secrets land here.
3. `application-<profile>.yml` (in resources).
4. `application.yml` (in resources).
5. `@PropertySource` declarations.

Rules:
- Secrets **only** via env vars, sourced from Vault/AWS SM/k8s Secrets at pod startup.
- Non-secret tunables (timeouts, batch sizes, feature flags) **only** in YAML — version-controlled, reviewable.
- Anything that needs to change *without redeploy* belongs in a config service or feature flag, **not** in YAML.

## 3. `@ConfigurationProperties` — Always Typed, Always Validated

```java
@ConfigurationProperties(prefix = "app.pricing")
@Validated
public record PricingProps(
    @NotBlank String baseUrl,
    @Positive @DurationMin(seconds = 1) Duration timeout,
    @Min(0) @Max(10) int retries,
    @NotNull Resilience resilience
) {
    public record Resilience(
        @NotBlank String circuitBreakerName,
        @Min(1) int slidingWindowSize,
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRateThreshold) {}
}
```

`application.yml`:
```yaml
app.pricing:
  base-url: https://pricing.internal
  timeout: 2s
  retries: 3
  resilience:
    circuit-breaker-name: pricing
    sliding-window-size: 20
    failure-rate-threshold: 0.5
```

Rules:
- Records, not Lombok `@Data` classes — immutable.
- `@Validated` → invalid config fails the app at startup. **Fail fast**.
- Group related settings under a single prefix. Don't sprinkle `@Value` everywhere.
- Use `Duration` for time, `DataSize` for bytes — Spring parses `2s`/`200ms`/`64KB`.

`@Value` is acceptable only for one-off ad-hoc reads in `@Configuration` classes. In application code, always `@ConfigurationProperties`.

## 4. Secrets — Concrete Patterns

### Kubernetes
- Secrets as env vars via `envFrom: secretRef`.
- Read-only mounted secrets for cert files (TLS, JKS).
- **Never** `data:` in plain ConfigMap for anything sensitive.

### Vault (HashiCorp)
- `vault-k8s` injector or CSI driver mounts secrets as files / env vars.
- Use the **database secrets engine** for short-lived dynamic credentials when possible:
  - Each pod gets a unique 1h-TTL DB user. Rotation is automatic.
- Spring Cloud Vault is acceptable but adds runtime dependency; prefer mount-as-env approach for simplicity.

### AWS Secrets Manager / Parameter Store
- IRSA (IAM Roles for Service Accounts) for k8s; instance role for VMs.
- Use AWS SDK `SecretsManagerClient` at startup, or external-secrets-operator to project into k8s Secrets.

### Never
- Secrets in Git, even encrypted (`*.enc`) unless using SOPS/sealed-secrets with strict review.
- `.env` files committed.
- Secrets in container images.
- Secrets logged. The `application-*.yml` is read at startup; **don't log it**.

## 5. Per-Tenant Config

Most settings are global; some need tenant overrides (feature toggles, rate limits, branding).

Pattern: a `tenant_settings` table in the tenant's schema (or `public` for shared), loaded into a request-scoped or caffeine-cached `TenantSettings` bean keyed by `tenant_id`.

```sql
CREATE TABLE tenant_settings (
    tenant_id  UUID PRIMARY KEY REFERENCES public.tenants(id),
    settings   JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Read via `TenantSettingsService.get(tenant)`. Invalidate cache on update event (Kafka).

**Never** stuff per-tenant config in `application.yml`.

## 6. Feature Flags

| Tool       | Use                                                                |
| ---------- | ------------------------------------------------------------------ |
| **Unleash** (self-hosted) | Default. Per-tenant gradual rollout, kill-switch, A/B.|
| **LaunchDarkly**          | When the team prefers SaaS with rich targeting.       |
| **Togglz**                | Lightweight; fine for small fleets.                   |

Wire a `FeatureToggle` port; adapter for whichever tool. Don't sprinkle vendor SDK calls everywhere.

```java
public interface FeatureToggle {
    boolean isEnabled(String flag);
    boolean isEnabledForTenant(String flag, TenantId t);
}

@Service
class PricingV2 {
    private final FeatureToggle flags;
    public Quote quote(Order o) {
        if (flags.isEnabledForTenant("pricing.v2", TenantContext.current())) {
            return v2Engine.quote(o);
        }
        return v1Engine.quote(o);
    }
}
```

### Flag lifecycle (mandatory)
Every flag has a **birth certificate AND a death warrant**:
- Created with: owner, target rollout date, **planned cleanup ticket linked**.
- Tracked in `feature-flags.md` in the repo.
- Cleaned up within 2 sprints of full rollout (100% on or 0% on).

A flag older than 90 days at 100% without a cleanup ticket is technical debt. The CI report lists stale flags.

## 7. Spring Cloud Config — When to Use

Use Spring Cloud Config (or Consul KV) when:
- You have **non-secret runtime config** that must change without restart, *and*
- More than ~5 services need to read the same change.

For one or two services, `application.yml` + redeploy is simpler. For secrets, **use Vault, not Config Server**. For per-tenant toggles, **use feature flags**.

## 8. Hot Reload

- `@RefreshScope` from Spring Cloud — supported but use sparingly (debug hell with refreshed beans).
- For most settings: change YAML → redeploy. Modern pod restarts are <30s; this is fine.
- For truly dynamic settings: feature flags > `@RefreshScope`.

## 9. Configuration Tests

- Unit-test `@ConfigurationProperties` binding with `@EnableConfigurationProperties` + `ApplicationContextRunner`.
- Validate startup fails with bad config (e.g., negative timeout).

```java
@Test void rejects_negative_retries() {
    new ApplicationContextRunner()
        .withUserConfiguration(PricingPropsConfig.class)
        .withPropertyValues("app.pricing.base-url=http://x", "app.pricing.timeout=2s",
                            "app.pricing.retries=-1",
                            "app.pricing.resilience.circuit-breaker-name=x",
                            "app.pricing.resilience.sliding-window-size=20",
                            "app.pricing.resilience.failure-rate-threshold=0.5")
        .run(ctx -> assertThat(ctx).hasFailed());
}
```

## 10. Anti-patterns — Refuse

- `@Value("${app.foo}")` scattered across the codebase.
- Secrets in `application.yml` / `application-prod.yml`.
- A "DEV/PROD" boolean in Java code (`if (env.equals("prod"))`). Use Spring profiles.
- Reading env vars manually via `System.getenv()` in business code.
- Per-tenant settings hardcoded as `if (tenantId.equals(...))`.
- Feature flag with no owner, no cleanup ticket, ≥90 days old at 100%.
- "Restart the app to change the log level" — use Actuator `/actuator/loggers` (admin-only).

## 11. Pre-Merge Checklist

- [ ] New properties under a typed `@ConfigurationProperties` record with `@Validated`.
- [ ] Defaults in `application.yml`, overrides in `application-<profile>.yml`.
- [ ] No secrets in YAML; secrets via env vars from Vault/Secrets Manager.
- [ ] New flag has owner + cleanup ticket.
- [ ] Tests for properties binding + validation failures.

## 12. Reference

- `.claude/skills/lib/jabrena/301-frameworks-spring-boot-core/references/301-frameworks-spring-boot-core.md` — `@ConfigurationProperties`, `@Validated`, profiles, conditional config
