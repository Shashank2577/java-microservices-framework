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

### 3.1 Auto-commit conditions (all must hold)

A commit is auto-created only when **every** condition is true:

1. **Atomic unit** — one coherent logical change. No grab-bags.
2. **All backend tests green**: `./gradlew :<affected>:check` includes unit, integration (Testcontainers), ArchUnit, contract tests, Flyway migration tests.
3. **Backend coverage ≥ thresholds**: 95% line / 90% branch on changed code, 98% on `domain`/`application` packages (see `java-code-quality` §7 and `java-testing` §8).
4. **All frontend tests green** if FE code is in the diff: Vitest/Jest unit + integration, Storybook interaction, MSW-backed API tests, Playwright smoke (≤5 min). See `java-testing` §13.
5. **Frontend coverage ≥ 95% line / 90% branch** if FE code is in the diff.
6. **Contract tests green** between FE and BE if either side changed shape.
7. **No secrets in diff** (`gitleaks` clean).
8. **Branch matches issue** per §1.
9. **No new high/critical CVEs** introduced (dep-check).
10. **No new Spotless/Checkstyle/SpotBugs/Error Prone/NullAway violations**.
11. **Vault updated** — today's `.vault/sessions/YYYY-MM-DD.md` has this commit appended; any new component/decision/finding/debugging/drift notes created; drift detection run (see `java-vault` §5, §7). Vault updates are staged in the same commit.
12. **User hasn't said "don't commit yet" in this turn**.

If any fails → don't commit. Report the failure clearly, including which test class / which file / what to fix.

**Never skip hooks. Never `--no-verify`.** A failing hook is a problem to fix, not a problem to bypass.

### 3.2 Full-stack PRs — one PR, end-to-end green

For features that touch both backend and frontend:

- One PR contains: BE code + BE tests + FE code + FE tests + contract updates + OpenAPI spec regenerated.
- CI runs: BE pipeline + FE pipeline + contract verification + E2E smoke. **All must be green** before merge.
- The commit message lists both stacks in `Changes:`:
  ```
  feat(orders): add cancellation flow

  Intent:
    ...

  Changes:
    - BE: CancelOrderUseCase, CancelOrderController, Flyway V004,
      OrderPlacedConsumer cancellation handling
    - FE: orders/cancel page, useCancelOrder hook, cancel button
      component, Playwright spec
    - Contracts: orders.order.cancelled.v1 schema added; cancel-order
      REST contract published

  Refs: PROJ-203
  ```

- Splitting BE-first / FE-later is **not allowed by default**. Reasons:
  - BE-only releases ship dead code (users can't trigger it).
  - FE-only releases hit unimplemented BE → 500s.
  - Reviews lose context (one PR's BE doesn't match the other's FE).
- Acceptable exceptions (each documented in the PR description):
  - Pure migration / refactor with no user-visible change.
  - Internal-only service with no frontend.
  - Feature behind a flag (`java-config` §6) — then BE can land first, FE second, both behind the flag.

### 3.3 Commit message — append a Test Plan block

Add to the existing commit-message format. Between `Changes:` and `Refs:`:

```
Test plan:
  - BE: 12 unit tests; 4 integration tests (Testcontainers Postgres+Kafka);
    Flyway migration test green
  - FE: 8 unit tests (Vitest); 3 component interaction tests (Storybook);
    1 Playwright smoke
  - Contract: orders-cancellation Pact verified against BE
  - Coverage: BE 96.4% line / 91.2% branch; FE 95.8% line / 90.6% branch
```

This makes the audit trail readable: a reviewer can see *what was verified* without re-running CI.

### 3.4 What changes for Claude

When auto-committing, Claude:
- Runs the full check before committing (`./gradlew check` + `npm test` if FE touched).
- Includes coverage numbers in the commit message (parsed from JaCoCo XML / Vitest output).
- Fails loud if coverage drops below threshold — does NOT commit a coverage regression.
- Refuses to commit "WIP" or red-build commits at all. There is no "save progress" exception.

### 3.5 Anti-patterns — add to existing §6 Destructive Action Policy

- "BE merged, FE coming tomorrow" without a flag
- Coverage dropped 1% → committed anyway "we'll add tests next sprint"
- Pre-commit hook bypassed with `--no-verify` because "this is just a typo fix"
- Commit message says "all tests pass" — claim without evidence. Coverage numbers must be in the message.
- Test plan block missing for non-trivial changes
- "Squash on merge" used to hide that intermediate commits were red

### 3.6 Per-Task Workflow

1. Verify branch (§1).
2. Implement change. Keep it scoped.
3. Run `./gradlew :<affected>:check` (+ `npm test` if FE touched). For cross-module work run full `./gradlew check`.
4. Stage **only the files relevant to this task** (`git add <paths>`). **Never** `git add -A` blindly.
5. Compose the commit message per §2 + §3.3 (Test plan block with coverage numbers).
6. Commit via HEREDOC:
   ```bash
   git commit -m "$(cat <<'EOF'
   feat(orders-svc): add transactional outbox for OrderPlaced events

   Intent:
     ...

   Test plan:
     ...

   Refs: PROJ-142
   Co-Authored-By: Claude <noreply@anthropic.com>
   EOF
   )"
   ```
7. Report commit SHA + one-line summary to the user.

### 3.7 Multi-Step Features
Split into multiple **small commits**, each green, each with its own Intent + Test plan block. Prefer 5 focused commits over one giant WIP. Never push a red commit.

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
