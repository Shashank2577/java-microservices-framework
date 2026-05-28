---
name: java-framework-metrics
description: Use to track whether the framework itself is working. Defines outcome metrics (services shipped, coverage, drift rate, framework adherence), leading indicators (vault health, skill usage, validator status), and review cadence. Without these, the framework grows without feedback — bigger, not better.
---

## 1. The question this skill answers

"Is this framework helping us ship better software, or just adding ceremony?"

Without explicit metrics, the answer is whatever feels true. Felt-truth is wrong half the time. This skill defines what to measure, what good looks like, and when to re-evaluate.

The framework has 29 skills and a vault. That's a lot of surface area. Surface area without a feedback loop becomes cargo cult. This document is the feedback loop.

A second purpose: a metrics document forces the framework's authors to commit, in writing, to what success looks like. That commitment is easy to write at the start and uncomfortable to face at quarter-end. The discomfort is the feature.

## 2. Outcome metrics — what we ultimately care about

These are slow signals (months). They're what justifies the framework's cost.

| Metric | Definition | Target | Bad signal |
| --- | --- | --- | --- |
| **Services shipped to production** | Count of `lifecycle: production` services with at least one user request | ≥1 by month 3; ≥3 by month 6 | 0 at month 6 → framework not being adopted |
| **Mean time from PRD-handed-to-Claude → first prod commit** | Calendar days | < 5 days for a small service | > 14 days → friction too high |
| **Coverage on shipped code** | Aggregated JaCoCo line coverage | ≥ 95% line / 90% branch | Drops below 90% → coverage gates ignored |
| **Mutation score** | PIT mutation score on domain + application | ≥ 85% | < 70% means coverage is high but assertions are weak (the coverage-gaming risk) |
| **Production incidents per service per quarter** | Sev-1 + Sev-2 incidents from `.vault/debugging/` and PagerDuty | ≤ 2 per service | Trending up → framework's resilience patterns not working |
| **MTTR per incident** | From PagerDuty / vault debugging notes | < 1 hour for Sev-1, < 4h for Sev-2 | Stable or rising → runbooks not landing |
| **Time-to-onboard a new engineer to a service** | Days from "first day" to "first merged PR" | < 10 working days | > 20 days → framework opaque |

Notes on the choices:

- **Services shipped** is the only metric that cannot be faked. A service is either in production handling user requests or it isn't. Every other outcome metric is a quality dimension of that primary fact.
- **Mutation score paired with line coverage** is deliberate. Line coverage alone is a notorious gaming target — you can drive it to 100% with assertion-free smoke tests. The pair forces honest testing.
- **MTTR** belongs here because the framework's resilience and observability skills exist to make incidents short. If incidents are long, those skills aren't being applied or aren't working.
- **Onboarding time** is the one metric that catches "framework grew opaque." A framework that only its authors can navigate has failed regardless of the other numbers.

## 3. Leading indicators — weekly signal

These are fast signals (days/weeks). They predict the outcome metrics. Worth tracking on a dashboard.

| Indicator | What it tells you | Where it lives |
| --- | --- | --- |
| **Vault session entries per week** | Activity level; if 0 something's broken | `.vault/sessions/` count |
| **New `decisions/` per week** | Whether decisions are being captured | grep `.vault/decisions/` by date |
| **Open drift items** | Reality vs documentation gap | `.vault/drift/` with `status: open` |
| **`findings/` count per week** | Discovery rate — too high = chaotic; zero = under-investigating | `.vault/findings/` |
| **Framework-validator status (green/red)** | Skill consistency | GitHub Action history |
| **Adversarial drift report — high-priority issue count** | Documentation drift severity | latest `.vault/drift/_adversarial-*.md` |
| **Skill invocation frequency** | Which skills are pulling their weight | parse Claude session logs |
| **Stale-skill score** | Skills not invoked in 60+ days | derived from above |
| **CI flake rate** | Test reliability | GitHub Actions success-on-first-try ratio |
| **Renovate PRs open > 30 days** | Dep maintenance | `gh pr list --label renovate` |

Watch for two failure shapes in the leading indicators:

1. **Silence** — zero vault entries, zero findings, zero decisions, but commits are happening. The framework is being bypassed. Investigate before celebrating velocity.
2. **Noise** — high findings count, high open-drift count, validator red. The framework is being followed but isn't keeping up with reality. Either prune the framework or invest in the gaps.

Stale-skill score deserves a special note. If a skill hasn't been invoked in 60 days, one of three things is true: (a) the work it covers hasn't come up, (b) people forgot the skill exists, (c) the skill is wrong and people route around it. Each calls for a different fix. Don't reflexively delete; investigate.

## 4. Anti-metrics — what NOT to measure

Avoiding Goodhart's law:

- "Lines of skills written" — bigger ≠ better; we already saw this critique
- "Number of skills" — more isn't better
- "Vault note count" — quantity without distillation is rot
- "Commits per day" — gamed easily
- "Coverage % alone" — gameable; mutation score balances it

