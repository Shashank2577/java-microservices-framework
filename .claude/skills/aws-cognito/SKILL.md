---
name: aws-cognito
description: Use whenever touching authentication, login flows, JWT validation, SAML federation, MFA, password policy, the AuthenticationService, the token-exchange endpoint, Cognito Terraform modules, or the local JWT fixture. Covers User-Pool-per-tenant model, platform-vs-tenant pool split, RFC 8693 token-exchange flow, Spring Security multi-issuer resolution, SAML JIT provisioning, MFA, lockout, the PostConfirmation Lambda → Kafka bridge, CloudTrail → AuditService routing, and LocalStack workarounds. Per ADR-016.
---

# AWS Cognito — Identity Provider Integration

Per [ADR-016], **AWS Cognito is the sole IdP** with **one User Pool per tenant** plus a **platform User Pool**. Keycloak is removed from consideration. This skill is the implementation manual for AuthenticationService, AuthorizationEngine, and every Spring Security Resource Server in the platform.

## 1. The Model — Why User-Pool-per-Tenant

| Property | Effect |
| -------- | ------ |
| **Identity isolation** | A Cognito misconfig on tenant A cannot leak to tenant B. Defense-in-depth alongside [ADR-004] database-per-tenant. |
| **Per-tenant SAML IdP** | Each tenant uploads their own metadata (Okta/Azure AD/OneLogin). No multi-IdP-per-pool gymnastics. |
| **Per-tenant password policy** | Cognito policy is per pool. Composes with BRD-05 "strictest-policy-wins" via platform-layer aggregation. |
| **Per-tenant MFA settings** | TOTP enforced by default; tenant admin can raise to TOTP + WebAuthn. |
| **Per-tenant audit trail** | CloudTrail events filterable per pool — SOC 2 evidence is cleanly partitionable. |
| **Clean offboarding** | Delete the User Pool → all identity data is gone. |

**Cost:** Cognito MAU pricing per pool. With per-tenant pools, cost attribution maps directly to the `Tenant` tag ([aws-platform] §19). Default quota is 1,000 pools/account — request increase to 10,000+ before tenant count justifies.

## 2. Platform Pool vs Tenant Pool

```
                ┌─────────────────────────┐
                │   Platform User Pool    │   issues Platform Token
                │   - Stores user identity│   custom claim: assigned_tenants[]
                │   - MFA enforced        │   used for SysAdmin Tools & tenant switcher
                └────────────┬────────────┘
                             │
              RFC 8693 token-exchange in AuthenticationService
                             │
              ┌──────────────┴──────────────┐
              ▼                              ▼
   ┌─────────────────────┐         ┌─────────────────────┐
   │ Tenant Pool — Acme  │         │ Tenant Pool — Warby │   each issues Tenant Token
   │ - SAML IdP (Okta)   │         │ - SAML IdP (Azure)  │   custom claims: tid, roles[], permissions[]
   │ - TOTP MFA          │         │ - TOTP + WebAuthn   │   used for ALL business APIs
   │ - Lockout 5 / 15min │         │ - Lockout 3 / 30min │
   └─────────────────────┘         └─────────────────────┘
```

- The **Platform Token** authorizes platform-level operations (tenant switcher, SysAdmin) and is the source for the token exchange.
- The **Tenant Token** authorizes business APIs. APIGateway validates it on every business request.
- A user with access to N tenants has N user records (one per tenant pool) plus 1 in the platform pool. `UserTenantAssignment` in the platform DB maps these.

## 3. Spring Security — Multi-Issuer Resource Server

