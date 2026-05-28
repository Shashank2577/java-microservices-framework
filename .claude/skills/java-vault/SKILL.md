---
name: java-vault
description: Use at every session start (read recent entries) and after every commit (write entries). Defines the team-shared project memory at `.vault/` — an Obsidian-compatible knowledge graph of decisions, sessions, people, components, tickets, findings, debugging postmortems, and drift detections. Committed to git; syncs between teammates. Updated by Claude automatically; readable by humans in Obsidian.
---

# Project Vault — Team Memory in `.vault/`

The vault is the project's institutional memory. Unlike Claude's per-session memory or a personal Obsidian, this vault **lives in the repo, is committed to git, syncs between teammates, and is updated by Claude on every commit and at session end**. Any human or Claude session that walks in cold can read the vault and have full context.

## 1. Why this exists

- **ADRs** capture heavyweight architecture decisions. The vault captures everything else.
- **Audit log** (`java-data-governance` §5) captures production events for compliance. The vault captures *engineering* memory.
- **Git history** captures *what* changed. The vault captures *why*, *who*, *what we learned*, *what surprised us*, and *where today's commit drifted from last quarter's decision*.

The vault answers: "Who decided X? When? Why? What else did we consider? Has anyone hit this Spring quirk before? Did this commit drift from our documented choice?"

## 2. Location and layout

`.vault/` at the repo root, committed to git, Obsidian opens it as a vault folder.

```
.vault/
├── README.md                          # Welcome + how to open in Obsidian
├── INDEX.md                           # Auto-maintained TOC and recent activity
├── _meta/
│   ├── tags.md                        # tag taxonomy
│   ├── graph.md                       # Obsidian graph hints
│   └── templates/                     # note templates (one per type)
│       ├── decision.md
│       ├── session.md
│       ├── person.md
│       ├── ticket.md
│       ├── component.md
│       ├── finding.md
│       ├── debugging.md
│       └── drift.md
├── people/                            # one note per teammate (including Claude)
├── components/                        # one note per service / module
├── decisions/                         # smaller-than-ADR decisions; ADRs back-link
├── tickets/                           # mirrors of GitHub issues / Jira
├── sessions/                          # one note per working day
├── findings/                          # surprising library behavior, gotchas
├── debugging/                         # incident postmortems
├── drift/                             # commits that contradicted documented decisions
└── brd-prd/                           # mirrors / links to source product docs
```

## 3. Note format — YAML frontmatter + wiki-links

Every note starts with frontmatter Obsidian (and Claude) can parse, and uses `[[wiki-links]]` to cross-reference:

```markdown
---
id: dec-0042
type: decision
tags: [#decision, #api-design]
date: 2026-05-28
authors: [[shashank]], [[claude]]
supersedes: []
status: active
linked-commits: [1e41c1b]
linked-tickets: [[PROJ-142]]
linked-components: [[orders-svc]]
---

# Cursor-based pagination as the default

## Context
We needed to pick a pagination model for [[orders-svc]] list endpoints. Initial
prototype used offset-based; under load it degraded after ~5k rows per tenant.

## Decision
Cursor-based by default; offset only for fixed-size enums.

## Rationale
- Stable performance regardless of page depth
- No "skipping a row" bug on concurrent writes
- Matches `java-api-design` §4 framework default

## Alternatives considered
- Offset paging → rejected (perf cliff)
- Keyset paging without opaque cursor → rejected (clients leak ordering)

## Consequences
- `OrderListController` returns cursor; clients can't deep-link by page index
- See [[dec-0043-cursor-encoding-secret]] for the signing key rotation
```

Frontmatter keys are stable. Body is free markdown.

## 4. Note types — purpose, when written

| Type | File path | Purpose | When Claude writes |
|------|-----------|---------|--------------------|
| **session** | `sessions/YYYY-MM-DD.md` | Per-day log: what got done, by whom, links to commits | Every commit appends; session-end writes summary |
| **decision** | `decisions/NNNN-<slug>.md` | Smaller-than-ADR engineering decisions | Whenever a non-trivial technical decision was made |
| **person** | `people/<slug>.md` | One per teammate (incl. Claude); owned components, decisions, recent activity | Auto-created on first commit from a new author |
| **component** | `components/<name>.md` | One per service/module; current shape, owner, linked decisions | At service creation; updated on architectural change |
| **ticket** | `tickets/<id>.md` | Mirror of GitHub issue / Jira ticket; linked commits, decisions | First time the ticket id appears |
| **finding** | `findings/YYYY-MM-DD-<slug>.md` | Surprising library behavior, gotchas, "second time we hit this" warnings | On discovery; future sessions search here first |
| **debugging** | `debugging/YYYY-MM-DD-<symptom>.md` | Incident postmortem: symptom → hypotheses → root cause → fix → lesson | After >30 min debugging |
| **drift** | `drift/YYYY-MM-DD-<title>.md` | Commit contradicts a documented decision | On detection (see §7) |
| **brd-prd** | `brd-prd/<period>-<feature>.md` | Mirror / link to source BRD/PRD; status; linked decisions and tickets | On PRD intake |