A metric you can game without delivering value is worse than no metric. It creates the appearance of progress while masking its absence.

The rule: if a metric can go up while the underlying thing it represents goes down, drop it. "Number of skills" can grow while skill quality collapses. "Vault note count" can grow while signal-to-noise rots. These metrics are not just useless — they actively mislead.

## 5. Review cadence

| Cadence | What happens | Who |
| --- | --- | --- |
| Weekly (Monday) | Review leading indicators dashboard; pick 1 to act on | Tech lead |
| Per release | Outcome metrics snapshot; comparison vs last release | Tech lead + 1 engineer |
| Quarterly | Full retrospective: framework retained / refined / removed sections | Whole team |
| Annually | Strategic check: is this framework still the right framework? | Tech lead + product |

A cadence without an action is theater. Every review must produce at least one decision: keep, change, remove. Write it down.

The weekly review should take 15 minutes. If it takes longer, the dashboard is wrong, not the cadence. The quarterly retrospective should take 2 hours and produce a written artifact. If it takes a day, the framework has too much surface area and that itself is the finding.

## 6. Dashboard implementation — pragmatic

Don't build a dashboard. Build a script.

`tools/metrics-snapshot.py` reads:

- The vault (counts of sessions, decisions, findings, open drifts)
- The validator status (GitHub Actions API)
- The Jacoco XML reports (coverage)
- The PIT reports (mutation score)
- The PagerDuty API (incidents) — if configured

Outputs:

- `.vault/_meta/metrics-YYYY-MM-DD.md` — weekly snapshot, committed
- A summary line to a Slack channel (optional)

Over time, you get a longitudinal record in the vault. Charts can come later when you actually need them. A markdown file checked into git beats a Grafana dashboard nobody opens.

Why a script and not a service: the script can run in CI weekly, fail loudly when a source is broken (coverage XML missing, PagerDuty unreachable), and produce a diff against last week. A dashboard hides those failures behind "no data" gaps that nobody notices. Loud failures are a feature.

Minimum viable snapshot, in markdown:

```
# Metrics snapshot — 2026-05-25

## Outcomes (last 30 days)
- Services in production: 2
- Mean PRD→prod: 6.3 days (up 1.1 from last month)
- Aggregate line coverage: 94.1% (FLAG: under 95% target)
- Mutation score: 81.4%
- Incidents this month: 1 (Sev-2)

## Leading (this week)
- Sessions: 14, Decisions: 3, Findings: 5, Open drift: 4
- Validator: green for 11 days
- Stale skills (>60d): 3 — flagged below
- CI first-try pass rate: 89%

## Actions taken from last week's review
- Closed drift item #12 (vault layout vs reality)
- Pruned `java-deprecated-pattern` skill — unused 90 days
```

That format is enough. Resist the urge to make it pretty.

## 7. Re-evaluation triggers — when to question the framework

The framework is suspicious if any of:

