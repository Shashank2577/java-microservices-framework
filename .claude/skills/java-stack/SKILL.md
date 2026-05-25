---
name: java-stack
description: Use when starting any Java work in this repo — confirms the canonical stack (Java 21, Spring Boot 3.4.x, Gradle 8 KTS, Postgres 16, Kafka), Java-21-era language features to prefer, and the version-catalog convention.
---

# Java Stack — Canonical Versions & Idioms

## Versions (pin in `gradle/libs.versions.toml`)

| Concern        | Choice                                         | Notes                                            |
| -------------- | ---------------------------------------------- | ------------------------------------------------ |
| JDK            | **Java 21 (LTS)** — Temurin or Liberica       | Toolchain enforced in Gradle                     |
| Framework      | **Spring Boot 3.4.x**                          | Spring Framework 6.1, Spring Security 6.3        |
| Build          | **Gradle 8.x**, Kotlin DSL (`build.gradle.kts`) | Use version catalog, no hardcoded versions      |
| Persistence    | Spring Data JPA + Hibernate 6                  | jOOQ for complex/dynamic queries                 |
| DB             | PostgreSQL 16+                                 | JSONB, generated cols, partial indexes available |
| Migrations     | Flyway 10.x                                    | Schema-aware via placeholders                    |
| Messaging      | Apache Kafka + Spring Kafka 3.x                | Confluent Schema Registry, Avro                  |
| Cache          | Caffeine (local), Redis 7 (distributed)        | Spring Cache abstraction                         |
| Resilience     | Resilience4j                                   | Circuit breaker, retry, bulkhead, rate limiter   |
| Auth           | Spring Security OAuth2 Resource Server + JWT   | OIDC for IdP                                     |
| Observability  | Micrometer → OpenTelemetry                     | Prometheus, Tempo, Loki                          |
| Logging        | Logback + `logstash-logback-encoder`           | Structured JSON to stdout                        |
| Testing        | JUnit 5, AssertJ, Mockito, Testcontainers      | WireMock, ArchUnit, Spring Cloud Contract        |
| Containers     | Spring Boot buildpacks (`bootBuildImage`)      | OCI image, no Dockerfile by default              |

**Never guess a version.** If unsure, ask the user or consult Context7 MCP (`mcp__plugin_context7_context7__*`).

## Java 21 Language Features — Use These

| Feature              | Use For                                                       |
| -------------------- | ------------------------------------------------------------- |
| **Records**          | DTOs, value objects, immutable carriers                       |
| **Sealed types**     | Closed type hierarchies (Result, domain events, command types)|
| **Pattern matching** | `switch` over sealed types — exhaustive, no default needed    |
| **Text blocks**      | SQL, JSON, multi-line strings                                 |
| **Virtual threads**  | Default for Spring MVC: `spring.threads.virtual.enabled=true` |
| **`var`**            | Local vars where RHS is obvious; not for fields or APIs       |

### Idioms

```java
// Sealed + records + pattern matching
public sealed interface PaymentResult permits Succeeded, Failed, Pending {}
public record Succeeded(String txId, Money amount) implements PaymentResult {}
public record Failed(String code, String message) implements PaymentResult {}
public record Pending(Instant retryAt) implements PaymentResult {}

String describe(PaymentResult r) {
    return switch (r) {
        case Succeeded s -> "ok: " + s.txId();
        case Failed f    -> "fail: " + f.code();
        case Pending p   -> "retry at " + p.retryAt();
    };
}
```

## Gradle — Version Catalog

`gradle/libs.versions.toml`:
```toml
[versions]
spring-boot       = "3.4.1"
spring-cloud      = "2024.0.0"
flyway            = "10.20.1"
testcontainers    = "1.20.4"
resilience4j      = "2.2.0"

[libraries]
spring-boot-starter-web        = { module = "org.springframework.boot:spring-boot-starter-web" }
flyway-core                    = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-postgres                = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
testcontainers-postgres        = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
resilience4j-spring-boot3      = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }

[plugins]
spring-boot      = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-deps-mgmt = { id = "io.spring.dependency-management", version = "1.1.6" }
```

Every module's `build.gradle.kts` references the catalog (`libs.spring.boot.starter.web`) — **never** raw GAV strings.

## Toolchain Enforcement

Root `build.gradle.kts`:
```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}
```

## Spring Boot Baseline Properties

`application.yml` defaults (override per profile):
```yaml
spring:
  threads.virtual.enabled: true
  jpa:
    open-in-view: false                # never true
    properties.hibernate.jdbc.batch_size: 50
  datasource.hikari:
    maximum-pool-size: 20
    leak-detection-threshold: 30000
server:
  shutdown: graceful
  forward-headers-strategy: framework
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true
```

`spring.jpa.open-in-view=false` is mandatory — OSIV hides N+1 problems and leaks transactions into the web layer.

## Spring Bean Scopes