## 5. Write triggers — what Claude does on every commit

Treat this like the `Intent:` block: not optional. Every commit, in order:

1. **Append to today's session log** (`sessions/YYYY-MM-DD.md`):
   - One bullet linking the commit hash + Intent summary
   - List of `[[component]]`s touched
   - List of `[[decision]]`s referenced
2. **Create or update component notes** for any service whose files were modified.
3. **Create person note** if commit author has none yet.
4. **Create decision note** if a non-trivial technical choice was made (see §6 for "non-trivial").
5. **Create ticket note** if the commit references a new ticket id; sync state via `gh issue view <id>` (or Jira API).
6. **Run drift check** (§7) — if any component's behavior contradicts its documented shape, create drift note + flag the user.
7. **Update INDEX.md** if a new top-level entry was added.

**Session-end** (at user farewell signal, or when Claude finishes work):
- Write a paragraph summary at the bottom of today's session note: what was accomplished, what remains, what surprised us, what needs human input.

The commit is not "end-to-end green" (per `java-git-workflow` §3) until the vault has been updated.

## 6. What counts as "non-trivial" — decision threshold

Create a decision note when **any** of:
- A trade-off was weighed (cache vs no cache; sync vs async; library A vs B)
- A framework default was overridden
- A naming or convention choice could plausibly have gone two ways
- Future-Claude (or a teammate) might wonder "why did we do it this way?"

Don't create one for: typos, formatting, dependency-version bumps without semantic impact, mechanical refactors with no choice.

If unsure, write the decision note. Future-Claude thanks you.

## 7. Drift detection — the framework's killer feature

Before modifying a component, Claude reads `components/<name>.md`. If the change contradicts the documented shape, a **drift note is required** and the user is flagged.

```markdown
---
id: drift-2026-05-28-orders-cache
type: drift
status: open                       # resolved when decision is updated OR commit is reverted
date: 2026-05-28
detected-by: [[claude]]
commit: abc123def
component: [[orders-svc]]
contradicts: [[dec-0017-caffeine-only]]
---

# orders-svc introduced Redis cache; dec-0017 says Caffeine only

Commit `abc123def` adds `RedisCacheManager` to [[orders-svc]].
[[dec-0017-caffeine-only]] explicitly chose Caffeine-only on 2026-03-12.

## Resolution required (pick one)
- A. Update [[dec-0017]] with new context (Redis justified by X measurement).
   Add a new decision superseding it.
- B. Revert the Redis introduction in commit `abc123def`.

@shashank — please pick. Until resolved, status = open.
```

Resolution:
- If the decision is updated → write a new decision note, set old one's `status: superseded`, `supersedes: [[new-id]]`. Drift status → resolved.
- If commit is reverted → drift status → resolved-by-revert.

Open drift items are visible at the top of `INDEX.md` and read at session start.

## 8. People as first-class entities

Every commit author has a `people/<slug>.md`. Auto-updated:
- **Owned components** — components where this person authored the most commits in the last 90 days.
- **Recent decisions** — decisions authored or co-authored.
- **Recent sessions** — sessions where this person committed.
- **Specialties** (manual) — domains/tech this person is the go-to for.

Claude is a person too (`people/claude.md`). All Claude-authored commits trace back to it. The co-author trailer on commits (`Co-Authored-By: Claude`) is what makes the linkage automatic.

Bus-factor + onboarding answer: "who knows orders-svc?" → `components/orders-svc.md` → linked people.

## 9. Read triggers — when Claude reads the vault

| Trigger | What Claude reads |
|---|---|
| Session start | `INDEX.md`; `sessions/` last 7 days; `drift/` with `status: open` |
| About to touch component X | `components/X.md` + its linked decisions |
| About to make a decision in domain Y | `decisions/` filtered by tag Y |
| Hits a familiar-looking bug | `debugging/` + `findings/` keyword search |
| Asked "who decided X?" | `decisions/` by topic → `authors:` frontmatter |
| Asked "who works on X?" | `components/X.md` → linked people |
| Onboarding a new teammate | Curated walk: `README.md` → `INDEX.md` → recent sessions → key decisions for their domain |

