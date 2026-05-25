---
name: java-code-quality
description: Use whenever wiring or extending the build's quality gates — Spotless/google-java-format, Checkstyle, SpotBugs, Error Prone + NullAway, OWASP dependency-check, JaCoCo coverage thresholds, PIT mutation testing, and Renovate/Dependabot. Every gate must be a Gradle task that runs in CI and blocks merge on failure.
---

# Code Quality Gates

Every gate listed here runs **in CI and locally**, and **blocks merge** on failure. They're cheap to wire on day 1 and ruinously expensive to retrofit. The rule is "fail the build, fix the code" — never disable, never `// SUPPRESS`.

## 1. The Gates

| Gate                  | Tool                       | When                       | Failing means         |
| --------------------- | -------------------------- | -------------------------- | --------------------- |
| Formatting            | Spotless + google-java-format | every build             | reformat and re-commit|
| Style                 | Checkstyle (project rules) | every build                | fix the violation     |
| Static bug detection  | SpotBugs + fb-contrib      | every build                | fix or justify by issue |
| Null safety           | Error Prone + NullAway     | every build                | annotate / fix        |
| Dependency CVEs       | OWASP dependency-check     | every build (cache offline DB) | upgrade or accept-with-ticket |
| Vulnerable code patterns | Sonar / Snyk Code (optional) | nightly + PR            | fix or accept         |
| Coverage              | JaCoCo                     | every build                | < threshold = fail    |
| Mutation testing      | PIT (Pitest)               | weekly / on label          | < threshold = fail    |
| Secrets               | gitleaks (pre-commit + CI) | every commit               | block commit          |
| License compliance    | gradle-license-report      | weekly                     | review on additions   |
| Dependency freshness  | Renovate / Dependabot      | continuous                 | PR auto-opened        |

## 2. Spotless + google-java-format

`build.gradle.kts`:
```kotlin
plugins { id("com.diffplug.spotless") version "7.0.2" }
spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.24.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org.springframework", "com.example", "")
    }
    kotlinGradle { ktlint() }
}
tasks.check { dependsOn("spotlessCheck") }
```
`./gradlew spotlessApply` to fix. Wire into pre-commit hook.

## 3. Checkstyle