Each service is a Resource Server. The hard part: each tenant's tokens are signed by a different pool with a different issuer URI and a different JWK set. We resolve issuer dynamically per request.

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class CognitoResourceServerConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http,
                            JwtAuthenticationConverter conv,
                            JwtIssuerAuthenticationManagerResolver resolver) throws Exception {
        return http
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(resolver))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .build();
    }

    @Bean
    JwtIssuerAuthenticationManagerResolver issuerResolver(TenantIssuerRegistry registry,
                                                          JwtAuthenticationConverter conv) {
        return new JwtIssuerAuthenticationManagerResolver(issuer -> {
            // Reject any issuer URI we don't recognize as a known tenant pool
            if (!registry.isKnownIssuer(issuer)) {
                throw new InvalidBearerTokenException("Unknown issuer: " + issuer);
            }
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);  // pulls JWKS, caches
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
            provider.setJwtAuthenticationConverter(conv);
            return provider::authenticate;
        });
    }
}
```

### TenantIssuerRegistry — single source of truth

```java
@Component
public class TenantIssuerRegistry {
    private final TenantRepository tenants;
    private final Cache<String, Boolean> issuerCache;  // Caffeine, TTL 5 min

    public boolean isKnownIssuer(String issuer) {
        return issuerCache.get(issuer, this::lookup);
    }

    private Boolean lookup(String issuer) {
        // issuer format: https://cognito-idp.<region>.amazonaws.com/<pool-id>
        String poolId = issuer.substring(issuer.lastIndexOf('/') + 1);
        return tenants.existsByCognitoPoolId(poolId) || POOL_REGISTRY.isPlatformPool(poolId);
    }
}
```

### JWK Cache Behavior

Per ADR-016:
- **24-hour cache** of JWK sets, keyed by issuer.
- **Refresh on miss** — if a token's `kid` is not in cache, fetch and re-cache.
- **Cold-start stampede protection:** [aws-platform] §22 — wrap `JwtDecoders.fromIssuerLocation` with a `LoadingCache` keyed on issuer, so concurrent first requests share one fetch.

```java
@Bean
Cache<String, JwtDecoder> decoderCache() {
    return Caffeine.newBuilder()
        .maximumSize(1_000)              // pool quota ceiling
        .expireAfterWrite(Duration.ofHours(24))
        .refreshAfterWrite(Duration.ofHours(12))
        .build();
}
```

### Required Claims

Tokens MUST carry:

| Claim | Source | Purpose |
| ----- | ------ | ------- |
| `iss` | Cognito | Identifies the pool → tenant |
| `sub` | Cognito | Stable user ID |
| `tid` | Custom attribute on tenant pool | Drives `TenantContext` per [java-multi-tenancy] |
| `roles` | Custom attribute / Cognito groups | App-level roles |
| `permissions` | Custom attribute | Fine-grained perms (BRD-05) |
| `assigned_tenants` | Platform pool only | List of tenant slugs user can access |
| `exp` | Cognito | 60-min token TTL |

`TenantResolverFilter` reads `tid` after Spring authentication populates the SecurityContext. Reject tokens missing `tid` (per [java-security] §1).

## 4. The Token-Exchange Flow (RFC 8693)

Implemented in **AuthenticationService**. Endpoint: `POST /oauth2/token` with `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`.

```
Client                   AuthN Service              Platform Pool         Tenant Pool
  │                            │                          │                     │
  │── 1. Login (Cognito hosted UI) ─────────────────────→│                     │
  │←──────── Platform Token (JWT) ───────────────────────│                     │
  │                            │                          │                     │
  │── 2. POST /oauth2/token ──→│                          │                     │
  │     subject_token=PT       │                          │                     │
  │     audience=tenant://acme │                          │                     │
  │     grant_type=token-exchange                         │                     │
  │                            │                          │                     │
  │                            │── 3. Verify PT signature & claims ────────────→│
  │                            │← assigned_tenants includes "acme"? ──────────│
  │                            │                          │                     │
  │                            │── 4. AdminInitiateAuth with CUSTOM_AUTH on tenant pool ──→│
  │                            │← Tenant Token (JWT) ─────────────────────────────────────│
  │                            │                          │                     │
  │←── 5. Tenant Token ────────│                          │                     │
  │                            │                          │                     │
  │── 6. Business request (Bearer Tenant Token) ─────→ APIGateway → service     │
