---
id: dec-0001
type: decision
tags: [#decision, #ci, #docs]
date: 2026-05-29
authors: [[shashank]], [[claude]]
supersedes: []
superseded-by: []
status: active
linked-commits: []
linked-tickets: []
linked-components: []
---

# Disable markdownlint rules MD031, MD032, MD040 across the framework

## Context

PR #2 (first PR exercising the CI workflows) surfaced **546 markdownlint errors** across 70+ skill files. Three rules accounted for the bulk:

- **MD031** — Fenced code blocks should be surrounded by blank lines
- **MD032** — Lists should be surrounded by blank lines
- **MD040** — Fenced code blocks should have a language specified

The framework's writing style across the 32 skills relies on tight rhythm — code fences immediately under bullet points, lists wedged between heading and code, and bare ``` fences for ASCII diagrams / state machines where no language is appropriate.

## Decision

Disable MD031, MD032, and MD040 in `.markdownlint.jsonc`. Keep the rest of `default: true`. Re-evaluate quarterly per `java-framework-metrics`.

## Rationale

- **MD040 is wrong for our content.** ASCII diagrams and state machines have no language to claim. The rule's "fix" — adding a fake language like `text` — adds noise without value.
- **MD031 and MD032 are stylistically defensible but the cost is real.** Auto-fixing 546 violations in a single PR would touch every skill, swamp the diff, and bury the small substantive changes that were the point of PR #2.
- The cost of these rules is high (large mechanical reformat for marginal readability gain). The cost of *not* having them is low (markdown still renders correctly on GitHub and in Obsidian).
- We can revisit if/when our reader signals (the human-review ritual) say the unpadded fences/lists hurt comprehension. So far they haven't.

## Alternatives considered

- **Mass auto-fix in PR #2.** Rejected — would 10x the diff and obscure the real fixes (broken URLs, vault delta workflow).
- **Disable in a separate "config cleanup" PR.** Rejected — would mean PR #2 ships red and the CI surface stays broken on `main` for an extra cycle. Faster to make the call now.
- **Keep MD031/MD032; only disable MD040.** Tempting — those rules are reasonable in principle — but the 200+ violations still block the PR. Better to disable in one go and revisit deliberately later.
- **Add a TODO to re-enable later** with a tracked issue: yes, will open that.

## Consequences

- Markdown style is now lighter than it was. Subtle readability variance possible.
- The framework's "consistency over cleverness" message takes a small hit. Mitigated by the other gates (Spotless, ArchUnit, validator, JaCoCo, PIT) that are not relaxed.
- We carry a known liability: at scale, unpadded fences/lists can hurt skim. Acceptance: at our skill count today (32), this is fine; if the count grows past ~50, re-evaluate.
- Quarterly review: in `.vault/_meta/quarterly-retrospective-YYYY-QN.md` confirm whether the relaxation is still the right call.

## References

- `.markdownlint.jsonc` — the config that encodes this decision
- markdownlint rules: https://github.com/DavidAnson/markdownlint/blob/main/doc/Rules.md
- `java-framework-metrics/SKILL.md` §5 — quarterly review cadence
- CI run https://github.com/Shashank2577/java-microservices-framework/actions/runs/26597478412 — the 546 errors that triggered this

## Status notes

- 2026-05-29: adopted in PR #2 alongside the URL fixes.
- Open follow-up: GH issue to track quarterly re-evaluation.
