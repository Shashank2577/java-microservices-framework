---
name: java-git-workflow
description: Use at the START of any task, BEFORE coding, and AFTER finishing a coherent unit of work. Enforces branch-per-issue, Conventional Commits with mandatory Intent block, the auto-commit protocol (commit after each green task), and PR conventions. Claude must follow this skill for every change.
---

# Git & Commit Workflow (mandatory)

## 0. Pre-Flight — Before You Type Any Code

1. Confirm the **issue ID** for this task. If absent, **stop and ask** the user. Offer to create one if there's an issue tracker configured.
2. Check current branch. If on `main`/`master`, create a new branch (§1). If on a feature branch, confirm it matches the issue.
3. Pull latest `main`, rebase if needed.

## 1. Branching

- One branch per issue. No exceptions.
- Branch off `main` (or the configured base branch).
- Name: `<type>/<issue-id>-<kebab-summary>`
  - Types: `feat`, `fix`, `chore`, `refactor`, `docs`, `test`, `perf`, `build`, `ci`, `revert`.
  - Summary: 3–7 words, kebab-case, no nouns-only ("feat/auth" ✗ → "feat/PROJ-142-tenant-aware-datasource" ✓).
- Examples:
  - `feat/PROJ-142-tenant-aware-datasource`
  - `fix/PROJ-198-order-total-rounding`
  - `refactor/PROJ-204-extract-pricing-port`

`main` is protected. Work only lands via PR.

## 2. Commit Message — Conventional + Intent (Mandatory Format)

Every Claude-authored commit uses **exactly** this structure:

```
<type>(<scope>): <imperative summary, ≤72 chars>

Intent:
  <Why this change exists in 1–3 sentences. The problem it solves, the
  decision taken, the alternative rejected. Written in plain English.
  Not a restatement of the diff.>

Changes:
  - <bullet of what changed, file/module granularity>
  - <bullet>

Refs: <ISSUE-ID>
Co-Authored-By: Claude <noreply@anthropic.com>
```

### Field rules

| Field      | Rule                                                                              |
| ---------- | --------------------------------------------------------------------------------- |
| `type`     | Matches branch type (`feat`, `fix`, `chore`, ...).                                |
| `scope`    | Module or service in parentheses: `orders-svc`, `web-starter`, `flyway`, `outbox`.|
| Summary    | Imperative ("add", "fix", "remove"), ≤72 chars, no trailing period.               |
| `Intent:`  | **Mandatory.** Wrap at 72 cols. Captures *why* — diff already shows *what*.       |
| `Changes:` | High-level bullet list, file/module granularity. Skip if Intent already covers.   |
| `Refs:`    | Mandatory issue ID.                                                               |
| Co-author  | Always include the Claude trailer.                                                |

### Good examples

```
feat(orders-svc): add transactional outbox for OrderPlaced events

Intent:
  We need to publish OrderPlaced to Kafka atomically with the DB
  write. Direct KafkaTemplate.send() inside @Transactional risks
  publishing without the row being committed, or the reverse on
  rollback. The outbox table + a scheduled publisher (ShedLock-
  guarded) avoids that race and gives us at-least-once delivery
  with a clear failure surface.

Changes:
  - outbox table + Flyway V004
  - OutboxRepository, OutboxPublisher (@Scheduled + @SchedulerLock)
  - PlaceOrder use-case writes to outbox in same TX
  - Integration test with Testcontainers Kafka

Refs: PROJ-142
Co-Authored-By: Claude <noreply@anthropic.com>
```

```
fix(orders-svc): correct order-total rounding on multi-currency lines

Intent:
  Order total was computed by summing Money.add() across lines, but
  the BigDecimal scale defaulted to 2 even for currencies that use
  3 or 4 decimals (BHD, KWD). Lines now carry their currency scale
  explicitly and the total is computed per-currency. Rejected the
  alternative of normalizing everything to a base currency since
  the read side needs the original currency.

Refs: PROJ-198
Co-Authored-By: Claude <noreply@anthropic.com>
```

### Bad — refuse to commit messages like

- `fix bug` (no scope, no intent, no issue ref).
- `update files` (says nothing).
- `wip` (no Intent block, no ref).
- `feat: ...` with no Intent block.
- Anything missing `Refs:`.

## 3. Auto-Commit Protocol

Claude **commits automatically** after each completed task when **all** are true:

1. The change is one coherent, atomic unit (one logical change, not a grab-bag).
2. Build is green: `./gradlew :<affected-modules>:test` (or full `./gradlew build` for cross-module changes).
3. ArchUnit, lint (Spotless/Checkstyle), and Flyway test pass.
4. No secrets / credentials in the diff (`*.env`, `application-local.yml` with passwords, AWS keys, JWT secrets). Pre-commit `gitleaks` hook is mandatory.
5. Branch matches issue (§1).
6. The user hasn't said "don't commit yet" in this turn.

**If any condition fails** → don't commit. Report the blocker. Wait.

### Per-Task Workflow

1. Verify branch (§1).
2. Implement change. Keep it scoped.
3. Run the narrowest sensible test command. If it touches multiple modules, run `./gradlew build`.
4. Stage **only the files relevant to this task** (`git add <paths>`). **Never** `git add -A` blindly.
5. Compose the commit message per §2 with a real Intent block.
6. Commit via HEREDOC:
   ```bash
   git commit -m "$(cat <<'EOF'
   feat(orders-svc): add transactional outbox for OrderPlaced events

   Intent:
     ...

   Refs: PROJ-142
   Co-Authored-By: Claude <noreply@anthropic.com>
   EOF
   )"
   ```
7. Report commit SHA + one-line summary to the user.

### Multi-Step Features
Split into multiple **small commits**, each green, each with its own Intent block. Prefer 5 focused commits over one giant WIP. Never push a red commit.

## 4. Pre-Commit Hooks

Required hooks at repo root (`.git/hooks` or via a tool like `pre-commit`/`lefthook`):
- `gitleaks` — secret scanning.
- `spotless` / `google-java-format` — formatting.
- `./gradlew :<changed>:test --quick` — fast feedback.
- Conventional commit linter — rejects messages missing `Intent:` or `Refs:`.

**Never** use `--no-verify` to bypass. Fix the hook failure.

## 5. Pull Requests

Open the PR when the user signals "ready for review" (or when explicitly told to).

### PR Title
= Primary commit subject (Conventional Commits format).

### PR Body Template
```
## Summary
- <1–3 bullets describing the change>

## Intent
<Why — same spirit as the commit Intent block, broadened to the whole change.>

## Risk / Rollback
<What could break, how to roll back. "Low; revert the migration and the deploy."
or "Adds the outbox publisher — if Kafka is down, outbox grows; no data loss.">

## Test plan
- [ ] Unit tests added/updated
- [ ] Integration test with Testcontainers passes
- [ ] ArchUnit clean
- [ ] Flyway migration applied to fresh DB and to a snapshot of the last release
- [ ] Manual smoke against local stack

Closes <ISSUE-ID>
```

Use `gh pr create` with a HEREDOC body. Set draft status if anything in the test plan is unchecked.

## 6. Destructive Action Policy

Without **explicit, in-this-conversation** user approval, Claude **never**:

- `git push --force` (any form, including `--force-with-lease`).
- `git reset --hard`.
- `git rebase -i` on a branch that has been pushed.
- Delete branches (local or remote).
- Drop tables, truncate, or run destructive migrations against any non-local DB.
- Commit `.env`, `application-local.yml` with creds, or any file matched by `gitleaks`.
- Skip hooks (`--no-verify`, `--no-gpg-sign`).

If the user requests one of these, confirm scope and target explicitly before executing. A previous approval does **not** grant blanket permission.

## 7. Common Scenarios

### "I forgot to mention the issue"
Stop. Ask for the issue ID. If they create one mid-task, rename the branch:
```bash
git branch -m feat/PROJ-201-old-name feat/PROJ-201-correct-name
```

### "Pre-commit hook failed"
Read the failure. Fix the underlying problem. Re-stage. **Create a new commit** — never `--amend` to mask the failure (the original commit didn't happen, but the muscle memory of `--amend` after hook fail is dangerous).

### "Merge conflict on rebase"
Resolve in the obvious way. If unsure which side wins, ask the user. Never `git checkout --ours/--theirs` reflexively.

### "Multiple unrelated changes accidentally staged"
Unstage everything (`git restore --staged .`). Stage one logical group at a time, commit, repeat.

## 8. The "Done" Definition for a Commit

- [ ] Branch named `<type>/<issue-id>-<summary>`.
- [ ] Tests for the change exist and pass.
- [ ] ArchUnit clean.
- [ ] Migration (if any) present and tested.
- [ ] No secrets in diff.
- [ ] Commit message has `Intent:` + `Refs:` + Claude co-author.
- [ ] Only the files for this task are staged.
- [ ] Build green.