### The five scopes

| Scope | When to use | When to avoid |
| --- | --- | --- |
| `singleton` (default) | 99% of beans — controllers, services, repositories, gateways | Anything that holds per-request state |
| `prototype` | Stateful helpers; new instance per injection | When singleton with method params would do |
| `request` | Per-HTTP-request beans (request-scoped properties, audit context) | If a request-scoped `ScopedValue` / `ThreadLocal` works (cheaper) |
| `session` | Per-user-session state (legacy MVC, rare in stateless REST) | Stateless APIs (our default) — don't use |
| `application` / custom | Rare; one per ServletContext / custom scope | Almost never |

**Framework default: singleton. Use prototype + scoped proxies only with a written reason.**

### Singleton is not "thread-safe"

A singleton bean is **shared across threads**. Mutating state in a singleton requires explicit synchronization. The right pattern: stateless beans + method-local state + parameters.

```java
// WRONG — mutable field in a singleton
@Service class OrderCounter { private int count = 0; void inc() { count++; } }   // race

// RIGHT — return value, no shared state
@Service class OrderCounter { long inc(long current) { return current + 1; } }
```

### Prototype — and the singleton-of-prototype trap

```java
@Component @Scope("prototype")
class ReportBuilder { /* state */ }

@Service class ReportService {
    @Autowired ReportBuilder builder;            // INJECTED ONCE — same instance forever
    void build() { builder.add(...); }           // not a fresh prototype, despite the scope
}
```

Fix with a `Provider`:

```java
@Service
class ReportService {
    private final ObjectProvider<ReportBuilder> builders;
    void build() {
        ReportBuilder b = builders.getObject();   // fresh each call
        b.add(...);
    }
}
```

### Request scope — only when truly per-request

```java
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = TARGET_CLASS)
class RequestAuditContext {
    private final List<String> events = new ArrayList<>();
    void record(String e) { events.add(e); }
    List<String> events() { return List.copyOf(events); }
}
```

The scoped proxy lets you inject it into a singleton; the proxy looks up the current request's bean on each call.

**But for this framework, prefer:**
- `TenantContext` (ScopedValue / ThreadLocal) for tenant
- MDC for log fields
- A simple `Map` in a request attribute for ad-hoc per-request data

Request-scoped beans cost a proxy + bean-creation per request. Avoid unless the alternative is uglier.

### `ScopedValue` over `ThreadLocal` (JDK 21)

```java
public final class TenantContext {
    private static final ScopedValue<TenantId> CURRENT = ScopedValue.newInstance();
    public static <R> R callWith(TenantId t, Callable<R> c) throws Exception {
        return ScopedValue.where(CURRENT, t).call(c::call);
    }
    public static TenantId current() { return CURRENT.get(); }
}
```

