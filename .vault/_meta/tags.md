# Tag Taxonomy

This page lists the canonical tags used across vault notes. Keep tags stable so search and graph queries stay useful.

## Note-type tags (one per note)

- `#decision` — engineering decision
- `#adr` — promoted to full ADR (also has a file in `docs/adrs/`)
- `#session` — daily session log
- `#person` — teammate
- `#component` — service or module
- `#ticket` — GitHub issue or Jira mirror
- `#finding` — surprising library behavior, gotcha
- `#debugging` — incident postmortem
- `#drift` — commit contradicted a documented decision
- `#brd` / `#prd` — source product doc mirror

## Domain tags (multi-applicable)

- `#architecture` `#api-design` `#security` `#multi-tenancy` `#messaging` `#caching`
- `#database` `#migrations` `#observability` `#performance` `#testing`
- `#deployment` `#operations` `#data-governance` `#compliance`
- `#dx` (developer experience) `#onboarding`

## Status tags

- `#status/active` `#status/superseded` `#status/deprecated`
- `#status/open` (drift items)  `#status/resolved` (drift items)
- `#status/draft` (decisions still being discussed)

## Special tags

- `#load-bearing` — promote candidate at quarterly distillation
- `#second-time` — finding we've hit before; pay attention
- `#blocker` — must be resolved before merge / release
