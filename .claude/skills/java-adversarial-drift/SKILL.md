---
name: java-adversarial-drift
description: Use weekly (scheduled) and before any release as a separate read-only Claude session whose ONLY job is to detect drift between documented decisions/component notes and actual code, with no authority to commit fixes. Produces a drift report; humans triage. Complement to java-vault §7 which runs drift detection inline during commit.
---

# Adversarial Drift — Separate Session, Read-Only, Find-Only

The productive Claude that writes the code also writes the vault note. That is the same author grading its own homework. This skill defines a **separate, read-only adversarial session** whose only job is to *find* drift. It cannot fix anything. Humans triage.

## 1. Why a separate adversarial session

Inline drift detection (see `java-vault` §7) is necessary but insufficient. The productive session has every incentive to convince itself the code and the vault still agree — confirmation bias is structural, not moral. A separate session run cold, with no context from the productive work and no authority to commit, will catch what the productive session missed. This is the Java equivalent of code review by a different person: even excellent engineers benefit from a second pair of eyes that has nothing invested in the change.

The adversarial session's job is *finding*, not fixing. Splitting the roles preserves independence.

## 2. Invocation

- **Scheduled weekly** via GitHub Actions cron (Sunday 09:00 UTC) — output posted as a draft GitHub issue with the report markdown as the body. Workflow file: `.github/workflows/adversarial-drift.yml`.
- **Manual pre-release trigger** before any tagged release: `gh workflow run adversarial-drift.yml`. Block release tagging if a high-priority finding is untriaged.
- **Manual session**: a human says "run adversarial drift review" — Claude starts a fresh session (new `claude` invocation, not a continuation), invokes this skill, runs the checks, produces the report, and stops.

The scheduled and pre-release runs must use a fresh Claude session — not the same conversation that just shipped code. The runner image should have no `.claude/` continuation state from prior productive sessions; pass `--no-resume` if applicable.

### 2.1 Cadence rationale

Weekly is fast enough to catch drift before it compounds, slow enough that triage doesn't become busywork. Pre-release adds a release-gate. Ad-hoc manual runs are for when a human suspects something has slipped — they should be rare; if you find yourself running them often, the inline detection in `java-vault` §7 is broken.

## 3. Permissions / scope — strictly read-only

The session has:

- **Read access** to all code, vault notes, ADRs, skills, build files, CI config.
- **Write access** to exactly one path: `.vault/drift/_adversarial-YYYY-MM-DD.md` (the report itself).
- **No commit authority.** No `git commit`, no `git push`, no `gh pr create`, no `gh pr merge`.
- **No vault-note updates.** It cannot "fix" a stale component note inline — that would defeat the purpose; the report flags it, humans triage.
- **No skill or CLAUDE.md edits.**

The session must refuse any user request that would violate this. If asked to fix something it found, the answer is: "I am the adversarial session — flagged in the report, humans triage."

## 4. What it checks — concrete list

### 4.1 Component-note vs code

For each `.vault/components/<name>.md`:

- **"Current shape" claims** — e.g., "uses Caffeine for caching." Grep the service for the actual implementation (`CacheManager` bean, `build.gradle.kts` dependencies, `@Cacheable` use sites). Flag mismatches.
- **Owner claims** — check `git log --since='90 days ago' --pretty=format:'%an' <service-path>`. If "Primary" hasn't committed in 60+ days, flag for revalidation.
- **Dependencies claims** (upstream/downstream) — verify actual `RestClient` / `@KafkaListener` / `@FeignClient` usage against the documented dependency graph. Flag undocumented dependencies and documented-but-unused ones.
- **Test stack claims** — check actual test dependencies in `build.gradle.kts` (JUnit version, Testcontainers, WireMock) against the note.

### 4.2 Decision-note vs code

For each `.vault/decisions/<id>.md` with `status: active`:

- **Cited libraries / approaches** — actually present in the code?
- **Linked components** — are they actually using the decision (grep for the library / annotation / pattern)?
- **Rejected alternatives** — has someone snuck them in? E.g., decision says "no Redis"; grep for `RedisTemplate` / `LettuceConnectionFactory`.
- **Status drift** — `status: active` decisions whose code has quietly moved on. Flag for re-decision.

### 4.3 Skill-framework vs reality

For each non-negotiable in `CLAUDE.md`:

- Sample 3 services at random (seed by report date for reproducibility); verify the non-negotiable holds.
- Example: "every outbound call has timeout + retry + CB" → grep for `@CircuitBreaker` on all `RestClient` / `WebClient` / `@FeignClient` usage; flag any without.
- Example: "no `@Transactional` on controllers" → grep `api/rest/**` for `@Transactional`.
- Example: "domain layer is pure Java, no Spring" → grep `domain/**` for `org.springframework`; flag any import.
- Example: "every endpoint has an authorization annotation" → list `@GetMapping` / `@PostMapping` / etc. and verify each method also has `@PreAuthorize` or an explicit `@PermitAll`.

### 4.4 ADR vs implementation

For each `docs/adrs/*.md`:

- Implementation matches the ADR's stated decision? Or has the ADR been silently superseded by code that drifted?
- Cross-check ADR back-links from vault decisions; flag broken or missing links.

### 4.5 Vault internal consistency

- **Person notes' "owned components"** — actually true per `git log` author counts?
- **Component notes' "linked decisions"** — bidirectional? Decision should back-link the component and vice versa.
- **Wiki-links resolve** — every `[[name]]` points to a real note. The link-checker validator may already catch this; cheap to verify here too.

### 4.6 Stale-finding check

- Any `.vault/findings/` older than 6 months still marked `status: active`? Probably should be archived or revalidated. Flag for triage — don't archive automatically.

