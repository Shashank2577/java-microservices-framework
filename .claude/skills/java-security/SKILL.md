---
name: java-security
description: Use whenever touching authentication, authorization, secrets, CORS, headers, rate limiting, input handling at the edge, or anything OWASP-related. Covers OAuth2 Resource Server + JWT with tenant claim, method security, CORS/CSRF policy, security headers, secrets management (Vault/k8s), mTLS, rate limiting, and OWASP Top 10 mapped to Spring defenses.
---

# Security — Defense in Depth

JWT validation alone is not security. This skill defines the framework's **defense-in-depth** posture across the request lifecycle, secrets, and code.

## 1. Authentication — OAuth2 Resource Server + JWT

Every service is a **resource server**. Tokens are issued by the IdP (Keycloak/Auth0/Okta) and validated locally. Services do not issue tokens.

```yaml
spring.security.oauth2.resourceserver:
  jwt:
    issuer-uri: ${IDP_ISSUER_URI}                 # discovery: /.well-known/openid-configuration
    # jwks cached automatically; rotation handled by the IdP
```

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity                            // for @PreAuthorize
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter conv) throws Exception {
        return http
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv)))
            .csrf(csrf -> csrf.disable())                 // stateless API
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
            .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthConverter() {
        var conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            var roles = jwt.getClaimAsStringList("roles");
            var scopes = jwt.getClaimAsStringList("scope");
            return Stream.concat(
                Optional.ofNullable(roles).orElse(List.of()).stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)),
                Optional.ofNullable(scopes).orElse(List.of()).stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
            ).toList();
        });
        return conv;
    }
}
```

### JWT claims this framework expects

| Claim   | Meaning                                          |
| ------- | ------------------------------------------------ |
| `sub`   | User ID (stable)                                 |
| `tid`   | **Tenant ID** — drives `TenantContext`           |
| `roles` | App-level roles                                  |
| `scope` | OAuth scopes (space-separated)                   |
| `exp`   | Expiry                                           |

Reject tokens missing `tid`. The `TenantResolverFilter` (see `java-multi-tenancy`) reads `tid` after auth.

## 2. Authorization — Layered

| Layer            | Mechanism                                                          |
| ---------------- | ------------------------------------------------------------------ |
| Route            | `authorizeHttpRequests` in `SecurityFilterChain` for coarse rules. |
| Method           | `@PreAuthorize("hasRole('ADMIN')")` on use-cases / controllers.    |
| Tenant ownership | Domain check: load resource by `(tenantId, id)`. **Never** by `id` alone. |
| Row-level        | DB `tenant_id` predicate + Postgres RLS as belt-and-suspenders (optional). |

```java
@PreAuthorize("hasAuthority('SCOPE_orders:write')")
public OrderId place(PlaceOrderCommand cmd) { ... }
```

**Never** rely on UI hiding a button. Every server-side action checks authorization.

## 3. CORS — Explicit Allowlist

```yaml
app.cors:
  allowed-origins: https://app.example.com,https://admin.example.com
  allowed-methods: GET,POST,PUT,PATCH,DELETE,OPTIONS
  allowed-headers: Authorization,Content-Type,Idempotency-Key,X-Tenant-Id
  exposed-headers: Trace-Id,Idempotency-Replayed
  max-age: 3600