- 3+ consecutive quarters of no production services shipped
- Mutation score chronically < 70% while line coverage is > 95% (you're gaming)
- > 10 open drift items at any time
- Adversarial drift report finds > 5 high-priority issues in a single run
- Engineers cherry-pick skills (some files have `.framework-skip: true` headers proliferating)
- Onboarding time trending up
- "Skip framework, we need to ship" appears more than once in a quarter

If three of these hit at once, do a framework retrospective. Maybe the framework is wrong; maybe it's right and execution is off. Find out — don't guess.

The hardest of these to spot is the last one. "Skip framework, we need to ship" is rational under deadline pressure but corrosive over time. If you hear it once a quarter, that's normal. Twice means people no longer believe the framework helps them ship. That belief, once lost, is hard to rebuild and the framework will be quietly abandoned regardless of what the validator says.

## 8. Honesty checks — the meta-metric

Every quarter, ask:

1. **Did the framework save us time, or cost us time?** Estimate net effect.
2. **What did we add that we shouldn't have?** Remove it.
3. **What did we omit that we should have?** Add it.
4. **What did we believe at quarter-start that turned out wrong?** Surface, don't bury.

Honest answers go in `.vault/_meta/quarterly-retrospective-YYYY-QN.md` and stay forever. Past retrospectives are read at the start of the next one — that's how you compound learning instead of repeating mistakes.

Two common dishonesty patterns to watch for in retrospectives:

- **Recency bias dressed as judgment.** "Last sprint went well, so the framework is working." One sprint is noise. Track the rolling quarter.
- **Sunk-cost ratification.** "We've already built it, so let's keep it." A skill you stop using costs zero to delete and a recurring cognitive tax to retain. Delete liberally.

If a quarter's retrospective contains only positive findings, that itself is a finding. Reality is messier than that. Either the team is not looking hard, or not writing what they see.

## 9. Anti-patterns — refuse

- Tracking metrics with no review cadence
- Reviewing without an action ("interesting, moving on")
- Adding metrics that aren't actionable
- Hiding bad metrics
- Conflating output (commits) with outcome (working software)
- "Framework adherence score" — measures compliance, not value

The last one is the seductive trap. It's easy to measure "did you follow the process." It's hard to measure "did the process help." Always prefer the hard measurement.

A worked example: imagine a dashboard tile showing "92% of PRs cite a relevant skill." High number, looks great. But if those services then ship slowly, leak bugs, and onboarding takes weeks, the citation rate measures only ritual. The outcome metrics in section 2 would catch this; the adherence score would hide it.

## 10. Pre-merge checklist (for framework changes)

- [ ] New skill added → which leading indicator measures whether it's working?
- [ ] Skill removed → confirmed via metrics that nobody was using it?
- [ ] Threshold changed → captured in `.vault/decisions/` with measurement justifying

If a framework change can't answer "how will we know this helped?", it doesn't ship. The framework itself is held to the same goal-driven-execution bar as the services it builds.

For removals specifically: don't ask "is anyone still using this?" — most people won't speak up. Ask the script. If the skill has zero invocations in 60 days and no quarterly retrospective mentions it, that's evidence enough to retire it. Move the file to `.vault/_archive/` instead of deleting outright, so a future engineer can resurrect it if reality changes.

## 11. Reference

- Goodhart's Law (the warning): "When a measure becomes a target, it ceases to be a good measure."
- DORA metrics (the inspiration but not the prescription — they're org-level)
- Google SRE book Chapter 4 (Service Level Objectives — the same logic applied here)
- *The Goal* by Eliyahu Goldratt — for the discipline of distinguishing throughput from busyness
- *Accelerate* by Forsgren, Humble, Kim — for the evidence that measurement-driven engineering compounds

## 12. First-30-day rollout

Don't try to stand up every metric on day one. The framework is new, and a metrics regime imposed before there is anything to measure becomes its own ceremony. Stage it.

- **Week 1.** Create `tools/metrics-snapshot.py` as a stub that just counts vault directories and writes the markdown file. Commit it. The act of committing the stub commits the team to the cadence.
- **Week 2.** Add coverage and mutation parsing from JaCoCo + PIT XML. These exist the moment the first service has tests.
- **Week 3.** Add validator status from GitHub Actions. Wire the weekly Monday review.
- **Week 4.** First quarterly retrospective template lands in `.vault/_meta/`. PagerDuty integration is optional and can wait until there are real incidents to track.

By the end of month one, the dashboard is producing snapshots with real data, the weekly cadence is running, and the team has practiced the muscle of "review → decide → write it down" at least four times. That muscle matters more than any specific metric.

## 13. What this skill is not

This skill does not define service-level SLOs. Those live with each service. This skill defines how we know whether the framework that produces those services is itself healthy. The two are related but distinct: a service can have a great SLO record while the framework that built it has rotted to the point where the next service will take twice as long. Track both, separately.

This skill also does not replace human judgment. The metrics surface evidence; engineers decide what to do with it. A bad quarter on the numbers might be the right outcome (e.g., a hard-learned lesson) and a good quarter might be hollow (e.g., no real work attempted). Numbers without narrative are noise.

## 14. How this skill measures itself

The recursive question: does this metrics skill itself help? Apply its own rules.

- **Outcome.** Has the team caught at least one bad framework decision earlier because of the snapshot, in the past two quarters? If yes, the skill is paying for itself. If no for two consecutive quarters, retire or radically rewrite it.
- **Leading indicator.** Is the weekly snapshot file actually being committed? `git log .vault/_meta/metrics-*.md` should show roughly one new file per week. Gaps mean the cadence broke.
- **Anti-pattern check.** Are we adding metrics to this skill faster than we are removing them? If so, this document is becoming the kind of bloat it warns against. Cap the table sizes; force a deletion before each addition.
- **Honesty check.** When this skill says something uncomfortable about the framework, do we act on it, or do we explain it away? Track the ratio across quarters. Explanations greater than actions = the skill has become decorative.

If the metrics-skill fails its own checks, that is the cleanest possible signal that the framework's feedback loop is broken. Fix the loop first, then everything downstream.

A final discipline: when this skill is invoked during a review and a metric is missing or unreliable, do not paper over the gap. Record the gap explicitly in the snapshot ("PagerDuty integration broken — incident metric unavailable this week") and treat fixing it as a first-class task. A dashboard that silently drops bad metrics teaches the team to distrust all of them. A dashboard that loudly admits its gaps earns the trust that makes the rest of the framework worth keeping.

The framework will outlive any single engineer who worked on it. The metrics are how that future team — possibly without context for why a given skill exists — decides what to keep. Write the metric, write the threshold, write the action. Then trust the future to honor it.

That trust is reciprocal: today's authors must write metrics honest enough that tomorrow's team is willing to act on them. Vague targets and missing thresholds invite paralysis. Specific numbers, even imperfect ones, invite decisions. Choose specificity.
