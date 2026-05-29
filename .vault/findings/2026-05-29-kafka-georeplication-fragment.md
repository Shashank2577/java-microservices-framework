---
type: finding
date: 2026-05-29
tags: [#finding, #documentation, #ci]
discovered-by: [[claude]]
related-components: []
status: active
---

# kafka.apache.org `#georeplication` fragment no longer resolves; renamed to `#georeplication-overview`

## Symptom / Observation

Lychee on PR #2 reported:

```
* [ERROR] <https://kafka.apache.org/documentation/#georeplication> | Cannot find fragment
```

The page exists; the anchor changed.

## Why it's surprising

Page anchors on long single-page docs rot silently — the page returns 200, but the linked section is invisible to readers. Lychee's `--include-fragments` flag is what made this visible; without it, we'd have shipped a "broken jump" link indefinitely.

## Evidence

CI run https://github.com/Shashank2577/java-microservices-framework/actions/runs/26597478412
Job: Markdown link check (lychee)

## Implication

- Keep `--include-fragments` in the lychee config — it's the only way to catch this class of rot.
- Long Apache project doc pages are particularly prone to anchor shifts; check periodically.

## Workaround / mitigation

- Updated to `#georeplication-overview` (the current anchor) in `java-ops-dr-runbooks/SKILL.md`.

## See also

- `.github/workflows/validate-framework.yml` — lychee config preserving `--include-fragments`
