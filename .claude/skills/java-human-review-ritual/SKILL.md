---
name: java-human-review-ritual
description: Use to enforce the human-in-the-loop checkpoints that prevent Claude from operating in a closed loop. Defines daily / per-PR / weekly / quarterly review rituals, what the human looks at, when Claude must escalate vs. proceed, and the "stop-the-line" triggers that pause autonomous work.
---

# Java Human Review Ritual

## 1. The closed-loop risk

Claude writes code. Claude writes the vault note about the code. Claude reads the vault note on the next session. Claude trusts the note. Claude continues building on the trusted note. **At no point does a human verify any of it.**

In short bursts this is fine. Over weeks, it produces accurate-looking output that may be subtly wrong, and nobody knows. The risk compounds: errors become "history," history becomes "convention," convention becomes "the way we do things."

This skill prevents that. It defines explicit human checkpoints at multiple cadences so a human signal lands before the loop closes.

## 2. The four checkpoints

| Cadence | What the human looks at | Estimated time | Output |
| --- | --- | --- | --- |
| **Per PR** (every commit Claude wants to merge) | The PR itself: code diff + commit Intent + test plan + linked vault entries | 5-15 min | Approve / request changes |
| **Daily standup** (15 min, end of day) | Today's `.vault/sessions/YYYY-MM-DD.md` | 5 min | Notes on what surprised you |
| **Weekly review** (Mon 30 min) | Last 7 days of vault: sessions, new decisions, open drifts | 15-20 min | Triaged drift items, decision on any escalations |
| **Quarterly retrospective** (90 min) | Last quarter's metrics, distillation, vault archive | 60-90 min | Framework retained/refined/removed; entries archived |

## 3. Per-PR — what good review looks like

PRs land via the framework's auto-commit protocol (java-git-workflow §3). Even with all the gates green, the human reviewer must:

- **Read the Intent block.** Does it actually justify the change? Does Claude's reasoning hold up? An Intent that reads "refactor for clarity" with no further detail is a failed Intent — push back.
- **Skim the diff.** Spot-check 3-5 files. The framework guarantees the build is green; the human guarantees the code is *right*. Look especially at: new public APIs, anything touching transaction boundaries, anything that changes a constructor or DI wiring.
- **Read the linked vault entries.** Decisions and drift notes attached to the PR — do they reflect reality? Cross-reference one claim against the actual code.
- **Sanity-check test quality.** High coverage + low mutation score = gaming. Open one test file and ask "would this test fail if the implementation was wrong?" If every assertion is `assertNotNull`, the test is worthless.
- **Check the test plan in the commit message.** Numbers reasonable? Did Claude run what it said it ran? Re-run one suite locally if you have any doubt.

Approve only when these pass.

If you find yourself approving without reading: stop. The reviewer's signal is what makes the auto-commit protocol safe. Without it, Claude is committing to itself and the framework collapses into theatre.

Rotate primary reviewer weekly so no single human becomes a rubber-stamp. The reviewer's name goes in the PR description; the reviewer's name goes in the vault entry; the reviewer is accountable.

### 3.1 PR review checklist (paste into PR description)

```
[ ] Intent block justifies the change
[ ] Diff spot-checked — 3-5 files, including any public-API change
[ ] Linked vault entries match the code
[ ] One test inspected; assertions are real
[ ] Test plan numbers match local re-run (if doubt)
[ ] No new TODO / FIXME without ticket
[ ] No new SuppressWarnings without justification in code comment
```

If any box can't be ticked honestly, request changes — don't approve "with a note."

## 4. Daily standup (the lightest checkpoint)

End of day, 5 minutes. Open `.vault/sessions/YYYY-MM-DD.md`.

Ask:
- Anything surprising in today's findings?
- Any open drift items?
- Anything Claude wrote in the session summary that looks wrong?
- Is the work for tomorrow clearly defined?
- Are the linked PRs and tickets the ones you actually expected to land today?

This is the lightest, most valuable signal. The day is fresh; you remember context. Five minutes prevents a week of bad accumulation.

