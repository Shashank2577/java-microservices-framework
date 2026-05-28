---
type: debugging
date: YYYY-MM-DD
duration-min: <total minutes spent>
tags: [#debugging]
investigators: [[someone]]
affected-components: []
severity: sev-1 | sev-2 | sev-3 | sev-4
status: resolved | open
---

# <Incident or bug — one sentence>

## Symptom

*(What was observed. User-facing impact if any. Time the symptom started.)*

## Initial hypothesis

*(First guess — usually wrong; that's fine, capture it.)*

## Investigation timeline

*(Roughly chronological. Each entry: timestamp, what you tried, what you observed.)*

- HH:MM — checked X, saw Y
- HH:MM — ruled out Z because ...
- HH:MM — found the smoking gun: ...

## Root cause

*(What was actually wrong. Be precise — line of code, configuration, race condition, missing index, etc.)*

## Fix

*(What changed. Link to the commit.)*

- Commit: `<sha>`
- Migration: *(if any)*
- Config change: *(if any)*

## Lessons

*(What we know now that we didn't know before. This is what future-Claude reads.)*

## Follow-ups

- [ ] Add a runbook entry — `docs/runbooks/<name>.md`
- [ ] Add a regression test — *(link)*
- [ ] Add a finding — [[finding-...]] *(if the lesson is library-level)*
- [ ] Update a decision — [[dec-NNNN]] *(if our prior choice was wrong)*

## See also

- Related debugging: [[debugging-...]]
- Related findings: [[finding-...]]