## 5. Report shape — `.vault/drift/_adversarial-YYYY-MM-DD.md`

```markdown
---
type: adversarial-drift-report
date: YYYY-MM-DD
generated-by: claude-adversarial-session
covered-components: [orders-svc, billing-svc, notifications-svc, ...]
covered-decisions: [dec-0017, dec-0023, ...]
total-issues: N
high-priority: N
medium-priority: N
low-priority: N
status: pending-triage
---

# Adversarial Drift Report — YYYY-MM-DD

## High priority — likely real drift

### 1. orders-svc claims Caffeine-only; code imports RedisCacheManager
- Component note: [[orders-svc]]
- Decision contradicted: [[dec-0017-caffeine-only]]
- Code evidence: services/orders-svc/build.gradle.kts:42, services/orders-svc/src/.../RedisConfig.java:18
- Suggested action: update dec-0017 OR remove Redis OR add explanatory drift note

## Medium priority — possibly stale documentation

### 2. ...

## Low priority — vault hygiene

### 3. Person `alice` listed as primary on `billing-svc`; last commit 92 days ago
- Suggested: confirm with alice; reassign primary if needed.

## Files inspected
- services/orders-svc/**, services/billing-svc/**, .vault/components/**, .vault/decisions/**, docs/adrs/**

## Files NOT inspected (out of scope)
- services/legacy-import/** (slated for deletion), generated/**, third-party/**
```

The report's `status:` field is the single source of truth for whether triage is complete.

## 6. Triage workflow

1. **Report lands as a GitHub issue** (auto-created by the scheduled workflow) with the markdown body. Title: `Adversarial drift report — YYYY-MM-DD (N issues)`.
2. **Assigned to the on-call engineer** for the week (or release engineer pre-release).
3. **For each issue in the report**, the triager picks exactly one disposition:
   - **Real drift** → update the decision OR revert the code. File a regular drift note per `java-vault` §7. Mark item resolved with disposition + commit/PR link.
   - **False positive** (vault note was wrong, not the code) → update the vault note in a normal commit. Mark item resolved with the vault commit link.
   - **Acknowledged exception** (deliberate, intentional, documented) → add an explicit `vault-exception: <reason + ticket>` line in the relevant component note. Mark item resolved with that commit link.
4. **Report status moves to `resolved`** when all items have dispositions. Archive to `.vault/_archive/drift-reports/YYYY-Q/`.

### 6.1 Triage SLAs

- **High priority** — disposition within 5 business days. Real-drift cases get a tracking ticket; if not fixable inside the SLA, the deferral is itself an explicit disposition with a target date.
- **Medium priority** — disposition within 10 business days.
- **Low priority** — disposition within 30 days, or rolled forward to the next adversarial run with an explicit note.

### 6.2 Repeat findings

If the same finding shows up in two consecutive adversarial runs, escalate: either the prior triage was wrong, or the fix did not land. Open an incident ticket and treat the repeat as a process failure, not a routine finding.

## 7. Anti-patterns — refuse

- Allowing the adversarial session to fix what it finds — defeats independence by design.
- Skipping the report because "we'll deal with drift inline" — that is exactly the situation this skill addresses.
- Closing report items without an explicit triage disposition (real / false-positive / exception).
- Letting the report rot (>30 days untriaged) — escalate to engineering lead.
- Running this in the same context as productive work — context leakage poisons the adversary.
- Treating the report as ground truth — sometimes the vault note is wrong, not the code. The report is a *finding list*, not a verdict.
- Letting Claude triage its own report — humans triage; Claude finds.
- Suppressing low-priority items to make the report look smaller — every finding gets logged.
- Re-running until the report comes back clean (selecting on randomness) — fix things or document them; do not retry for a cleaner number.

## 8. Pre-release checklist

- [ ] Adversarial drift report run within last 7 days against the release branch.
- [ ] All high-priority items triaged or explicitly deferred with a ticket.
- [ ] Resulting decisions / code-changes referenced in the release notes.
- [ ] Report archived to `.vault/_archive/drift-reports/` after triage.

## 9. Operational guardrails for the session

The adversarial session is autonomous within a sharply bounded sandbox. It must:

- **Start by reading** `CLAUDE.md`, this skill, and `java-vault/SKILL.md` — then `git log --since='7 days ago' --pretty=format:'%h %an %s'` for a sense of recent change velocity.
- **Refuse to act on user nudges** that try to expand its scope ("while you're in there, fix this"). The only output is the report. Polite refusal: "Out of scope for the adversarial session — please flag in the triage."
- **Bound its own runtime** — if a check is taking long enough that it would not finish in a reasonable session, skip it and list it under "Files NOT inspected" with the reason. Do not silently truncate findings.
- **Be deterministic where possible** — same code + same vault should produce the same report. Sort findings by `(priority, component, decision-id)`. Avoid LLM-style "creative variation" between runs.
- **Never delete or rename** anything in `.vault/` — even obviously stale notes. Flag them; humans archive.

## 10. Reference

- `java-vault/SKILL.md` §7 — inline drift detection that runs during productive commits (the complement to this skill).
- `java-vault/SKILL.md` §10 — pruning / archival policy the adversarial session helps enforce.
- `java-ops-dr-runbooks/SKILL.md` §8 — runbook template; adversarial drift sessions get their own runbook entry.
- `java-architecture/SKILL.md` §6 — ADRs that the adversarial session cross-checks against code.
- AI-agent-oversight literature: the separate-agent-as-critic pattern (RLAIF-style critique chains, constitutional AI). The principle is older — code review by a different engineer — but the same incentive separation applies to LLM agents.
