# Java Microservices Framework — Claude Operating Index

This repository builds **Java 21 + Spring Boot 3.4 + PostgreSQL + Kafka** microservices following a strict, well-architected recipe. This file is intentionally short. The framework is broken into focused skills under `.claude/skills/`. **Invoke the relevant skill before doing the corresponding work** — it carries the authoritative rules and examples.

The framework has two layers:
- **`.claude/skills/java-*`** — our **28 framework skills**. Opinions, conventions, and rules specific to this repo (multi-tenancy with control-plane tenant, DDD strategic+tactical, outbox-required, ArchUnit layering, RBAC + ABAC, the `Intent:` commit format, RFC 7807 errors, 95% coverage, end-to-end-green commits, Backstage catalog, etc.).
- **`.claude/skills/lib/jabrena/`** — 41 ported skills from [jabrena/cursor-rules-java](https://github.com/jabrena/cursor-rules-java) (Apache-2.0). Encyclopedic Java/Spring patterns with good/bad code examples. Our framework skills link to these for deep dives.

---

## How Claude Works In This Repo

For every task, Claude follows this loop:

1. **Pre-flight** — invoke `java-git-workflow`: confirm the issue ID, create or switch to the correct branch.
2. **Understand scope** — invoke the framework skills relevant to the change (see map below). When a framework skill points to a `lib/jabrena/...` reference, read that too if the work is non-trivial.
3. **Design before code** — for new entities/queries always invoke `java-patterns-database`; for new services or modules invoke `java-architecture`; for new endpoints invoke `java-api-design` + `java-api-errors`; for new events/messaging invoke `java-messaging`; for compliance touchpoints invoke `java-data-governance`.
4. **Implement** — small, focused changes following the skill rules. Tests alongside.
5. **Verify** — run `./gradlew :<module>:check` (or full build for cross-module). ArchUnit, coverage, dep-check, Spotless, gitleaks all included.
6. **Auto-commit** — per `java-git-workflow` §3, commit each green coherent unit with a mandatory `Intent:` block in the message.

**Never act outside this loop. Never skip the skill that applies.**

---

## Skill Map — Framework (`.claude/skills/java-*`)

### Foundation
| When you're about to…                                              | Invoke skill                       |
| ------------------------------------------------------------------ | ---------------------------------- |
| Anything — start of session, set up a module                        | `java-stack` *(includes Spring bean scopes)* |
| Design any service, apply 12-factor / SOLID / SoC / microservices core | `java-principles` *(includes Separation of Concerns §4)* |
| Discover service boundaries, event-storm a PRD, draw a context map, ubiquitous language | `java-ddd` *(strategic)*  |
| Create a new service, new package, or cross-layer boundary          | `java-architecture` *(includes tactical DDD: aggregates, VOs, domain events)* |

### Patterns
| When…                                                               | Skill                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| Decide between Strategy/Builder/Factory/Decorator etc.              | `java-patterns-gof`                |
| Choose Saga/CQRS/Outbox/Circuit Breaker, wire HTTP client, integrate LLM, build RAG with pgvector | `java-patterns-microservices` |
| Touch DB schema, entities, repositories, queries, full-text search  | `java-patterns-database`           |
| Evaluate Drools vs Easy Rules vs strategy pattern, store/audit/hot-reload rules | `java-rules-engine`    |

### Runtime
| When…                                                               | Skill                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| Choose sync vs async, build cron/scheduled jobs, use distributed locks, Spring Batch | `java-async-sync-jobs` |
| Anything tenant-aware — request, DB, Kafka, jobs, cost attribution, **control-plane / standard tenant** | `java-multi-tenancy` |
| Produce/consume Kafka, design topic, CDC/warehouse handoff          | `java-messaging`                   |
| Change DB schema (Flyway), data backfill jobs                       | `java-migrations`                  |
| Write a test, set up Testcontainers, write contract tests, local dev loop, **95% coverage, FE alignment** | `java-testing` |

### Edge / API surface
| When…                                                               | Skill                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| Define a REST endpoint, GraphQL/gRPC, WebSocket/SSE, i18n            | `java-api-design`                  |
| Throw, catch, or return an error (RFC 7807 Problem Details)         | `java-api-errors`                  |
| Auth, secrets, headers, OWASP, rate limiting, encryption-at-rest, **RBAC + ABAC** | `java-security` |
| Configure the API gateway (routing, CORS, rate-limit, canary, egress)| `java-api-gateway`                |

### Cross-cutting concerns
| When…                                                               | Skill                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| Add a log, metric, trace, MDC field, **OTel baggage**, SLO, alert, load test | `java-observability`         |
| Add a property, profile, secret, or feature flag                    | `java-config`                      |
| Add or invalidate a cache (Caffeine/Redis)                          | `java-caching`                     |
| Build an image, k8s manifest, JVM tuning, service mesh (Istio/Linkerd) | `java-container-deploy`         |
| Wire a quality gate (Spotless, NullAway, dep-check, JaCoCo, PIT, **Javadoc**, Backstage catalog) | `java-code-quality` |

### Integrations
| When…                                                               | Skill                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| Inbound/outbound webhooks (Stripe, GitHub, Slack, custom)           | `java-integration-webhooks`        |
| File/blob storage (S3 signed URLs, virus scan, large uploads)       | `java-integration-storage`         |
| Email, SMS, push, in-app notifications                              | `java-integration-notifications`   |

### Ops & governance
| When…                                                               | Skill                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| Plan DR, RPO/RTO, backups/PITR, runbooks, on-call                   | `java-ops-dr-runbooks`             |
| Handle PII, GDPR DSAR, retention, tamper-evident audit log          | `java-data-governance`             |

### Workflow
| When…                                                               | Skill                              |
| ------------------------------------------------------------------- | ---------------------------------- |
| Start any task, before any code change, after each unit of work     | `java-git-workflow` **(mandatory)**|

Multiple skills usually apply to one task. Read all that fit.

---

## Reference Library — `.claude/skills/lib/jabrena/`

41 ported skills from [jabrena/cursor-rules-java](https://github.com/jabrena/cursor-rules-java), Apache-2.0. See `lib/jabrena/README.md` for the full inventory and Maven→Gradle adapter notes.

Each framework skill above ends with a `## Reference` section linking the relevant `lib/jabrena/<n>/references/<n>.md`. Read framework skill first for the opinion; dive into jabrena for the code-example depth.

---

## Repository Layout (mono-repo)

```
java/
├── CLAUDE.md                                 # this index
├── .claude/
│   └── skills/
│       ├── java-*                            # 28 framework skills (our opinions)
│       └── lib/jabrena/                      # 41 ported reference skills (Apache-2.0)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml                 # version catalog
├── platform/
│   ├── starters/                             # custom Spring Boot starters (auto-config)
│   │   ├── web-starter/                      # REST + ProblemDetail + idempotency-key
│   │   ├── persistence-starter/              # Flyway + multi-tenant datasource
│   │   ├── messaging-starter/                # Kafka + outbox + inbox
│   │   ├── security-starter/                 # OAuth2 RS + tenant filter + headers + encryption
│   │   ├── observability-starter/            # Logback JSON + Micrometer + OTel
│   │   ├── config-starter/                   # Vault/AWS SM bootstrap + feature-flag port
│   │   ├── integration-starter/              # webhook receivers + storage + notifications ports
│   │   └── governance-starter/               # @Pii + audit-log + retention purger
│   └── building-blocks/                      # plain libs (no auto-config)
│       ├── domain-primitives/                # TenantId, Money, Ids, Result<T,E>
│       ├── error-model/                      # ProblemDetail + ErrorCode catalog
│       ├── outbox/                           # transactional outbox lib
│       ├── webhook-port/                     # WebhookProvider abstraction
│       └── test-support/                     # Testcontainers + ArchUnit rules
├── services/
│   ├── starter-service/                      # reference template; clone to bootstrap
│   └── <your-service>/
└── deploy/                                   # Helm charts, k8s manifests, mesh policies
```

---

## Non-Negotiables (the short list)

These are framework laws. Skill files explain *how*; this list is *what*.

1. **Java 21**, Spring Boot **3.4.x**, Gradle **8 KTS** with version catalog; no version literals in build files. (`java-stack`)
2. **12-factor + SOLID + Separation of Concerns + bounded-context microservices**. Domain layer is pure Java. (`java-principles`, `java-architecture`)
3. **Strategic DDD discovery before code**: event-storm → context map → ubiquitous language per service. Tactical DDD inside: aggregates by ID only across boundaries; VOs as records; domain events past-tense, published via outbox. (`java-ddd`, `java-architecture` §7)
4. **Schema-per-tenant** on Postgres with `TenantContext` propagated through HTTP, JPA, Kafka, jobs, caches. **Standard / control-plane tenant** (`t_standard`) is itself a tenant; cross-tenant ops only via privileged scopes from there. (`java-multi-tenancy`)
5. **Flyway** forward-only, immutable after merge; expand/contract for destructive changes; backfills are jobs, not migrations. (`java-migrations`)
6. **Outbox pattern** for every "DB write + event publish". Idempotent consumers with inbox. CDC via Debezium when scale demands. (`java-messaging`)
7. **No EAGER, no OSIV, no H2, no `ddl-auto=update`.** Testcontainers (Postgres + Kafka) for all integration tests. (`java-patterns-database`, `java-testing`)
8. **Every outbound call**: timeout + retry-with-jitter + circuit breaker (Resilience4j). (`java-patterns-microservices`)
9. **ArchUnit** enforces architecture in CI. (`java-architecture`)
10. **One error contract: RFC 7807 Problem Details** with `code`, `traceId`, `tenantId`, `errors[]`. (`java-api-errors`)
11. **Three observability pillars wired**: structured JSON logs + MDC (with `tenant_id`, `trace_id`), Micrometer + Prometheus, OpenTelemetry traces. **OTel baggage at gateway** for cross-service business attrs (tenant.id, tenant.class). SLO+alert per service. (`java-observability`)
12. **Security by default**: OAuth2 RS + JWT (`tid` claim), CORS allowlist, HSTS+CSP, secrets via Vault/k8s, AES-GCM at-rest with KMS-wrapped tenant DEKs. **RBAC via permissions, not roles** (`@PreAuthorize("hasAuthority('PERM_orders:create')")`); ABAC for fine-grained. (`java-security`)
13. **One gateway**: auth, CORS, rate-limit, canary, egress all live at the edge — not duplicated in services. (`java-api-gateway`)
14. **Spring scopes default singleton.** Prototype/request only with a written reason. `ScopedValue` (not `ThreadLocal`) for context. (`java-stack` §"Spring Bean Scopes")
15. **No secrets in YAML or Git.** Env vars only, sourced from Vault/AWS SM/k8s Secrets. Renovate keeps deps current. (`java-config`, `java-code-quality`)
16. **JVM container-aware**: `MaxRAMPercentage=75`, three k8s probes, `minReplicas≥2`, PDB, graceful shutdown. (`java-container-deploy`)
17. **Quality gates block merge**: Spotless, Checkstyle, SpotBugs, NullAway, dep-check, JaCoCo, gitleaks. **Coverage ≥ 95% line / 90% branch on changed code; 98% on domain+application. PIT ≥ 85%.** (`java-code-quality`, `java-testing`)
18. **Javadoc mandatory on public API of starters/building-blocks**; use-cases get short Javadoc; controllers documented via OpenAPI; no Javadoc decoration on internal helpers. (`java-code-quality` §11)
19. **Service catalog discipline**: every service has `catalog-info.yaml` (owner, lifecycle, APIs), TechDocs, runbooks per alert. (`java-code-quality` §13)
20. **Rules engines: decision matrix first.** Default to strategy + sealed types. Easy Rules / Drools only when matrix says yes; every rule fire audited + versioned. (`java-rules-engine`)
21. **Webhooks: inbox + accept-fast + HMAC + per-provider secret rotation.** Outbound webhooks via durable queue with exp-backoff + DLT. (`java-integration-webhooks`)
22. **File uploads: never through the JVM.** Presigned URLs only, tenant-prefixed keys, virus scan before `AVAILABLE`. (`java-integration-storage`)
23. **Notifications via outbox, never inline.** Transactional vs marketing separated; per-tenant from-address verified. (`java-integration-notifications`)
24. **Every service declares an RPO/RTO tier**; PITR enabled; quarterly restore drill; runbook per alert. (`java-ops-dr-runbooks`)
25. **PII annotated (`@Pii`).** Append-only audit log with hash chain. Retention policies as code. Per-tenant region pinning. (`java-data-governance`)
26. **End-to-end green commit.** Backend + frontend + contracts all green before a commit lands. Test plan + coverage numbers in every commit message. No BE-then-FE-tomorrow splits without a feature flag. (`java-git-workflow` §3, `java-testing` §13)
27. **Every branch ties to an issue. Every commit has an `Intent:` block. Claude auto-commits after each end-to-end-green task.** (`java-git-workflow`)
28. **No destructive Git ops without explicit user approval.** (`java-git-workflow` §6)

---

## Project Details (user fills in)

> Per-project specifics live here. The skills above are the framework; this section makes the framework concrete for *this* repo.

- **Org package prefix:** `com.example` *(replace)*
- **Issue tracker:** *(e.g., GitHub Issues at `owner/repo`, or Jira project key `PROJ`)*
- **Issue ID format:** *(e.g., `PROJ-###` or `#123`)*
- **Base branch:** `main`
- **CI:** *(e.g., GitHub Actions)*
- **Container registry:** *(e.g., `ghcr.io/<org>`)*
- **Default profiles:** `local`, `test`, `dev`, `stg`, `prod`
- **IdP issuer:** *(Keycloak/Auth0/Okta URL — `IDP_ISSUER_URI`)*
- **Schema Registry:** *(Confluent/Apicurio URL)*
- **Secrets backend:** *(Vault / AWS Secrets Manager / k8s Secrets)*
- **Feature flag tool:** *(Unleash / LaunchDarkly / Togglz)*
- **Cache backend:** *(Caffeine only / Caffeine + Redis)*
- **Service mesh:** *(none / Linkerd / Istio)*
- **Service catalog:** *(Backstage URL)*
- **Storage:** *(S3 / GCS / Azure Blob)*
- **Email provider:** *(SES / SendGrid / Postmark)*
- **SMS provider:** *(Twilio / SNS)*
- **DR target:** *(RPO `5min` / RTO `1hr` for Tier-1; adjust by service)*
- **Services:**
  - `starter-service` — reference template
  - *(add your services here)*

---

_The framework lives in the skills. Edit per-project context in §"Project Details" only. Skills evolve via PR; the index stays small._