```

### AuthenticationService — Java sketch

```java
@RestController
@RequestMapping("/oauth2/token")
public class TokenExchangeController {

    private static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";

    @PostMapping(consumes = APPLICATION_FORM_URLENCODED_VALUE)
    public TokenExchangeResponse exchange(@RequestParam("grant_type") String grantType,
                                          @RequestParam("subject_token") String platformToken,
                                          @RequestParam("subject_token_type") String subjectType,
                                          @RequestParam("audience") String audience) {
        if (!GRANT.equals(grantType)) {
            throw new InvalidGrantException("Unsupported grant_type: " + grantType);
        }
        // 1. Validate platform token signature + issuer
        Jwt pt = platformPoolDecoder.decode(platformToken);

        // 2. Resolve tenant from audience (e.g., "tenant://acme")
        TenantSlug slug = TenantSlug.fromAudience(audience);
        TenantPool pool = tenantPoolRepo.findBySlug(slug)
            .orElseThrow(() -> new InvalidTargetException("Unknown tenant: " + slug));

        // 3. Authorize the exchange — user MUST have this tenant in assigned_tenants
        List<String> assigned = pt.getClaimAsStringList("assigned_tenants");
        if (assigned == null || !assigned.contains(slug.value())) {
            auditLog.deny(pt.getSubject(), slug, "tenant not in assigned_tenants");
            throw new AccessDeniedException("User not assigned to tenant " + slug);
        }

        // 4. Mint tenant token via Cognito AdminInitiateAuth
        String tenantToken = cognitoClient.mintTenantToken(pool, pt.getSubject());

        // 5. Audit (per ADR-011)
        auditLog.exchange(pt.getSubject(), slug, /* trace */);

        return new TokenExchangeResponse(tenantToken, "Bearer", 3600, "Bearer");
    }
}
```

The exchange is auditable, denyable, and scoped — exactly per RFC 8693 §2.1.

## 5. Cognito Tenant Pool Terraform Module

Module path: `infra/modules/cognito-tenant-pool/`. Invoked by `modules/tenant` ([java-iac-terraform] §6).

```hcl
# main.tf (sketch)
resource "aws_cognito_user_pool" "this" {
  name                     = "asgedge-${var.tenant_slug}"
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  mfa_configuration        = var.mfa_configuration   # "ON" default

  password_policy {
    minimum_length    = var.password_policy.min_length
    require_lowercase = var.password_policy.require_lowercase
    require_uppercase = var.password_policy.require_uppercase
    require_numbers   = var.password_policy.require_numbers
    require_symbols   = var.password_policy.require_symbols
    temporary_password_validity_days = 7
  }

  account_recovery_setting {
    recovery_mechanism { name = "verified_email" priority = 1 }
  }

  schema {
    name                = "tid"
    attribute_data_type = "String"
    mutable             = false
    required            = false
    string_attribute_constraints { min_length = 1 max_length = 64 }
  }

  schema {
    name                = "roles"
    attribute_data_type = "String"
    mutable             = true
    string_attribute_constraints { min_length = 0 max_length = 2048 }
  }

  lambda_config {
    post_confirmation = var.post_confirmation_lambda_arn
  }

  software_token_mfa_configuration { enabled = true }

  user_pool_add_ons { advanced_security_mode = "ENFORCED" }   # adaptive auth / threat detection

  device_configuration {
    challenge_required_on_new_device      = true
    device_only_remembered_on_user_prompt = true
  }
}

resource "aws_cognito_user_pool_domain" "hosted_ui" {
  domain          = var.hosted_ui_domain   # auth.<slug>.asgedge.com
  certificate_arn = var.acm_cert_arn
  user_pool_id    = aws_cognito_user_pool.this.id
}