If a session note has nothing of substance ("processed PROJ-N, no surprises"), that's also signal — either work is going well or Claude isn't recording deeply enough. The fix is usually "tell Claude to record more, not less." Vague summaries are a leading indicator of closed-loop drift.

If you skip the daily for a day, that's okay; if you skip for three days running, treat it as a stop-the-line: catch up before any further auto-commits land.

### 4.1 What "surprising" actually means

Use this checklist when the daily question "anything surprising?" feels too vague:

- A class or package you don't remember discussing was touched.
- The session note cites a library or pattern you didn't expect this team to use.
- A "minor refactor" landed alongside the main change.
- A test count dropped (even by one) without an explicit reason.
- A new dependency appeared in `pom.xml` / `build.gradle`.
- The session ran longer than usual with little visible output.
- Claude described doing something it has never reliably done before, with no learning notes.

Any of these warrants 60 more seconds of digging.

## 5. Weekly review (the substantive checkpoint)

Monday morning, 30 minutes. Open `.vault/INDEX.md`.

Walk through:
1. **Recent sessions** (~7 entries). Skim for patterns: same kind of work? same friction? same surprise? Patterns across a week reveal what daily review can't — e.g. "Claude debugged the same Hibernate issue three times this week."
2. **New decisions.** Read in full. Do you agree with each? Anything to escalate? Decisions are the most load-bearing artefacts in the vault — a wrong decision propagates.
3. **Open drift items.** Triage each: real drift / vault wrong / intentional. Resolve or assign. Drift that lingers > 2 weeks becomes background noise; close the loop early.
4. **New findings.** Do they suggest a framework change? A new skill? An update to an existing skill? Findings without follow-through are wasted observation.
5. **Adversarial drift report (if landed this week).** Triage per `java-adversarial-drift` §6.
6. **Reviewer health.** Did per-PR reviews actually happen? Look at the reviewer column — if one human approved 80% of PRs, that's a closed loop forming.

Output: one paragraph appended to `.vault/_meta/weekly-review-YYYY-MM-DD.md`. What you saw, what you decided, what's queued for the team. The paragraph is short on purpose — if it's longer than 10 lines, you're writing an essay instead of reviewing.

## 6. Quarterly retrospective (the strategic checkpoint)

End of quarter, 90 minutes. Whole team.

Walk through:
1. Outcome metrics (per `java-framework-metrics`)
2. Distill the last quarter's findings → `.vault/_meta/wisdom-YYYY-QN.md`
3. Archive sessions older than 90 days → `.vault/_archive/sessions/YYYY-QN/`
4. Identify decisions that should promote to ADRs → move to `docs/adrs/`
5. Review skills: which to keep, refine, remove
6. Honest answers to the four meta-questions (`java-framework-metrics` §8)
7. Plan next quarter's framework changes

Output: `.vault/_meta/quarterly-retrospective-YYYY-QN.md`. Honest. Specific.

A quarterly retrospective that ends with "everything's fine, keep going" is suspect. Every quarter has at least one thing the framework got wrong — find it and name it.

### 6.1 Quarterly invariants to recheck

These don't fit naturally into weekly review but must be checked at least quarterly:

- **Skill inventory.** List every `SKILL.md` in `.claude/skills/`. For each: still used? still accurate? still needed?
- **Vault size.** Sessions older than 90 days archived? Decisions promoted to ADRs where appropriate?
- **Reviewer distribution.** Per-PR approval counts per human; no one should dominate.
- **Escalation log.** Every escalation closed or re-justified? Open escalations > 30 days = root-cause failure.
- **Stop-the-line frequency.** Triggering too often (> 1/week) means the triggers are noisy; never triggering means they're too loose.

## 7. Stop-the-line triggers

Some signals require *immediate* human attention, not wait-for-the-next-checkpoint. Claude must escalate and pause autonomous work when:

- **First-time error pattern** — bug class never seen before. New `.vault/debugging/` entry → page the on-call.
- **Drift detected on a load-bearing decision** — anything tagged `#load-bearing` in `.vault/decisions/`.
- **Quality gate broken** — coverage drops below 95% despite Claude's effort. Could indicate untestable code or genuine architecture issue.
- **Adversarial drift report has > 3 high-priority issues** — schedule the triage session immediately, don't wait for Monday.
- **Vault validator fails** — `tools/validate-framework.py` red. Don't trust the vault until fixed.
- **Repeated debugging in same area** — third time hitting the same kind of bug in the same module = architectural problem, not a coding problem. Pause and address root cause.

