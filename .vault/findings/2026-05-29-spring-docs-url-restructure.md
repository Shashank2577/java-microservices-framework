---
type: finding
date: 2026-05-29
tags: [#finding, #documentation, #ci]
discovered-by: [[claude]]
related-components: []
status: active
---

# Spring Boot reference-docs URL pattern changed; older `docs/3.4.x/reference/html/` paths return 404

## Symptom / Observation

Lychee link check on PR #2 surfaced four broken Spring docs URLs:

- `https://docs.spring.io/spring-boot/docs/3.4.x/reference/html/io.html#io.caching`
- `https://docs.spring.io/spring-boot/docs/3.4.x/reference/html/container-images.html`
- `https://docs.spring.io/spring-boot/docs/3.4.x/reference/`

All three return 404. The skills referencing them were written assuming the older URL shape that was canonical for Spring Boot 2.x and early 3.x.

## Why it's surprising

Spring Boot 3.4 docs *exist* — they're just at a different URL pattern now. The old `/docs/<ver>/reference/html/` path is dead. New canonical shape is `/spring-boot/reference/<topic>/<file>.html` (no `docs/`, no `<ver>/`, no `html/`, and topics split per file rather than one big `io.html`).

## Evidence

```
* [404] <https://docs.spring.io/spring-boot/docs/3.4.x/reference/html/io.html#io.caching>
* [404] <https://docs.spring.io/spring-boot/docs/3.4.x/reference/html/container-images.html>
* [404] <https://docs.spring.io/spring-boot/docs/3.4.x/reference/>
```

(from CI run https://github.com/Shashank2577/java-microservices-framework/actions/runs/26597478412)

## Implication

- Any skill that links to Spring docs needs to use the new URL pattern.
- Future Spring Boot versions may shift the structure again — link-check in CI is now the safety net that catches this.
- Other Spring projects (Spring Modulith, Spring Security, etc.) likely have similar patterns; spot-check those URLs too.

## Workaround / mitigation

- Updated the 3 broken Spring URLs in the same commit as this finding.
- The lychee CI step is the durable guard — future rot gets caught at PR time.

## Open questions

- Does Spring publish a redirect mapping anywhere? (Would help when older URLs are cited from external sources.)

## See also

- CI workflow: `.github/workflows/validate-framework.yml`
- Original PR that surfaced this: #2