resource "aws_cognito_user_pool_client" "app" {
  name                  = "asgedge-${var.tenant_slug}-app"
  user_pool_id          = aws_cognito_user_pool.this.id
  generate_secret       = true
  allowed_oauth_flows   = ["code"]
  allowed_oauth_scopes  = ["openid", "email", "profile"]
  allowed_oauth_flows_user_pool_client = true
  callback_urls         = var.callback_urls
  logout_urls           = var.logout_urls
  supported_identity_providers = compact([
    "COGNITO",
    var.saml_metadata_url == null ? "" : "SAML-${var.tenant_slug}"
  ])
  access_token_validity  = 60       # minutes
  id_token_validity      = 60
  refresh_token_validity = 30       # days
  token_validity_units { access_token = "minutes"  id_token = "minutes"  refresh_token = "days" }
}

resource "aws_cognito_identity_provider" "saml" {
  count          = var.saml_metadata_url == null ? 0 : 1
  user_pool_id   = aws_cognito_user_pool.this.id
  provider_name  = "SAML-${var.tenant_slug}"
  provider_type  = "SAML"
  provider_details = {
    MetadataURL    = var.saml_metadata_url
    IDPSignout     = "true"
    EncryptedResponses = "true"
  }
  attribute_mapping = {
    email          = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress"
    "custom:roles" = "groups"
  }
}

resource "aws_secretsmanager_secret" "client_secret" {
  name = "cognito/${var.tenant_slug}/app-client"
  kms_key_id = var.kms_key_arn
}

resource "aws_secretsmanager_secret_version" "client_secret_v" {
  secret_id     = aws_secretsmanager_secret.client_secret.id
  secret_string = aws_cognito_user_pool_client.app.client_secret
}
```

## 6. SAML Federation per Tenant

Each tenant uploads SAML metadata via Tenant Admin UI; the UI stores it in DB, Terraform reads it on the next apply (or a Lambda updates the pool live).

**JIT provisioning rule** (resolves part of GAP-033):
- SAML `email` attribute → Cognito username.
- SAML `groups` attribute → Cognito `custom:roles` (comma-separated).
- First SAML login → Cognito creates the user (PostConfirmation Lambda fires; user record materializes in the platform DB).

**Onboarding playbook** (handed to tenant admin):
1. Download SP metadata from `https://auth.<slug>.asgedge.com/saml2/metadata`.
2. Upload to Okta/Azure AD/OneLogin as a new SAML app.
3. Map IdP groups → roles in the platform Admin UI.
4. Click "Test Login" — flows through Cognito hosted UI.
5. Activate.

**Cert rotation:** SAML signing cert rotation is the tenant IdP's responsibility. We expose a "rotate" button in Admin UI that re-fetches the metadata URL. Cognito handles SP-side cert rotation automatically.

## 7. MFA, Lockout, Idle Timeout

Per BRD-05 + ADR-016:

| Setting | Platform pool | Tenant pool default | Tenant-configurable? |
| ------- | ------------- | ------------------- | --------------------- |
| MFA | TOTP enforced | TOTP enforced | Yes — admin can raise to TOTP + WebAuthn |
| SMS MFA | No (GAP-015 deferred) | No | Deferred |
| WebAuthn | Optional | Optional, recommended | Yes |
| Session token TTL | 60 min | 60 min | Yes (15-240 min range) |
| Refresh token TTL | 30 days | 30 days | Yes (1-365 day range) |
| Lockout threshold | 5 failed attempts | 5 | Yes (3-10) |
| Lockout duration | 15 min | 15 min | Yes (5-60 min) |
| Force password change on first login | Yes (Cognito `FORCE_CHANGE_PASSWORD` status) | Yes | No (BRD-05 USR-2 hard requirement) |