Use a published rule set (Google style or our org's fork) — don't invent. Key rules:
- Line length 120.
- Imports: no wildcards, no static-of-non-test.
- Whitespace, naming, javadoc on public API.

```kotlin
plugins { checkstyle }
checkstyle {
    toolVersion = "10.20.0"
    configFile = file("$rootDir/config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
```

## 4. SpotBugs + fb-contrib

```kotlin
plugins { id("com.github.spotbugs") version "6.0.27" }
spotbugs {
    toolVersion = "4.8.6"
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.LOW
    excludeFilter = file("$rootDir/config/spotbugs/exclude.xml")
}
dependencies { spotbugsPlugins("com.mebigfatguy.sb-contrib:sb-contrib:7.6.5") }
```

Triage:
- Treat HIGH and MEDIUM as build failures.
- LOW reviewed but not blocking (configurable per repo).
- Suppressions in `exclude.xml` require an issue reference comment.

## 5. Error Prone + NullAway

```kotlin
plugins { id("net.ltgt.errorprone") version "4.0.1" }
dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.36.0")
    errorprone("com.uber.nullaway:nullaway:0.12.2")
}
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option("NullAway:AnnotatedPackages", "com.example")
        error("NullAway")
        disable("MissingSummary")
    }
}
```

Default to `@NullMarked` (JSpecify) at package level; `@Nullable` is the explicit exception. Goal: no NPE in shipped code.

## 6. OWASP Dependency-Check

```kotlin
plugins { id("org.owasp.dependencycheck") version "10.0.4" }
dependencyCheck {
    failBuildOnCVSS = 7f          // fail on High and Critical
    suppressionFile = file("$rootDir/config/dep-check/suppressions.xml")
    nvd { apiKey = System.getenv("NVD_API_KEY") }
    formats = listOf("HTML", "SARIF")
}
tasks.check { dependsOn("dependencyCheckAnalyze") }
```

- Cache NVD DB across CI runs to keep build fast.
- Suppressions require linked issue + expiry.

## 7. JaCoCo Coverage

```kotlin
plugins { jacoco }
jacoco { toolVersion = "0.8.12" }
tasks.test { finalizedBy("jacocoTestReport") }
tasks.jacocoTestReport { dependsOn(tasks.test) }
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "BUNDLE"
            limit { counter = "LINE";   minimum = "0.95".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.90".toBigDecimal() }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com.example.*.domain.*", "com.example.*.application.*")
            limit { counter = "LINE"; minimum = "0.98".toBigDecimal() }
        }
    }
}
tasks.check { dependsOn("jacocoTestCoverageVerification") }
```

Apply higher bar to domain + application layers (testable by design). Exclude generated code, mappers, simple DTOs.

> Coverage thresholds are **95% line / 90% branch** on changed code, **98% line on domain + application packages**. This is intentional friction: every line is tested or there's a written reason in the PR description.

## 8. PIT Mutation

```kotlin
plugins { id("info.solidsoft.pitest") version "1.15.0" }
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(listOf("com.example.*.domain.*", "com.example.*.application.*"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    mutationThreshold.set(85)
    timestampedReports.set(false)
}
```
Run weekly or on `pitest` label. Not on every commit — slow.

## 9. gitleaks (Secrets)

`pre-commit` hook config:
```yaml
- repo: https://github.com/gitleaks/gitleaks
  rev: v8.21.2
  hooks:
    - id: gitleaks
```
And in CI: `gitleaks detect --redact --no-banner`. Fail on any find.

## 10. Renovate / Dependabot

Use **Renovate** for richer config (grouped updates, schedule, automerge for minor patches).

`renovate.json`:
```json
{
  "extends": ["config:recommended"],
  "schedule": ["after 9pm on saturday"],
  "rangeStrategy": "bump",
  "packageRules": [
    { "matchPackagePatterns": ["spring-boot"],  "groupName": "Spring Boot", "schedule": ["before 6am on monday"] },
    { "matchPackagePatterns": ["org.testcontainers"], "groupName": "Testcontainers" },
    { "matchUpdateTypes": ["patch"], "automerge": true }
  ],
  "vulnerabilityAlerts": { "labels": ["security"] }
}
```

- Patch updates auto-merge after CI green.
- Minor/major batched, reviewed weekly.
- Security advisories take priority — labeled and escalated.

## 11. Javadoc — Where It Earns Its Place

Javadoc is documentation, not decoration. It earns its place on contracts a caller relies on; everywhere else it's noise that rots. Treat it like code: reviewed, linted, and built.

### When to Javadoc

- **Public API of starters and building-blocks**: every public type and method. Treat these as you'd treat a library API — consumers can't read your source easily.
- **Application use-cases**: short Javadoc on the use-case class stating intent + transactional behavior + side effects. Caller behavior matters; surprises here cause incidents.
- **Domain entities and value objects**: comment only invariants not obvious from code. Constructor preconditions. State transitions.
- **Ports (interfaces)**: contract documentation — preconditions, postconditions, exceptions thrown. The interface is the contract.
- **Configuration properties**: every `@ConfigurationProperties` record field — what it means, valid range, default. Ops reads these.
- **ArchUnit rules**: comment the WHY (which class of bug this prevents). A rule without a reason gets deleted the first time it inconveniences someone.

### When NOT to Javadoc

- **Controllers**: documented via OpenAPI annotations (`@Operation`, `@ApiResponse`, `@Schema`). Don't duplicate.
- **Spring `@Configuration` classes**: bean methods don't need docs unless wiring is non-obvious.
- **Test classes**: test method names should describe behavior; no Javadoc.
- **Mappers (MapStruct generated)**: skip.
- **DTO records**: the field names + `@Schema(description=...)` for OpenAPI cover it.
- **Internal helper classes**: skip; if the name + signature aren't clear, rename.

### Javadoc anti-patterns

- `@param order The order` — restating the name, zero info. Delete.
- `Calls X to do Y.` — describing the body, not the contract. Document behavior, not implementation.
- Wall-of-text Javadoc on something a reader could grok from 5 lines of code. Be terse.
- `@deprecated` without a `@deprecated <reason; migration path>` block.

### Style

- Sentence case, full stops.
- `<p>` between paragraphs (mandatory in HTML 4 doctype Javadoc still uses).
- `{@code}` for identifiers; `{@link}` for cross-refs.
- One blank line between summary sentence and body.
- `@since` for every public API in a library.
- `@throws` on every checked exception and on common runtime exceptions the caller might expect (`IllegalArgumentException`, `IllegalStateException`).

### Build gates

- `javadoc` task green on `:platform:*` modules. Warnings fail the build.
- Public APIs of building-blocks/starters: missing Javadoc = build fail (configure `-Xdoclint:all,-missing` everywhere else, full `-Xdoclint:all` on platform modules).
- Generate site Javadoc as part of CI; publish to GitHub Pages or Backstage TechDocs.

### Example

```java
/**
 * Place a customer order.
 *
 * <p>Acquires a Postgres advisory lock on the customer id, validates inventory
 * via {@link InventoryGateway}, persists the order, and emits an
 * {@code OrderPlaced} event via the outbox. The entire operation is one
 * transaction; downstream side effects (Kafka publish) happen after commit
 * by the outbox publisher.
 *
 * @param cmd the command; lines must be non-empty and qty &gt; 0
 * @return the new order's id
 * @throws InventoryReservationException if any line cannot be reserved
 * @throws IllegalArgumentException      if the command fails validation
 */
@Transactional
public OrderId place(PlaceOrderCommand cmd) { ... }
```

## 12. ADRs — Architecture Decision Records

In `docs/adrs/` per service or platform-wide for cross-cutting choices.
- One markdown per decision, numbered: `0001-use-schema-per-tenant.md`.
- Sections: Context, Decision, Status, Consequences, Alternatives considered.
- Updated when superseded; never deleted.

A non-trivial architectural change without an ADR is rejected in review.

## 13. Service Catalog & Documentation

A service that isn't in the catalog is invisible: on-call can't find the owner, consumers can't find the API, and deprecations strand callers. Default to **Spotify Backstage** for the registry, owner mapping, and tech-docs surface. Every service has a `catalog-info.yaml` at repo root, and Backstage scaffolds new services via golden-path templates.

### `catalog-info.yaml` — required fields

```yaml
apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: orders-svc
  description: "Order placement and lifecycle service"
  annotations:
    github.com/project-slug: example/orders-svc
    backstage.io/techdocs-ref: dir:.
    pagerduty.com/integration-key: ${PAGERDUTY_KEY}
    grafana/dashboard-selector: "service=orders-svc"
    sentry.io/project-slug: orders-svc
  tags: [java, spring-boot, kafka, multi-tenant]
spec:
  type: service
  lifecycle: production       # experimental | production | deprecated
  owner: team-fulfillment
  system: orders
  providesApis:
    - orders-rest-v1
    - orders-events-v1
  consumesApis:
    - inventory-rest-v1
    - billing-events-v1
  dependsOn:
    - resource:postgres-orders
    - resource:kafka-orders-cluster
```

Every service has `owner`, `lifecycle`, `system`, `providesApis`, `consumesApis`, `dependsOn`. Without these the catalog is decoration; with them, on-call finds the right team in 5 seconds.

### TechDocs — mkdocs in-repo

`docs/` at repo root with `mkdocs.yml`. Mandatory minimum:
- `index.md` — what the service does, who owns it, lifecycle status.
- `architecture.md` — C4 diagram preferred; link to ADRs.
- `runbooks/` — see `java-ops-dr-runbooks` for template.
- `dashboards.md` — direct links to Grafana, Sentry, Logs queries.
- `api/` — OpenAPI spec rendered + Kafka topics + event schemas.
- `dependencies.md` — auto-rendered from `catalog-info`.

Built and published by CI on every merge to `main`.

### README template

```markdown
# orders-svc

> Order placement and lifecycle. Owned by team-fulfillment.

## Quick links
- [API docs](https://backstage.example.com/catalog/default/component/orders-svc/docs)
- [Runbooks](docs/runbooks/)
- [Dashboards](docs/dashboards.md)
- [Grafana](https://grafana.example.com/d/orders-svc)
- [Sentry](https://sentry.io/organizations/example/projects/orders-svc/)

## Local dev
\`\`\`bash
docker compose up -d
./gradlew bootRun -Plocal
\`\`\`
See [local-dev](docs/local-dev.md) for prerequisites.

## On-call
- Primary: team-fulfillment (PagerDuty)
- Escalation: platform-on-call
- Severity matrix: docs/runbooks/severity.md

## Lifecycle
production — see [catalog-info.yaml](catalog-info.yaml) for current status
```

A README that doesn't follow this template gets flagged in code review.

### ADRs, APIs, ownership

- **ADRs**: covered in §12. Index at `docs/adrs/index.md`; Backstage TechDocs surfaces them at `/docs/orders-svc/adrs`.
- **API docs**: every REST surface generates OpenAPI (see `java-api-design`). Backstage `providesApis` lists which specs the service publishes. Consumers find provider specs via Backstage instead of cross-repo grepping.
- **Ownership & on-call**: `owner` maps to a team with a PagerDuty schedule — catalog auto-pages the right team. Lifecycle transitions (experimental → production → deprecated) require a PR review.
- **Dependency graph**: `dependsOn` renders a graph in Backstage. During incidents: "what depends on orders-svc?" → instant answer. For migration: "what consumes `inventory-rest-v1`?" → know who to notify before deprecating.
- **Tech-radar**: org-wide Backstage tech-radar (Assess / Trial / Adopt / Hold). New libraries added via PR — forces a conversation before sprawl.
- **Software templates**: Backstage templates clone `starter-service` with name/package injected, `catalog-info.yaml` pre-filled, CI workflow added, Grafana dashboard provisioned, PagerDuty service created.

### Anti-patterns — Refuse

- New repo with no `catalog-info.yaml` (invisible in catalog).
- `catalog-info.yaml` lacking `owner` (orphan service).
- Runbooks in Confluence/Notion instead of in-repo TechDocs (rot fast).
- README missing links to runbook / dashboard / on-call info.
- API docs published nowhere (PDF in a wiki doesn't count).
- ADRs scattered across team wikis with no single index.
- Deprecated services with no `lifecycle: deprecated` (consumers can't tell).

### Pre-merge checklist

- [ ] `catalog-info.yaml` present, `owner` set.
- [ ] README follows the template.
- [ ] Runbook for each known alert.
- [ ] OpenAPI spec linked from catalog `providesApis`.
- [ ] If exposing a new API → consumers notified via catalog graph.
- [ ] If deprecating → `lifecycle: deprecated` set + migration timeline.

### Reference

- Backstage: https://backstage.io/docs
- `.claude/skills/lib/jabrena/030-architecture-adr-general/references/030-architecture-adr-general.md` — ADR structure
- `.claude/skills/lib/jabrena/170-java-documentation/references/170-java-documentation.md` — javadoc conventions

## 14. The CI Pipeline (single source of truth)

```
[install] →
  [spotlessCheck] [checkstyleMain] [compileJava] →
    [test + jacocoTestCoverageVerification] →
      [spotbugsMain] [errorprone] [dependencyCheckAnalyze] →
        [integrationTest] [archTest] →
          [bootBuildImage / jib] →
            [trivyScan + cosignSign] →
              [pushImage] →
                [opa/conftest k8s manifests] →
                  [deploy preview]
```

Every stage gates the next. Cache aggressively (Gradle build cache, dep-check NVD).

## 15. Local Pre-Commit (recommended)

`.pre-commit-config.yaml`:
```yaml
repos:
- repo: local
  hooks:
    - id: spotless
      name: spotless
      entry: ./gradlew spotlessApply
      language: system
      pass_filenames: false
- repo: https://github.com/gitleaks/gitleaks
  rev: v8.21.2
  hooks: [ { id: gitleaks } ]
- repo: local
  hooks:
    - id: gradle-check
      name: gradle check (quick)
      entry: ./gradlew check -x integrationTest
      language: system
      pass_filenames: false
      stages: [pre-push]
```

## 16. Anti-patterns — Refuse

- `// SUPPRESS` / `@SuppressWarnings("all")` without an issue reference.
- A suppression in `spotbugs/exclude.xml` or `dep-check/suppressions.xml` without expiry.
- Coverage thresholds reduced over time to "let the build pass."
- `--no-verify` to bypass pre-commit.
- `formatOnSave` only — not in CI. The build must enforce it.
- Renovate PRs ignored for weeks.

## 17. Pre-Merge Checklist

- [ ] `./gradlew check` green.
- [ ] No new Spotless reformatting.
- [ ] No new Checkstyle / SpotBugs / Error Prone violations.
- [ ] No new High/Critical CVEs from dep-check.
- [ ] Coverage ≥ 95% line / 90% branch / 98% domain.
- [ ] No new secrets (gitleaks clean).
- [ ] Renovate PR list reviewed.
- [ ] If architectural change → ADR added.

## 18. Reference

- `.claude/skills/lib/jabrena/030-architecture-adr-general/references/030-architecture-adr-general.md` — ADR structure & lifecycle
- `.claude/skills/lib/jabrena/170-java-documentation/references/170-java-documentation.md` — Javadoc patterns
- Error Prone: https://errorprone.info
- NullAway: https://github.com/uber/NullAway
- PIT: https://pitest.org
- Renovate: https://docs.renovatebot.com
