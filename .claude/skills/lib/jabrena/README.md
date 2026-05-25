# jabrena/cursor-rules-java — Ported Reference Library

This directory contains a curated subset of skills from **[jabrena/cursor-rules-java](https://github.com/jabrena/cursor-rules-java)** (commit cloned on first import) — a battle-tested collection of Java/Spring Boot skills authored by Juan Antonio Breña Moral.

These skills serve as the **deep reference library** for our framework. Our 20 framework skills (in `.claude/skills/java-*`) encode opinions and conventions specific to *this* repository — multi-tenancy, schema-per-tenant, outbox-required messaging, ArchUnit layering, the `Intent:` git workflow. They link out to the skills in this directory for encyclopedic code examples and patterns.

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE) in this directory.

Attribution: Juan Antonio Breña Moral and contributors.
Upstream: https://github.com/jabrena/cursor-rules-java
Format: each skill is `<number>-<name>/SKILL.md` + `<number>-<name>/references/<number>-<name>.md`. The reference file holds the substantive content.

## What's ported

41 skills — only those relevant to this repo's stack (Java 21 + Spring Boot + Postgres + Kafka). Quarkus, Micronaut, MongoDB, Maven build internals, and Jira-specific skills were not ported.

| Range | Topic |
| ----- | ----- |
| 030–033 | Architecture: ADRs (general, functional, non-functional), diagrams |
| 121–145 | Java language: OO design, types, secure coding, concurrency, exceptions, generics, refactoring, functional, data-oriented |
| 161–164 | Profiling: detect, analyze, refactor, verify |
| 170 | Documentation |
| 181–183 | Observability: logging, Micrometer metrics, OpenTelemetry tracing |
| 301–304 | Spring Boot: core, REST, validation, security |
| 311–314 | Spring data: JDBC, Spring Data JDBC, Flyway migrations, Spring Kafka |
| 321–323 | Spring Boot testing: unit, integration, acceptance |
| 701–703 | Technologies: OpenAPI, WireMock, fuzzing |

Run `ls .claude/skills/lib/jabrena/` for the full inventory.

## Differences from upstream — read this before applying

These skills were authored against a slightly different stack. When using them, mentally apply these adapters:

### Build tool — Maven → Gradle

| jabrena says | We use |
| ------------ | ------ |
| `./mvnw compile` | `./gradlew compileJava` |
| `./mvnw test` | `./gradlew test` |
| `./mvnw verify` / `./mvnw clean verify` | `./gradlew build` |
| `./mvnw spring-boot:run` | `./gradlew bootRun` |
| `mvn-dependency-plugin` | Gradle version catalog (`gradle/libs.versions.toml`) |
| `<dependency>` XML | `implementation(libs.xxx)` |

The *patterns* (good/bad code examples) carry over verbatim — only the build commands differ.

### Spring Boot version

jabrena targets **Spring Boot 4.0.x** (Spring Framework 7.x). We are on **3.4.x** (Spring Framework 6.1). Compatibility is high for the topics ported here, with these notes:

- `RestClient` exists in 3.2+; jabrena's examples assume 3.2+. ✓ compatible.
- Virtual threads via `spring.threads.virtual.enabled=true` — same on both. ✓
- Jakarta EE namespace (`jakarta.*`) — same. ✓
- `@ConfigurationProperties` + `@Validated` — same. ✓
- New 4.x APIs (if any examples reference them) — confirm against [Spring Boot 3.4 docs](https://docs.spring.io/spring-boot/docs/3.4.x/reference/) before adopting.
- Spring Security 6.3 (our line) vs 6.4 (4.x line) — minor API shifts; the patterns hold.

### Multi-tenancy

**jabrena's skills are tenant-agnostic.** Our framework is schema-per-tenant. When applying any of jabrena's persistence, security, Kafka, or scheduling examples, layer in `TenantContext` per our `java-multi-tenancy` skill.

### Outbox-required messaging

**jabrena's 314-frameworks-spring-kafka** shows direct `KafkaTemplate.send()` usage. Our framework forbids this inside `@Transactional` — every publish goes through the outbox. See `java-messaging` for the pattern.

### Auto-commit + Intent block

jabrena has separate skills for GitHub issues and Jira but nothing on commit-message format. Our `java-git-workflow` is the authority for branching and commit conventions.

## How our framework skills reference this library

Each framework skill ends with a **Reference** section listing the jabrena skills with the deep code examples. Example:

```
## Reference
- `.claude/skills/lib/jabrena/304-frameworks-spring-boot-security/references/304-frameworks-spring-boot-security.md` — Spring Security filter chain, @PreAuthorize, OAuth2 resource server, headers
- `.claude/skills/lib/jabrena/124-java-secure-coding/references/124-java-secure-coding.md` — Java-level secure coding (input validation, deserialization, crypto)
```

When in doubt, read both the framework skill (for the opinion) **and** the linked jabrena reference (for the example).

## Updating

Upstream is actively maintained (last update May 2026). When syncing:

```bash
cd .reference/cursor-rules-java && git pull
# Then re-run the port script in .scripts/port-jabrena.sh (TODO)
```

Re-port preserves only the skill list in the script; manual additions to this directory will be overwritten — keep adaptations in our framework skills, not in `lib/jabrena/`.