Reading at session start is part of the standard pre-flight (see `CLAUDE.md` "How Claude Works In This Repo").

## 10. Pruning, archival, distillation

Without pruning, the vault rots. Discipline:

- **Sessions older than 90 days** → archived to `.vault/_archive/sessions/YYYY-Q/`. Still searchable, not in the active set.
- **Resolved drift items** → kept in `.vault/drift/_resolved/` for the audit trail.
- **Superseded decisions** → kept with `status: superseded`; never deleted (the trail of "what we used to think" matters).
- **Quarterly distillation** — at quarter end, a session is dedicated to:
  - Promoting load-bearing lessons from 90 days of `findings/` → a `_meta/wisdom-YYYY-QN.md` page.
  - Identifying decisions that should be promoted to full ADRs (`docs/adrs/`).
  - Archiving stale sessions.
- Distillation is itself logged as a session.

## 11. Search and graph

Obsidian renders the wiki-links as a graph. Useful queries:
- Click any node → see backlinks (who references this decision/component/person)
- Filter graph by tag (`#drift open` shows open drift items)
- Search across all notes (Cmd-O)

Claude doesn't render a graph but uses `grep`/`Glob` against the vault for the same purpose.

## 12. Integration with existing skills

| Skill | How vault integrates |
|---|---|
| `java-git-workflow` §3 | Vault update is part of end-to-end-green commit. Commit blocked if vault not updated. |
| `java-architecture` §6 (ADRs) | Vault decisions can be promoted to full ADRs. ADR file in `docs/adrs/`; vault decision back-links it. |
| `java-data-governance` §5 (audit log) | Audit log = compliance, tamper-evident, immutable. Vault = engineering memory, freely editable, in git. **They are different things.** |
| `java-code-quality` §12 (ADRs) | Same as above — ADR layer above vault decision layer. |
| `java-ops-dr-runbooks` §8 | Runbooks live in `docs/runbooks/`. Vault `debugging/` postmortems link to the runbook that exists or should exist. |
| `java-ddd` §13 | Glossary still in `docs/glossary.md` (one per service); vault decisions can link to glossary terms. |

## 13. The CLAUDE.md session-start contract

Every Claude session begins by:
1. Reading `CLAUDE.md` (skill map + non-negotiables)
2. Reading `.vault/INDEX.md`
3. Reading `.vault/sessions/` last 7 days
4. Reading `.vault/drift/` `status: open` items
5. (Then skill invocation per the task)

This is automatic. If the vault is empty, Claude says so once at session start and proceeds with empty memory.

## 14. Anti-patterns — refuse

- Vault not updated on a commit — that's a broken commit
- Decisions written but never linked from `components/` or `sessions/` — orphan notes
- Drift detected but no flag raised — silent contradiction
- Vault entries with no `date:` — eventually unreadable
- Notes deleted (vs marked superseded) — destroys the historical trail
- Vault rewritten in a force-push — destroys teammates' memory
- Free-form vault notes without frontmatter — breaks search and graph
- Treating vault as Claude-only — humans must be able to edit it directly in Obsidian
- Storing secrets in vault — vault is git-tracked; same rules as code
- Personal opinions / vent — vault is professional team memory, not Slack

## 15. Pre-merge checklist

- [ ] Today's session note has an entry for every commit in this PR
- [ ] New components/services have a `components/<name>.md` note
- [ ] New decisions linked to the commits that implement them
- [ ] All open drift items either resolved or explicitly acknowledged
- [ ] INDEX.md reflects new top-level entries
- [ ] No secrets in vault

## 16. Reference

- [Obsidian docs](https://help.obsidian.md/) — wiki-link syntax, vault concept
- [Diátaxis](https://diataxis.fr/) — documentation framing; influence on note types
- `.claude/skills/lib/jabrena/030-architecture-adr-general/references/030-architecture-adr-general.md` — ADR format we link from vault decisions
- `java-architecture/SKILL.md` §6 (ADRs) — when a vault decision is heavy enough to be promoted
- `java-data-governance/SKILL.md` §5 — the audit log (NOT the same as this vault)
- `java-git-workflow/SKILL.md` §3 — vault update as part of commit protocol