Tenant-configurable settings live in `GCDSecurityPolicy` (per AuthenticationService domain). On change, Terraform applies to the pool via a CI workflow (we don't `aws cognito-idp` from the application — that would be drift).

**Forced password change** — Spring redirects clients holding tokens with `cognito:user_status = FORCE_CHANGE_PASSWORD` to `/account/change-password`. The hosted UI handles this natively; SPAs check the claim on token receipt.

## 8. PostConfirmation Lambda → Kafka

Cognito has no native Kafka emitter. We bridge via Lambda.

```python
# lambda/cognito_post_confirmation.py
import os, json, boto3
from kafka import KafkaProducer  # bundled in Lambda layer

PRODUCER = KafkaProducer(
    bootstrap_servers=os.environ["MSK_BOOTSTRAP"].split(","),
    security_protocol="SASL_SSL",
    sasl_mechanism="AWS_MSK_IAM",
    # IAM via Lambda execution role
)

def handler(event, _ctx):
    tenant = event["userPoolId"]                        # we map back via DB or naming
    sub    = event["request"]["userAttributes"]["sub"]
    email  = event["request"]["userAttributes"]["email"]
    payload = {
        "type": "UserCreated",
        "version": 1,
        "tenant_id": tenant_slug_from_pool(tenant),
        "user_sub": sub,
        "email": email,
        "occurred_at": event["request"]["userAttributes"].get("updated_at"),
    }
    PRODUCER.send("tenant.events.v1", key=tenant.encode(), value=json.dumps(payload).encode())
    PRODUCER.flush()
    return event
```

The platform consumes `tenant.events.v1` and materializes the user record in the platform DB. Idempotent on `(tenant_id, user_sub)` per [java-messaging] inbox pattern.

> **Open question:** whether to keep the Lambda + Kafka bridge long-term or migrate to EventBridge Pipes → MSK once Pipes gains direct Cognito source (currently Pipes does not). Track in a future ADR.

## 9. CloudTrail → AuditService

Every Cognito mutation emits a CloudTrail event. We route them to AuditService.

```
CloudTrail (org trail)
   └─ EventBridge rule: source=aws.cognito-idp
        └─ EventBridge target: SQS audit-cognito-events
             └─ AuditService consumer → audit.events.v1 Kafka topic (per ADR-011)
```

AuditService preserves the original CloudTrail payload, attaches `tenant_id` (derived from pool ARN), and appends to the immutable hash-chained audit ledger.

## 10. Anti-patterns — Refuse

- Single User Pool with `custom:tenant_id` (rejected in ADR-016 Option B).
- Storing Cognito client secrets in env vars at build time. Secrets Manager + ESO only.
- Issuing tokens from your service. Cognito is the only issuer.
- Trusting `tid` from a request header. Read it from the validated JWT only.
- Caching JWKS forever. 24h TTL with refresh-on-miss.
- Synchronously calling Cognito on the hot path of every request. Decoder cache + pool registry handles this.
- Hardcoding pool IDs in application config. Use the registry; pool IDs come from DB.
- Cognito hosted UI without our branded wrapper for tenant users. Wrap or use the SPA login flow.
- Allowing token exchange without verifying `assigned_tenants`. The exchange is your authorization checkpoint.
- Using AWS SDK `AdminUpdateUserAttributes` from a non-AuthN service. Only AuthenticationService and AuthorizationEngine touch Cognito.
- Skipping CloudTrail → AuditService routing. SOC 2 evidence depends on it.
- Manually clicking in the Cognito console. Drift.

## 11. LocalStack and Dev — The Cognito Workaround

**LocalStack's Cognito support is partial.** Hosted UI is broken; SAML is not supported; JWKS endpoint occasionally returns stale keys. We do not rely on it.

Local strategy (per [java-dx] §7.1):

### LocalJwtIssuer (in-process fixture)

```java
@Configuration
@ConditionalOnProperty(name = "app.auth.local-jwt-fixture.enabled", havingValue = "true")
public class LocalJwtIssuerAutoConfiguration {

    @Bean
    public LocalJwtIssuer localJwtIssuer() throws Exception {
        return new LocalJwtIssuer(
            "http://localhost:9999/local-pool",   // issuer URI
            generateRsaKeyPair()
        );
    }

    @Bean
    @Primary
    JwtIssuerAuthenticationManagerResolver localResolver(LocalJwtIssuer issuer,
                                                         JwtAuthenticationConverter conv) {
        // Returns an in-process JWK set; tokens minted via issuer.mintToken(sub, tid, roles)
        return issuer.toResolver(conv);
    }
}
```

The fixture:
- Serves a JWKS endpoint at `localhost:9999/.well-known/jwks.json`.
- Mints valid JWTs (signed RSA-256) with arbitrary claims for tests.
- Is **excluded from non-local profiles** by `@ConditionalOnProperty`.

Integration tests use the same fixture; no real Cognito needed.

```java
@Test
void contractEndpointRequiresTid() {
    String token = jwtIssuer.mintToken(sub("alice"), tid("acme"), roles("CONTRACT_VIEWER"));
    given().header("Authorization", "Bearer " + token)
        .get("/contracts/123")
        .then().statusCode(200);
}
```

### When real Cognito is needed locally

Rare. For SAML/MFA flow validation: use the dev AWS account's real Cognito (one pool, one app client, point your `local-saml` profile at it). Acceptable because it's the dev account, not laptop.

## 12. Failure Modes and Mitigations

| Failure mode | Mitigation |
| ------------ | ---------- |
| JWK cache stampede on cluster cold-start | Caffeine LoadingCache with single-flight; pre-warm on startup by fetching known issuers |
| Token-exchange failure (pool unreachable) | Resilience4j circuit breaker on Cognito SDK calls; clients receive 503 with Retry-After |
| SAML cert expiry | Cognito emits `IdentityProviderUpdated` events; PagerDuty alert 30d before expiry |
| Cognito regional outage | Documented in [java-ops-dr-runbooks]; multi-region active-active not supported by Cognito (2026); accept and surface status to customers |
| Cognito quota — User Pools | Request 10,000+ before tenant 950 onboards; tracked in capacity-planning doc |
| Stale `assigned_tenants` after platform user gets new tenant access | Force re-login OR token-exchange `assigned_tenants` from DB rather than from PT claim (variant — currently using PT claim, see below open question) |

> **Open question:** whether `assigned_tenants` should be read from the Platform Token claim (current spec) or re-checked against the platform DB on every token exchange. The DB read costs ~5ms but lets tenant-revocations take effect immediately rather than at next platform-token refresh (60 min). Recommend DB read for prod; track in a future ADR.

## 13. Migration

Phase 1 (the current build) has **no production users**. AuthenticationService is built against Cognito from day 1. No legacy IdP migration needed.

For tenants migrating from an existing IdP (rare — most enterprise tenants federate via SAML, no migration needed):
1. Export users from source IdP (anonymized for testing).
2. Use Cognito's CSV bulk-import into the new tenant pool.
3. Force password reset on first login (BRD-05 USR-2 default).
4. SAML federation activated for the rest.

## 14. Cross-References

- [java-security] — Spring Security baseline, JWT validation, secrets
- [java-iac-terraform] — `modules/cognito-tenant-pool/`, `modules/tenant/`
- [java-multi-tenancy] — `TenantContext`, `tid` claim propagation, RLS belt-and-suspenders
- [aws-platform] — region strategy, MSK for the Kafka emit, KMS for client-secret encryption
- [java-messaging] — `tenant.events.v1` consumer, inbox idempotency
- [java-api-gateway] — gateway validates Tenant Token; platform endpoints validate Platform Token
- [java-data-governance] — audit event flow continuity
- [java-ops-dr-runbooks] — Cognito regional outage playbook
- ADR-016 (binding), ADR-005 (refined), ADR-004 (isolation alignment), ADR-011 (audit ledger)
- GAP-015 (SMS MFA deferred), GAP-033 (SAML JIT — partially resolved)

## 15. Reference

- [ADR-016 — AWS Cognito](../../../../asg-edge-plus-kb/decisions/ADR-016-aws-cognito-identity-provider.md)
- [RFC 8693 — OAuth 2.0 Token Exchange](https://datatracker.ietf.org/doc/html/rfc8693)
- [Cognito User Pool API reference](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/Welcome.html)
- [Spring Security multi-tenant resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/multitenancy.html)
- [Cognito SAML configuration](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-saml-idp.html)
