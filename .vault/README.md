# Project Vault

This directory is the **team's institutional memory** for this Java microservices framework. It is committed to git, syncs between teammates, and is updated by Claude on every commit.

Open this folder in [Obsidian](https://obsidian.md/) for the best experience — wiki-links and the graph view make navigation easy. You can also browse as plain markdown.

## What's in here

- **`INDEX.md`** — table of contents and recent activity
- **`sessions/`** — one note per working day; what happened, by whom
- **`decisions/`** — engineering decisions smaller than full ADRs
- **`people/`** — one note per teammate (including Claude); ownership, recent activity
- **`components/`** — one note per service or module; current shape and owners
- **`tickets/`** — mirrors of GitHub issues / Jira tickets
- **`findings/`** — surprising library behavior, gotchas, "second time we hit this" warnings
- **`debugging/`** — incident postmortems
- **`drift/`** — commits that contradicted documented decisions; open items need resolution
- **`brd-prd/`** — mirrors / links to source product docs
- **`_meta/`** — tag taxonomy, graph hints, note templates

## How to open in Obsidian

1. Install [Obsidian](https://obsidian.md/).
2. "Open folder as vault" → select this `.vault/` directory.
3. The wiki-links will resolve and the graph view will render.

## How notes are written

By Claude, on every commit. See `.claude/skills/java-vault/SKILL.md` for the full pattern. Humans can edit notes directly — just preserve the YAML frontmatter and link discipline so the graph stays intact.

## Quick orientation queries

- "What happened this week?" → open `sessions/` and read the most recent 7 days
- "Who decided X?" → grep `decisions/` for the topic, check `authors:` frontmatter
- "Who works on orders-svc?" → open `components/orders-svc.md`, follow links to people
- "Are there open drifts?" → check the top of `INDEX.md` or open `drift/` and filter on `status: open`
- "Have we hit this Kafka bug before?" → grep `findings/` and `debugging/`

## What this vault is NOT

- **Not the audit log.** The framework's tamper-evident audit log lives in the application's `audit_log` table (see `java-data-governance` §5). That's for compliance. This vault is for engineering memory.
- **Not personal notes.** This is team memory. Use your own Obsidian for personal scratch.
- **Not a place for secrets.** Everything here is committed to git.
- **Not free-form.** Notes have frontmatter and templates. See `_meta/templates/`.