When a stop-the-line trigger fires, Claude:
1. Writes a `.vault/_meta/escalations/YYYY-MM-DD-<symptom>.md` capturing what tripped, the relevant evidence (file paths, log snippets, test names), and the proposed next step.
2. Opens a GitHub issue labeled `framework:escalation` linking the escalation note.
3. Does not auto-commit further work in the affected area until a human responds — work in unrelated areas may continue.

Stop-the-line is not punishment; it is the framework's load-bearing safety mechanism. The cost of pausing for an hour is far less than the cost of compounding a bad assumption for a week.

## 8. Anti-patterns — refuse

- "Approve all" without reading the diff
- Weekly review skipped "this week's busy" — that's exactly when you need it
- Daily standup that never reads the vault — the vault is the day's record
- Quarterly retrospective that doesn't archive anything — vault grows unbounded
- "Claude is fine, I trust it" without verifying — trust requires evidence
- Stop-the-line triggers ignored because they're noisy — fix the triggers, don't ignore them
- Reviewer who only checks the framework gates were green — those are necessary, not sufficient

## 9. When the human disagrees with the vault

The whole point of these rituals is that disagreements surface. When they do:

1. **Do not silently edit Claude's vault entry.** That destroys the audit trail. The vault is append-only at the entry level.
2. **Add a `## Human correction` block** at the bottom of the entry, dated and signed.
3. **If the correction invalidates a downstream decision**, file a drift item in `.vault/drift/` referencing both the original entry and the correction.
4. **Tell Claude.** Next session start: "Read .vault/sessions/YYYY-MM-DD.md — note the Human correction. Treat the corrected version as ground truth."
5. **If the same kind of disagreement recurs**, that's a skill gap — update the relevant skill so Claude stops making the same mistake.

The corrections themselves become first-class evidence at the weekly review: every human correction is one closed-loop break prevented.

## 10. What counts as "the human looked"

The whole ritual hinges on a human actually engaging, not just signing. Distinguish:

- **Engaged review** — eyes on the diff, mind on the Intent, fingers running a sanity command. Leaves at least one concrete observation in the PR or vault, even if it's "looks good because X".
- **Performative review** — clicked approve, said "lgtm", left no evidence of engagement. Counts as zero. The closed loop forms here.

The simplest way to keep yourself honest: every review you do, write one sentence somewhere about what you actually saw. If you can't write the sentence, you didn't do the review.

This applies at every cadence:
- Per-PR: one sentence in the PR comment.
- Daily: one sentence in `.vault/sessions/YYYY-MM-DD.md` under "## Human notes".
- Weekly: the weekly-review note.
- Quarterly: the retrospective.

Four artefacts; four sentences minimum per cadence. That's the floor.

## 11. The default

Without these rituals, the closed loop forms. With them, every week a human looks the framework in the eye and asks: *is this going where I want?*

The whole framework is worth the time it costs only if you actually do these reviews. Skipping them is the most likely failure mode.

## 12. Calendar integration

- Per-PR: organic, when PRs are reviewed.
- Daily: 17:30 calendar block, 5 min.
- Weekly: Monday 09:30 calendar block, 30 min, recurring.
- Quarterly: Last Friday of quarter, 14:00, 90 min, recurring.

Put them in the calendar. If they're not in the calendar, they won't happen.

If a ritual is genuinely impossible at its slot (release crunch, vacation), reschedule it explicitly — write the new slot in `.vault/_meta/calendar.md`. Do not silently skip. A skipped ritual that nobody acknowledges is exactly how the closed loop reasserts itself.

## 13. Reference

- `java-vault/SKILL.md` — what the human is reviewing
- `java-framework-metrics/SKILL.md` — what numbers to look at
- `java-adversarial-drift/SKILL.md` — supplements the human review with adversarial machine review
- `java-git-workflow/SKILL.md` §5 — PR template + when to merge