```

- **Never** `Access-Control-Allow-Origin: *` for any authenticated endpoint.
- Per-tenant origins handled at the gateway, not in each service.

## 4. CSRF

Disabled for stateless JSON APIs (tokens carried in `Authorization` header, never in cookies). If you ever introduce cookie-based auth, **re-enable CSRF** for those endpoints — no exceptions.

## 5. Security Headers — Mandatory

Set by Spring Security in §1. Required minimums:

| Header                          | Value                                                       |
| ------------------------------- | ----------------------------------------------------------- |
| `Strict-Transport-Security`     | `max-age=31536000; includeSubDomains`                       |
| `Content-Security-Policy`       | API: `default-src 'none'; frame-ancestors 'none'`. Tighter on Swagger UI route. |
| `X-Content-Type-Options`        | `nosniff`                                                   |
| `X-Frame-Options`               | `DENY`                                                      |
| `Referrer-Policy`               | `strict-origin-when-cross-origin`                           |
| `Cache-Control` (sensitive endpoints) | `no-store`                                            |

## 6. Secrets Management

| Where it goes                | What                                                              |
| ---------------------------- | ----------------------------------------------------------------- |
| Vault / AWS SM / k8s Secret  | DB passwords, JWT signing keys (none — we validate, not sign), API keys, encryption keys |
| Env var injected at runtime  | The above, surfaced as `SPRING_DATASOURCE_PASSWORD`, etc.        |
| Spring `@Value` / `@ConfigurationProperties` | Read at startup. **Never log the value.**         |
| `application-*.yml`          | Non-secret config only.                                           |
| Git                          | **Nothing secret. Ever.** Pre-commit `gitleaks` hook required.    |

Rotation:
- Use short-lived dynamic credentials (Vault DB engine) when possible.
- Otherwise, rotate quarterly minimum; document the procedure in a runbook.

## 7. mTLS

In a service mesh (Istio/Linkerd) — enabled cluster-wide; nothing changes in app code. Outside a mesh — terminate TLS at the gateway, use `NetworkPolicy` to constrain east-west traffic, mount client certs via secrets if a downstream requires them.

## 8. Rate Limiting

| Tier        | Mechanism                                                                 |
| ----------- | ------------------------------------------------------------------------- |
| Per-IP      | Gateway (Spring Cloud Gateway + Redis-backed rate limiter).               |
| Per-tenant  | Gateway, tenant-keyed. Default: 100 req/s, configurable per-tenant.       |
| Per-route   | Bucket4j inside the service for expensive routes.                         |
| Outbound    | Resilience4j `RateLimiter` on HTTP clients to be a good citizen downstream. |

Hit limit → respond `429` with `Retry-After` header and the standard problem body (`code: "RATE_LIMIT_EXCEEDED"`).

## 9. Input Validation — Belt and Suspenders

| Boundary             | Defense                                                        |
| -------------------- | -------------------------------------------------------------- |
| HTTP request body    | Jakarta Validation (`@Valid`, `@NotBlank`, `@Size`, custom).   |
| Path / query params  | Annotations on controller method args (`@Min`, `@Pattern`).    |
| Database             | Parameterized queries only. `PreparedStatement` / JPA. **Never** string-concat SQL. |
| Logs                 | Redact known-sensitive fields before logging.                  |
| Outbound HTTP        | Whitelist destinations (configured allowlist).                 |
| Deserialization      | Jackson with `@JsonTypeInfo` only for known sealed type hierarchies. **Never** `enableDefaultTyping`. |
| File uploads         | Type check by content (magic bytes), not extension. Size limit. Virus scan on user-content paths. |

## 10. OWASP Top 10 — Mapped to Defenses

| OWASP (2021)                          | Defense in this framework                                                  |
| ------------------------------------- | -------------------------------------------------------------------------- |
| A01 Broken Access Control             | Method security + tenant predicate in queries + explicit ownership checks. |
| A02 Cryptographic Failures            | TLS everywhere; HSTS; Argon2id / bcrypt(strength≥12) for any password; AES-GCM for at-rest crypto. |
| A03 Injection                         | Parameterized queries; Jakarta Validation; no `Runtime.exec` from user input. |
| A04 Insecure Design                   | Hexagonal + DDD bounded contexts (see `java-architecture`).                |
| A05 Security Misconfiguration         | Spring Boot Actuator: only `health`/`info`/`prometheus` exposed; everything else internal. Docker base image scanned. |
| A06 Vulnerable Components             | Renovate/Dependabot + OWASP dep-check in CI (see `java-code-quality`).     |
| A07 Identity/Auth Failures            | OAuth2 OIDC with IdP; short-lived tokens; refresh-token rotation; brute-force throttling at IdP. |
| A08 Software/Data Integrity Failures  | Signed container images (cosign); supply-chain scan; Jackson safe-deserialization. |
| A09 Logging/Monitoring Failures       | Structured logs, traces, metrics, alerts (see `java-observability`).       |
| A10 SSRF                              | Allowlist outbound HTTP destinations; never call URLs from user input.     |

## 11. Cryptography Rules

- TLS 1.2+ only. Disable TLS 1.0/1.1.
- Passwords: Argon2id (preferred) or BCrypt strength ≥12. Use `PasswordEncoder` bean, never roll your own.
- At-rest encryption: AES-256-GCM via `javax.crypto`. Key management via Vault/KMS. **Never** hardcode keys.
- Random: `SecureRandom`. Never `java.util.Random` for security-relevant choices.

## 12. Encryption at Rest & Key Rotation

### Three tiers of at-rest encryption

| Tier | Mechanism | Use for |
| ---- | --------- | ------- |
| Platform | Postgres TDE (RDS/CloudSQL), EBS volume encryption, S3 SSE-S3 | All data at rest, baseline. **Mandatory.** |
| Column-level (envelope) | AES-256-GCM + per-tenant Data Encryption Key (DEK) + KMS-wrapped DEK | Direct PII (email, SSN, account number) where field-level isolation needed |
| Per-record DEK | KMS-wrapped DEK per user/record | Highest sensitivity (health, gov ID); enables crypto-shred per subject |

### Envelope encryption — concrete pattern

```java
public interface Crypter {
    byte[] encrypt(byte[] plaintext, TenantId tenant);    // returns ciphertext including IV + wrapped DEK ref
    byte[] decrypt(byte[] ciphertext, TenantId tenant);
}
```

- Tenant-scoped DEK generated on tenant onboarding, wrapped by org Master Key in KMS (AWS KMS / GCP KMS / Vault Transit).
- DEK is cached in process (Caffeine, TTL 10 min, max size = active tenant count); revoke on tenant disable.
- Ciphertext layout: `[version|key_id|iv(12)|ciphertext|tag(16)]`.
- Use `javax.crypto.Cipher` with `AES/GCM/NoPadding`. **Always** unique IV (12-byte random); never reuse.
- Authenticated additional data (AAD): include `tenant_id || field_name` so a ciphertext can't be moved across fields/tenants.

### Field-level encryption in JPA

- `@Convert(converter = EncryptedStringConverter.class)` on encrypted columns.
- Converter pulls `TenantContext.current()` to scope the key.
- Stores ciphertext as `bytea`; original column type lost in DB (intentional — searchability sacrificed; for "must search" use deterministic encryption or a separate searchable hash column).

### Searchable encryption — caveats

- Deterministic AES (same input → same ciphertext) enables equality search but **leaks frequencies**. Use only for high-cardinality fields.
- Hash-based (`HMAC(field, tenant_secret)`) for equality without storing the value — useful for email lookup without storing plaintext.
- Range queries on encrypted data: don't. If you need them, the field is the wrong candidate.

### Key rotation

Mandatory cadences:
- KMS Master Key: rotated annually (KMS auto-rotation).
- Tenant DEKs: rotated annually OR on suspected compromise.
- JWT signing keys: rotated quarterly (IdP responsibility); your service validates against JWKS, so rotation is transparent.
- Vault encryption keys: per Vault docs.

Rotation procedure for tenant DEKs:
1. Generate new DEK v2 in KMS, mark v1 as deprecated.
2. Rewrite path: app encrypts new writes with v2; reads support v1 and v2 (key_id in ciphertext header).
3. Background re-encryption job (see `java-migrations` §9 backfill pattern) reads every encrypted column, decrypts with v1, re-encrypts with v2.
4. After verified complete, retire v1 (KMS schedule deletion 30-day window).
5. Verify zero v1 ciphertexts remain.

### Crypto-shred for right-to-erasure

- Destroying a DEK = all ciphertexts encrypted with it become unrecoverable.
- Tenant-level shred: destroy that tenant's DEK → tenant data inaccessible everywhere, including backups.
- Per-record DEK shred: same but per subject (heavier ops, max isolation).
- KMS keys cannot be "really deleted"; they go through a deletion window. Document this for compliance.

### Secrets in env vars vs at-rest

- Env-var secrets in containers are at-rest in k8s etcd / Vault. Ensure etcd is encrypted (cluster setup concern; verify).
- `application-*.yml` never contains secrets (see `java-config`).

### Cross-reference

Link to `java-data-governance` (when written) for crypto-shred-driven erasure.

## 13. Anti-patterns — Refuse

- `@PreAuthorize("permitAll")` on a non-public endpoint.
- CORS `*` on an authenticated endpoint.
- Logging full request bodies.
- Storing JWT in `localStorage` (browser concern, but if your SDK ships, default to `httpOnly` cookies + SameSite=strict).
- Custom crypto.
- "Disable security for tests" — instead, use a test JWT issuer (Keycloak Testcontainer or in-process token factory).
- Granting wildcard scopes to internal services. Each service-account token has minimal scope.
- Trusting headers from clients (e.g., `X-User-Id`) — derive identity from JWT only.
- Encrypting at app level without platform encryption (skips the cheap layer).
- AES-CBC for new code (no auth tag); use AES-GCM.
- IV reuse.
- Hardcoded keys.
- Decrypting in services that don't need plaintext (over-broad blast radius).
- "Rotate keys when convenient" — pin a cadence; track in `key_rotation_log`.
- Treating KMS key deletion as instant.

## 14. Pre-Merge Security Checklist

- [ ] New endpoints require auth unless explicitly public; explicit allowlist updated.
- [ ] `@PreAuthorize` on every state-changing use-case.
- [ ] Tenant predicate in every repository query.
- [ ] No secrets in diff (`gitleaks` clean).
- [ ] Dependency-check has no new high/critical CVEs.
- [ ] Input validation on all controller args.
- [ ] No new direct SQL string-concat.
- [ ] Logging redaction in place for any new sensitive field.

## 15. Reference (deep dive)

- `.claude/skills/lib/jabrena/304-frameworks-spring-boot-security/references/304-frameworks-spring-boot-security.md` — Spring Security configuration, filter chain, method security, OAuth2 resource server, headers, password encoding
- `.claude/skills/lib/jabrena/124-java-secure-coding/references/124-java-secure-coding.md` — Java-level secure coding: input validation, deserialization safety, crypto, randomness, file handling, JNDI/LDAP
- `.claude/skills/lib/jabrena/126-java-exception-handling/references/126-java-exception-handling.md` — exception message hygiene, security exception patterns
- `.claude/skills/lib/jabrena/303-frameworks-spring-boot-validation/references/303-frameworks-spring-boot-validation.md` — Jakarta Validation depth
