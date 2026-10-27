# Skills Manifest — KnowledgeFolder/java

This folder holds **two distinct categories of skills** that share the same `.claude/skills/` directory because they cross-reference each other via relative paths. The split is enforced by **naming convention**, not subdirectory structure.

If you need proposal-generation or BRD/component validators, see the separate folder:
`/Users/shashanksaxena/Documents/ASG/Code/KnowledgeFolder/ProjectProposals/.claude/skills/`

---

## 🎯 Project-Specific Skills — ASG Edge+ Domain (`asg-*` prefix)

These encode product-specific business logic and domain rules for the ASG Edge+ lease accounting system. Use them when implementing ASG-specific behavior.

| Skill | Domain | Use for |
|---|---|---|
| `asg-accounting-asc842` | ASC 842 (US GAAP) | Lease classification, ROU asset, lease liability, journal entries, modification accounting |
| `asg-accounting-ifrs16` | IFRS 16 (international) | Measurement primitives shared with ASC 842, framework/trigger taxonomy |
| `asg-contract-domain` | Contract aggregate | Aggregate boundary rules, event emission (ContractModified, ContractTerminated, etc.), outbox pattern |
| `asg-decimal-precision` | Money math | Decimal scale, rounding, currency handling invariants |
| `asg-hub-and-spoke-financial` | Financial pattern | Hub-and-spoke architecture for financial transaction processing |
| `asg-react-frontend` | Frontend | React conventions specific to this product |

---

## 🔧 General Implementation Skills (`java-*`, `aws-*`, `lib/`)

Reusable across projects. Use them when implementing any Java/Spring/AWS service.

**Java application skills (30):**
`java-api-design`, `java-api-errors`, `java-api-gateway`, `java-architecture`, `java-async-sync-jobs`, `java-caching`, `java-code-quality`, `java-config`, `java-container-deploy`, `java-data-governance`, `java-dx`, `java-git-workflow`, `java-iac-terraform`, `java-integration-notifications`, `java-integration-storage`, `java-integration-webhooks`, `java-messaging`, `java-migrations`, `java-multi-tenancy`, `java-observability`, `java-ops-dr-runbooks`, `java-patterns-database`, `java-patterns-gof`, `java-patterns-microservices`, `java-principles`, `java-security`, `java-sre`, `java-stack`, `java-testing`

**AWS skills (2):**
- `aws-cognito` — User-Pool-per-tenant identity model per ADR-016
- `aws-platform` — Broader AWS service integration patterns

**Library skills (`lib/`):**
External skill collections (e.g., jabrena reference packages for data-oriented programming and Spring Boot core).

---

## Why the Two Categories Share This Folder

The `asg-*` skills cross-reference the `java-*` skills via relative paths (e.g., `asg-accounting-asc842` cites `java-multi-tenancy`, `java-testing`, `java-patterns-database`). Moving the `asg-*` skills out would break 50+ in-skill references.

If you need a fully separate skill bucket for project-specific work that does **not** depend on Java implementation details (such as proposal generators and validators), use the **ProjectProposals** folder instead — those skills operate on `.md`/`.docx`/`.xlsx` artifacts, not Java code.

---

## Decision Guide

| You want to... | Use a skill from... |
|---|---|
| Implement ASC 842 / IFRS 16 calculation code | `asg-accounting-asc842`, `asg-accounting-ifrs16` |
| Apply Java multi-tenancy, security, observability patterns | `java-*` skills here |
| Set up AWS Cognito, ECS, etc. | `aws-*` skills here |
| Generate a proposal document (FRD, NFRD, WBD, architecture) | `ProjectProposals/.claude/skills/` (generators) |
| Validate that BRDs ↔ components ↔ FRD/NFRD agree | `ProjectProposals/.claude/skills/` (validators) |