Why `ScopedValue` (see `java-multi-tenancy`):
- Immutable inside the scope — no leak risk.
- Propagates correctly across virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`).
- No `.remove()` boilerplate / leak bug surface.

`ThreadLocal` only when you need mutability or pre-JDK21 compatibility. Always pair with try/finally for cleanup.

### Scope and virtual threads

Virtual threads share the same `Thread` heuristic as platform threads for `ThreadLocal`. `request`-scoped beans work — Spring binds via thread context. **But** if you spawn work via `CompletableFuture.supplyAsync` on a default executor, request scope is lost.

Fix: a `TaskDecorator` that copies the request attributes:

```java
@Bean
TaskDecorator requestAndMdcTaskDecorator() {
    return runnable -> {
        var ctx = RequestContextHolder.getRequestAttributes();
        var mdc = MDC.getCopyOfContextMap();
        return () -> {
            RequestContextHolder.setRequestAttributes(ctx);
            if (mdc != null) MDC.setContextMap(mdc);
            try { runnable.run(); } finally {
                RequestContextHolder.resetRequestAttributes();
                MDC.clear();
            }
        };
    };
}
```

Register on every `AsyncTaskExecutor` and the Spring MVC virtual-thread executor.

### Scope and bean lifecycle

- Singleton: `@PostConstruct` once at startup; `@PreDestroy` at shutdown. Beware long startup work — it blocks readiness.
- Prototype: Spring does **not** call `@PreDestroy`; you're responsible for cleanup. Don't put resource-holding logic in prototypes.
- Request: lifecycle = HTTP request.

### Custom scopes

Possible (e.g., `tenant` scope) but rarely worth the complexity. Prefer:
- `ScopedValue` + a request filter that sets it (see `java-multi-tenancy`).

If you genuinely need a custom scope, register a `Scope` bean and `proxyMode = TARGET_CLASS`. Document with an ADR.

### Anti-patterns — refuse

- Mutable fields in singleton beans without synchronization
- Injecting a prototype into a singleton without `ObjectProvider`
- Request-scoped bean accessed from a `@Scheduled` job (no request → NPE)
- Custom scopes when `ScopedValue` would do
- `ThreadLocal.set` without a `try/finally remove` (leaks across requests on pooled threads)
- Session scope in a stateless REST API
- `@PreDestroy` on a prototype bean (it won't fire)

## Dependency Hygiene

### 1. Version catalog is the only place versions live

- All dependency versions live in `gradle/libs.versions.toml`. No version literals (`"org.foo:bar:1.2.3"`) in any `build.gradle.kts`.
- Module build files reference `libs.bar` (typed accessor), never raw GAV.
- Rationale: single edit point for upgrades; Renovate updates one file; visible in PR diff.
- ArchUnit can't enforce this, but CI can: a small Gradle task that fails if `*.kts` contains a version string pattern matching `:\d+\.\d+` outside the catalog.

### 2. Use Spring Boot's BOM (dependency management plugin)

```kotlin
plugins {
    id("io.spring.dependency-management")
}
dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}") }
}
```

- Omit versions on Spring-aligned libraries (Jackson, Hibernate, Logback, Tomcat, Caffeine, etc.) — the BOM aligns them.
- Override only with a reason; document it in the catalog comment.

### 3. No SNAPSHOTs in non-local builds

Snapshot version in a non-`local` profile = fail the build.

```kotlin
tasks.register("verifyNoSnapshots") {
    doLast {
        val offenders = configurations.flatMap { it.allDependencies }
            .filter { it.version?.endsWith("-SNAPSHOT") == true }
        if (offenders.isNotEmpty()) throw GradleException("SNAPSHOT deps not allowed: $offenders")
    }
}
tasks.check { dependsOn("verifyNoSnapshots") }
```

Internal libs publish a real version (semver) and pin it.

### 4. Constraints and platform dependencies

For deeper cross-module alignment, publish your own BOM under `platform/`:

```kotlin
// platform/build-platform/build.gradle.kts
plugins { `java-platform` }
javaPlatform { allowDependencies() }
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    constraints {
        api(libs.resilience4j.spring.boot3)
        api(libs.flyway.core)
        api(libs.flyway.postgres)
        api(libs.testcontainers.postgres)
        // ... pinned versions for cross-module alignment
    }
}
```

Every service module: `implementation(platform(project(":platform:build-platform")))` — from then on dependencies are version-free.

### 5. Transitive-dependency policy

- `./gradlew :module:dependencies` is the source of truth; review on every PR that touches dependencies.
- Avoid wildcard excludes (`exclude(group = "*")`); they break silently. Use targeted excludes with a comment explaining why.
- For known-bad transitive versions, prefer a `constraint` in the platform BOM over per-module excludes.
- Conflict resolution: `failOnVersionConflict()` in critical modules to force explicit decisions.

### 6. Snapshot tests for build correctness

- Plugin updates (Spring Boot, Gradle) often shift transitive versions silently.
- A `dependencyInsight` task captures the full resolved tree and diffs against a checked-in snapshot. Surprises in upgrades become loud.

### 7. Internal libraries — semver and release notes

- Building blocks and starters in `platform/` follow semver (`MAJOR.MINOR.PATCH`).
- Breaking change = MAJOR bump + migration guide in the lib's `CHANGELOG.md`.
- Pre-release alphas: only with explicit consumer opt-in; never auto-merge.

### 8. Java version pinning

- `gradle/libs.versions.toml` carries `[versions] java = "21"`.
- Every module enforces toolchain: `java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt()) } }`.
- Renovate is configured to bump Java version centrally when a new LTS lands.

### 9. Annotation processors

- Lombok, MapStruct, Spring Boot configuration processor — declared in catalog, applied via `annotationProcessor(libs.x)` / `testAnnotationProcessor(libs.x)`.
- Avoid Lombok on domain types (records cover most cases); accept it on JPA entities and infra DTO mappers where the boilerplate truly is noise.

### 10. Renovate stance (full config lives in `code-quality`)

- Patch updates → auto-merge after CI green.
- Spring Boot grouping → manual review.
- Major bumps → manual + ADR for impact.
- Stale ignored Renovate PRs (>30 days) → flagged in CI report.

### Anti-patterns

- Hardcoded version strings in `build.gradle.kts`
- SNAPSHOT or LATEST dependencies in non-local profile
- Wildcard excludes (`exclude(group = "*")`)
- Two Spring Boot versions in one classpath (BOM drift)
- Internal lib without semver discipline
- "We'll fix Renovate PRs later" — list grows; vulnerabilities accumulate

## Reference

- `.claude/skills/lib/jabrena/110-java-maven-best-practices/references/110-java-maven-best-practices.md` — Maven-centric; the principles (BOM usage, no version literals, transitive hygiene) transfer to Gradle catalog/BOM.
- `.claude/skills/lib/jabrena/111-java-maven-dependencies/references/111-java-maven-dependencies.md` — deeper dependency management patterns; same principles apply.
