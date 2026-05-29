---
id: dec-0002
type: decision
tags: [#decision, #ci, #docs]
date: 2026-05-29
authors: [[shashank]], [[claude]]
supersedes: [[0001-relax-markdownlint-md031-md032-md040]]
superseded-by: []
status: active
linked-commits: []
linked-tickets: []
linked-components: []
---

# Drop markdownlint from CI entirely

## Context

[[0001-relax-markdownlint-md031-md032-md040]] disabled three rules (MD031, MD032, MD040) on the basis that they were too strict for the framework's tight skill-writing rhythm. PR #2's second CI pass exposed the next layer: MD022 (blanks around headings), MD058 (blanks around tables), and MD049 (emphasis style) all fire heavily across CLAUDE.md and the vault templates. ~100 additional errors with the same character — typographic conventions that don't match how the framework actually writes.

The pattern is now clear: every iteration of "relax one more rule" leaves the next one firing. Markdownlint is enforcing a style this framework's prose consistently fights. Whack-a-mole.

## Decision

Remove the `markdown-style` job from `.github/workflows/validate-framework.yml`. Delete `.markdownlint.jsonc`. Do not maintain a markdownlint config.

The remaining markdown discipline comes from:

- **lychee link check** — catches broken URLs and fragments (the substantive value)
- **mheap/frontmatter-json-schema-action** — catches vault frontmatter regressions
- **tools/validate_framework.py** — catches wiki-link rot + coverage threshold drift + jabrena reference rot
- **gitleaks** — catches secrets

Spelling, blank-line discipline, fence languages, emphasis style — left to the writer's judgment and human review per `java-human-review-ritual`.

## Rationale

- **Three failed CI runs**, each fixing the previous round's complaints, each triggering the next layer. The cost of compliance was outpacing the value.
- **The framework's prose voice is opinionated and tight.** Tables wedged under section headers without blank lines, bare ``` fences for ASCII diagrams, mixed asterisk/underscore emphasis — these are stylistic choices that GitHub and Obsidian both render correctly. Markdownlint's strict view that there is one canonical markdown style is not load-bearing for our use.
- **Real readability problems get caught in human review** (`java-human-review-ritual`), not by a linter. We have the human-review channel; let it do its job.
- **CI noise hurts the framework's credibility.** A red CI on stylistic gripes trains everyone to ignore red CI, which is exactly the opposite of what we want.

## Alternatives considered

- **Continue disabling rules one at a time.** Rejected — we already did one round; the next layer fires; the pattern is "this is the wrong tool for this content."
- **Auto-fix all 600+ errors in one mechanical PR.** Rejected — would touch every skill file with no substantive change, swamp diff review, normalize "huge mechanical PRs" as acceptable, and (worst) might not stick because new content from future Claude would keep failing.
- **Replace with a custom prose linter that only checks load-bearing things.** Tempting but speculative; better to drop and add back only if a specific problem emerges.

## Consequences

- We carry a known liability: markdown style across the framework is now inconsistent. Acceptance: at our scale (~70 markdown files) and audience (Claude + a small human team), this is fine.
- Re-evaluate at the quarterly retrospective per `java-framework-metrics` §5: if a specific style problem actually hurts a reader, address that problem then.
- If a particular file becomes hard to read because of style drift, fix that file specifically. Don't bring back the linter for the org.

## References

- PR #2 — the run that demonstrated the whack-a-mole
- [[0001-relax-markdownlint-md031-md032-md040]] — superseded by this decision
- `java-framework-metrics/SKILL.md` §8 — honesty-check question: "what did we add that we shouldn't have?"

## Status notes

- 2026-05-29: adopted. `.markdownlint.jsonc` deleted, workflow job removed in same commit.
