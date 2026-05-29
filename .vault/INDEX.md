---
type: index
last-updated: 2026-05-29
---

# Project Vault — INDEX

This page is the orientation point for the vault. Claude updates it whenever new top-level entries are created.

## Open drift items

*(Drift = a commit that contradicts a documented decision. Each open item needs a human resolution: update the decision, or revert the commit. See `.claude/skills/java-vault/SKILL.md` §7.)*

- *(none yet — drift items will appear here as they're detected)*

## Recent sessions

- [[2026-05-29]] — bootstrap wrapper, prove build, wire PIT, unify schemas, validator tests
- [[2026-05-28]] — vault introduction, third-pass skills, first reference module

## Active components

*(One note per service / module in this repo. Owners and current shape.)*

- [[domain-primitives]] — Java 21 pure value objects (`TenantId`, `Money`, `Result`). 91% mutation score, 99% instruction / 91% branch coverage.

## Active decisions

*(Smaller-than-ADR engineering decisions. ADRs themselves live in `docs/adrs/`.)*

- [[0002-drop-markdownlint-from-ci]] — drop markdownlint entirely (supersedes [[0001-relax-markdownlint-md031-md032-md040]])

## Recent findings

- [[2026-05-29-spring-docs-url-restructure]] — Spring Boot reference docs URL pattern changed; old `/docs/3.4.x/reference/html/` paths return 404
- [[2026-05-29-kafka-georeplication-fragment]] — kafka.apache.org `#georeplication` anchor renamed to `#georeplication-overview`

## People

- [[claude]]
- *(humans added on their first commit)*

## How to read this vault

- Sessions → daily log of what happened
- Decisions → why we picked this approach
- Findings → things that surprised us
- Debugging → incident postmortems
- Drift → commits that contradicted prior decisions
- People → who works on what
- Components → current shape of each service
- Tickets → GitHub/Jira mirrors

See `README.md` for the full guide, or `.claude/skills/java-vault/SKILL.md` for the rules Claude follows when writing here.
