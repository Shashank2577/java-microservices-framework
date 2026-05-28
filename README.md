# Java Microservices Framework — Claude Code Skills

[![CI](https://github.com/Shashank2577/java-microservices-framework/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Shashank2577/java-microservices-framework/actions/workflows/ci.yml)
[![Validate framework](https://github.com/Shashank2577/java-microservices-framework/actions/workflows/validate-framework.yml/badge.svg?branch=main)](https://github.com/Shashank2577/java-microservices-framework/actions/workflows/validate-framework.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

An opinionated, production-grade recipe for building **Java 21 + Spring Boot 3.4 + PostgreSQL + Kafka** microservices, packaged as [Claude Code](https://claude.com/claude-code) skills so that Claude can read it, follow it, and enforce it on every change.

This repository is **not** the application. It is the *framework*: a set of rules, conventions, and reference implementations that any Java microservices project can adopt by dropping its contents into the project root.

---

## Why this exists

LLM-assisted coding without a framework produces inconsistent services: one with H2 in tests, one with mocked Postgres; one with Lombok everywhere, one without; ten different REST error shapes across ten services. Code review eats your week.

This repo encodes the opinions a senior team would write on a whiteboard once and re-explain forever. Claude reads the skills before every change and enforces them. New engineers get a consistent codebase; reviews focus on the actual business logic, not the same eight stylistic disagreements.

---

## What's in here

```
.
├── CLAUDE.md                                 # the operating index — start here
├── .claude/
│   └── skills/
│       ├── java-*                            # 32 framework skills (the opinions)
│       └── lib/jabrena/                      # 41 ported reference skills (Apache-2.0)
├── .gitignore
├── LICENSE                                   # Apache-2.0
└── README.md
```

- **`CLAUDE.md`** — the operating index. Lists the 26 skills, the 24 non-negotiables, and the project-details placeholders you fill in for your specific project.
- **`.claude/skills/java-*`** — the 32 framework skills. Each is a focused `SKILL.md` covering one concern (DB design, security, multi-tenancy, etc.). ~150–600 lines each, ~7,800 lines total.
- **`.claude/skills/lib/jabrena/`** — 41 ported skills from [jabrena/cursor-rules-java](https://github.com/jabrena/cursor-rules-java) (Apache-2.0). Encyclopedic Java/Spring patterns with good/bad code examples. Our framework skills link to these for deep dives.

---

## The 32 framework skills

**Foundation** — `java-stack`, `java-principles`, `java-ddd`, `java-architecture`
**Patterns** — `java-patterns-gof`, `java-patterns-microservices`, `java-patterns-database`, `java-rules-engine`
**Runtime** — `java-async-sync-jobs`, `java-multi-tenancy`, `java-messaging`, `java-migrations`, `java-testing`
**Edge / API** — `java-api-design`, `java-api-errors`, `java-security`, `java-api-gateway`
**Cross-cutting** — `java-observability`, `java-config`, `java-caching`, `java-container-deploy`, `java-code-quality`
**Integrations** — `java-integration-webhooks`, `java-integration-storage`, `java-integration-notifications`
**Ops & Governance** — `java-ops-dr-runbooks`, `java-data-governance`
**Workflow & Memory** — `java-git-workflow`, `java-vault`
**Oversight & meta** — `java-adversarial-drift`, `java-human-review-ritual`, `java-framework-metrics`

See [`CLAUDE.md`](./CLAUDE.md) for the full skill map and which skill to invoke for which task.

---

## The 24 non-negotiables (excerpt)

1. Java 21, Spring Boot 3.4, Gradle 8 KTS with version catalog.
2. Domain layer is pure Java — no Spring, no JPA imports.
3. **Schema-per-tenant** Postgres with explicit `TenantContext` propagation.
4. **Outbox pattern** for every "DB write + event publish". Idempotent consumers with inbox.
5. No EAGER, no OSIV, no H2, no `ddl-auto=update`. Testcontainers for integration tests.
6. **RFC 7807 Problem Details** is the single error contract.
7. Three observability pillars wired: JSON logs + MDC, Micrometer + Prometheus, OpenTelemetry + baggage.
8. **OAuth2 RS + JWT with `tid` claim**; AES-GCM at-rest with KMS-wrapped tenant DEKs; RBAC via permissions (not roles), ABAC for fine-grained.
9. **One gateway** owns auth, CORS, rate limit, canary — never duplicated in services.
10. **Coverage ≥ 95% line / 90% branch / 98% domain.** End-to-end green commits (BE+FE+contracts) — no BE-then-FE-tomorrow splits.
11. **Quality gates block merge**: Spotless, NullAway, dep-check, JaCoCo, gitleaks, ArchUnit.
12. **Every branch ties to an issue. Every commit has an `Intent:` block + Test plan + coverage numbers.** Claude auto-commits after each end-to-end-green task.

Full list in [`CLAUDE.md`](./CLAUDE.md#non-negotiables-the-short-list).

---

## How to use this

### Mode 1 — Drop into an existing Java project

```bash
cd <your-java-project>
git remote add framework https://github.com/Shashank2577/java-microservices-framework
git fetch framework
git checkout framework/main -- .claude/ CLAUDE.md
# (Optionally) commit
git add .claude CLAUDE.md
git commit -m "chore: adopt java-microservices-framework skills

Intent:
  Adopt the canonical framework so Claude follows the same opinions
  as the rest of the org's Java services.

Refs: <your-issue>"
```

Then fill in the **`Project Details`** section at the bottom of `CLAUDE.md` with your org prefix, issue tracker, services, etc.

### Mode 2 — Start a new Java project from scratch

```bash
gh repo create my-org/my-service --private --clone
cd my-service
git remote add framework https://github.com/Shashank2577/java-microservices-framework
git fetch framework
git checkout framework/main -- .claude/ CLAUDE.md .gitignore
git commit -m "chore: bootstrap with java-microservices-framework

Intent:
  New service scaffold. Adopts the framework's skills and conventions
  before any business code is written.

Refs: <your-issue>"
```

Then have Claude bootstrap the `services/starter-service/` Gradle module per the framework's package architecture (see `.claude/skills/java-architecture/SKILL.md`).

### Mode 3 — Read as reference

Even without Claude Code, the markdown is human-readable. Engineers can read individual `SKILL.md` files for one-page summaries of any concern (multi-tenancy, error handling, etc.) — each links to deeper material in `lib/jabrena/`.

---

## How Claude uses these skills

When you have Claude Code installed and pointed at a repo containing this framework:

1. Claude reads `CLAUDE.md` on session start, sees the skill map.
2. For every task, Claude invokes the relevant skill *before* writing code.
3. Skills enforce: branch-per-issue, multi-tenant DB access, outbox publishing, Problem Details errors, structured logs, ArchUnit-validated architecture, mandatory `Intent:` block in commit messages, auto-commit after each green unit of work.
4. Each framework skill links to a `lib/jabrena/<n>/references/<n>.md` for deep code examples.

See [`CLAUDE.md`](./CLAUDE.md#how-claude-works-in-this-repo) for the operating loop.

---

## What this framework does NOT do

- **Does not auto-run a project from a PRD.** It defines HOW; you still provide WHAT.
- **Does not include `starter-service` source code yet.** It's referenced but not committed — that's the next pass.
- **Does not provision infrastructure** (Vault, Postgres, Kafka, IdP, Backstage). Skills assume they exist.
- **Is not a SOC2 / ISO / HIPAA compliance checklist.** It provides primitives (audit log, encryption, retention); mapping to controls is the GRC team's job.
- **Is not platform-specific.** No Terraform, no opinionated cloud choice. Bring your own platform.

---

## Stack

| Concern        | Choice                                          |
| -------------- | ----------------------------------------------- |
| JDK            | Java 21 LTS (Temurin or Liberica)               |
| Framework      | Spring Boot 3.4.x                               |
| Build          | Gradle 8 KTS with version catalog               |
| Database       | PostgreSQL 16+                                  |
| Migrations     | Flyway 10.x                                     |
| Messaging      | Apache Kafka + Confluent Schema Registry (Avro) |
| Cache          | Caffeine (local), Redis 7 (distributed)         |
| Resilience     | Resilience4j                                    |
| Auth           | Spring Security OAuth2 RS + JWT                 |
| Observability  | Micrometer → OpenTelemetry → Prometheus/Tempo/Loki |
| Testing        | JUnit 5, AssertJ, Testcontainers, ArchUnit, WireMock, Spring Cloud Contract |
| Containers     | Spring Boot buildpacks / Jib                    |

Versions pinned in the version catalog block in `.claude/skills/java-stack/SKILL.md`.

---

## Roadmap

Closing the gap from "framework" to "auto-runnable":

- [ ] `java-bootstrap` skill — scaffold a new service from a spec (Gradle module + starter-service copy + catalog-info + gateway route + issue).
- [ ] `java-task-planning` skill — PRD/user-story → atomic tasks with acceptance criteria, sequenced.
- [ ] `java-decision-boundary` skill — explicit "ask user vs choose default" rules per ambiguity class.
- [ ] Real `services/starter-service/` Gradle code — not just references.
- [ ] Real `platform/starters/` and `platform/building-blocks/` Gradle code.
- [ ] Optional `java-modulith` skill — for teams that should start with a modular monolith.

PRs welcome.

---

## Credits & License

This repository is **Apache License 2.0** — see [`LICENSE`](./LICENSE).

The reference library at `.claude/skills/lib/jabrena/` is a curated subset of [**jabrena/cursor-rules-java**](https://github.com/jabrena/cursor-rules-java) by **Juan Antonio Breña Moral**, also Apache-2.0 — see [`.claude/skills/lib/jabrena/LICENSE`](./.claude/skills/lib/jabrena/LICENSE) and [`.claude/skills/lib/jabrena/README.md`](./.claude/skills/lib/jabrena/README.md) for attribution and Maven→Gradle adapter notes.

Built with [Claude Code](https://claude.com/claude-code).

---

## Contact

Issues and PRs: [github.com/Shashank2577/java-microservices-framework](https://github.com/Shashank2577/java-microservices-framework)
