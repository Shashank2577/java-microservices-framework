---
id: drift-YYYY-MM-DD-<slug>
type: drift
tags: [#drift, #status/open]
date: YYYY-MM-DD
detected-by: [[claude]]
commit: <sha>
component: [[component-name]]
contradicts: [[dec-NNNN]]
status: open                       # open → resolved-by-update | resolved-by-revert
resolved-on: null
---

# <Component> change contradicts <decision id>

## What changed

Commit `<sha>` does X in [[component-name]].

## What's documented

[[dec-NNNN]] on YYYY-MM-DD says Y.

## Why this is drift

*(Specifically how X contradicts Y. Not just "different" — actually contradicts.)*

## Resolution required — pick one

- **A. Update the decision.** Real-world experience justifies the change. Write a new decision that supersedes [[dec-NNNN]] with new context. Set this drift's status → `resolved-by-update`.
- **B. Revert the commit.** The decision still stands; the commit went rogue. Revert and set this drift's status → `resolved-by-revert`.

## Flagged to

@<user>

## Notes
*(Add discussion / context as the resolution is decided.)*
