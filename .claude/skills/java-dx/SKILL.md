---
name: java-dx
description: Use on developer onboarding, setting up a workstation, configuring the local dev loop, or troubleshooting first-day errors. The consolidated developer-experience entry point — Java 21 + SDKMAN + Docker + AWS SSO + kubectl + Terraform + LocalStack + Spring DevTools + IntelliJ + pre-commit hooks + Compose stack + dev container + the `make first-pr` walkthrough.
---

# Developer Experience — Day 1 to Done

A new engineer must be productive on day 1 — branch cut, change committed, PR open, CI green, Atlantis plan visible. This skill is the single source of truth for that loop. If anything below conflicts with another skill, this is the runbook for the *workstation*; the other skill is the runbook for the *system*.

## 1. Onboarding Checklist (in order)

Do these in order. Each step has a verification command.

| # | Step | Verify with |
| - | ---- | ----------- |
| 1 | Install [SDKMAN](https://sdkman.io) | `sdk version` |
| 2 | `sdk install java 21.0.5-tem` + `sdk default java 21.0.5-tem` | `java --version` shows `21.0.5` |
| 3 | Install Docker Desktop (≥ 4.30, with 8 GB RAM, 4 vCPU allocated) | `docker run --rm hello-world` |
| 4 | Install AWS CLI v2 | `aws --version` shows `aws-cli/2.x` |
| 5 | Install `kubectl` (matched to EKS minor — currently `1.30`) | `kubectl version --client` |
| 6 | Install `helm` 3.14+ | `helm version` |
| 7 | Install `terraform` 1.7+ (via `tfenv` recommended) | `terraform version` |
| 8 | Install `awscli-sso` and run `aws configure sso` per §8 | `aws sts get-caller-identity --profile asgedge-dev` |
| 9 | Clone the mono-repo: `git clone git@github.com:taazaa/asg-edge-plus.git` | `cd asg-edge-plus && ls platform/` |
| 10 | `make bootstrap` — installs Git hooks, downloads catalog, primes Gradle cache | exits 0 |
| 11 | `make verify` — runs ArchUnit + unit tests + Spotless against the starter-service | exits 0 |
| 12 | Start the local stack: `make compose-up` | `make compose-status` shows all green |
| 13 | Run the starter service: `make run-starter` | `curl localhost:8080/actuator/health` returns `{"status":"UP"}` |
| 14 | `make first-pr` (§9) — scaffold your first PR | PR opens against `main` |

**Time budget:** 90 minutes end-to-end on a clean Mac/Linux box. If you exceed 2 hours, you hit a problem listed in §11 — pull from there.

## 2. Local Dev Loop

The loop is **deliberately fast**. We optimize for "save → see effect" under 5 seconds.

| Phase | Command | What happens |
| ----- | ------- | ------------ |
| Up | `make compose-up` | Postgres, Kafka, LocalStack, Redis, OTel collector, Jaeger come up. ~30s cold. |
| Run | `./gradlew :services:<svc>:bootRun --args='--spring.profiles.active=local'` | Spring DevTools hot-reload enabled. Changed classes recompile in <1s. |
| Test | `./gradlew :services:<svc>:test` | Unit tests only. Use `:check` for the full gate. |
| Integration | `./gradlew :services:<svc>:integrationTest` | Testcontainers spins Postgres + Kafka per [java-testing]. ~20s first run, ~5s cached. |
| Down | `make compose-down` | Stops the stack. Volumes persist; `make compose-clean` to wipe. |

**Spring DevTools** is the default in `local` profile. Save a Java file in IntelliJ → DevTools triggers `bootRun` reload → endpoint behavior changes without a JVM restart. Disabled in `test`/`dev`/`stg`/`prod` profiles per [java-config].

**Profile rules:**
- `local` — local Postgres + Kafka + LocalStack. No real AWS.
- `test` — Testcontainers per test class. No external state.
- `dev` / `stg` / `prod` — real AWS. Never run from your laptop.

## 3. Make Targets (one ring to rule them all)

We use `make` (not `justfile`, not raw `gradle`) as the single dev entrypoint. The Makefile is short — it delegates to `gradle`, `terraform`, `kubectl`, `aws`, `docker compose`. Targets are stable; their internals can change.

```
make bootstrap           # one-time: install hooks, prime caches, login checks
make verify              # quick CI-equivalent: spotless + arch + unit tests
make build               # full gradle build
make build-image SVC=auth  # bootBuildImage for one service
make test                # unit tests
make test-integration    # Testcontainers integration tests
make run-<svc>           # bootRun a service with local profile (e.g. make run-auth)
make compose-up          # local Postgres+Kafka+LocalStack+Redis+OTel+Jaeger
make compose-down
make compose-clean       # nuke volumes
make tf-plan ENV=dev     # terraform plan against envs/dev
make tf-apply ENV=dev    # never run for prod — Atlantis owns prod apply
make tunnel-<svc> ENV=dev  # kubectl port-forward into EKS (e.g. make tunnel-auth ENV=dev)
make logs-<svc> ENV=dev    # tail CloudWatch logs for that service
make first-pr            # see §9
make doctor              # diagnostic: java/docker/aws/kubectl/terraform versions
```

Never bypass `make` for the targets it provides. If you need a new target, add it — that is how the loop stays uniform.

## 4. IDE — IntelliJ IDEA Conventions

IntelliJ IDEA is the supported IDE. VS Code is allowed but unsupported (some refactors and ArchUnit integration are weaker).

**Checked-in IDE config (in repo, do not local-override):**
- `.idea/codeStyles/Project.xml` — code style enforced by Spotless. Reformat-on-save uses this.
- `.idea/inspectionProfiles/Project.xml` — NullAway-equivalent warnings turned on.
- `.editorconfig` — covers VS Code, IntelliJ, vim.

**Required plugins:**
- **SonarLint** — linked to the team SonarCloud project.
- **GitToolBox** — inline blame, branch indicator, push reminder.
- **MapStruct Support** — generated-mapper navigation works.
- **Save Actions** — turn on "optimize imports" + "reformat code" on save.

**Banned plugins / patterns:**
- **Lombok.** The framework forbids Lombok per [java-code-quality]. Use Java 21 records, IDE-generated `equals`/`hashCode`, or MapStruct. New code with `@Data` will fail CI.
- IDE-level "auto-add unused imports" disabled — Spotless owns import order; let it.

**Import optimization:**
- Project import order: `java`, `javax`, `jakarta`, blank, all-other, blank, `com.taazaa.asgedge.*`, blank, `static *`. Spotless enforces this; IntelliJ matches it via the checked-in style.

## 5. Pre-Commit Hooks

We use [`lefthook`](https://github.com/evilmartians/lefthook). Installed by `make bootstrap`. Config in `lefthook.yml`. Hooks run on `pre-commit` and `pre-push`.

```yaml
# lefthook.yml (excerpt)
pre-commit:
  parallel: true
  commands:
    gitleaks:
      run: gitleaks protect --staged --redact
    spotless:
      glob: "*.{java,kt,kts}"
      run: ./gradlew spotlessApply && git add {staged_files}
    terraform-fmt:
      glob: "*.tf"
      run: terraform fmt -recursive {staged_files} && git add {staged_files}
    yaml-lint:
      glob: "*.{yml,yaml}"
      run: yamllint -s {staged_files}
pre-push:
  commands:
    arch-unit:
      run: ./gradlew archTest -q
    tf-plan-sanity:
      glob: "*.tf"
      run: scripts/tf-plan-sanity.sh   # short plan that fails fast on `*:Destroy` of stateful resources
```

**Bypassing the hook (`--no-verify`) requires explicit approval per [java-git-workflow] §6.** It is never the default.

## 6. Dev Container / GitHub Codespaces

For engineers without local Docker (or who want a clean environment), a `.devcontainer/devcontainer.json` is checked in.

```jsonc
{
  "name": "asg-edge-plus-dev",
  "image": "ghcr.io/taazaa/asgedge-devcontainer:2026.05",
  "features": {
    "ghcr.io/devcontainers/features/aws-cli:1": { "version": "2" },
    "ghcr.io/devcontainers/features/kubectl-helm-minikube:1": { "version": "1.30" },
    "ghcr.io/devcontainers-extra/features/terraform:1": { "version": "1.7" }
  },
  "postCreateCommand": "make bootstrap",
  "remoteEnv": {
    "SDKMAN_DIR": "/usr/local/sdkman",
    "JAVA_HOME": "/usr/local/sdkman/candidates/java/current"
  },
  "customizations": {
    "vscode": { "extensions": ["redhat.java", "vscjava.vscode-java-debug", "hashicorp.terraform"] }
  }
}
```

The devcontainer image is built by CI quarterly and pinned by date tag. **Never** use `:latest` — pin reproducibility.

## 7. Compose Stack (local backing services)

`docker-compose.yml` lives at repo root. Started by `make compose-up`. Services:

| Service | Image | Port(s) | Purpose |
| ------- | ----- | ------- | ------- |
| postgres | `postgres:16-alpine` | 5432 | Per-tenant schemas materialized at startup ([java-multi-tenancy]) |
| kafka | `confluentinc/cp-kafka:7.6.0` (KRaft mode) | 9092 | Topics auto-created via init container |
| schema-registry | `confluentinc/cp-schema-registry:7.6.0` | 8081 | Avro schema validation locally |
| localstack | `localstack/localstack:3.5` | 4566 | S3, SQS, Secrets, SSM, KMS, IAM, STS (per §7.1) |
| redis | `redis:7-alpine` | 6379 | Caches + rate limiters |
| otel-collector | `otel/opentelemetry-collector:0.100.0` | 4317, 4318 | OTLP receiver → Jaeger exporter |
| jaeger | `jaegertracing/all-in-one:1.57` | 16686 (UI) | Local trace UI |
| mailcatcher | `dockage/mailcatcher` | 1080 (UI), 1025 (SMTP) | Outbound email capture |

Open the local Jaeger UI: `http://localhost:16686`. Mailcatcher UI: `http://localhost:1080`.

### 7.1 LocalStack — what works and what doesn't

LocalStack is *not* a perfect AWS clone. We use it for what it handles cleanly and stub everything else.

| AWS service | LocalStack support | Use for local? |
| ----------- | ------------------ | -------------- |
| S3 | Full | **Yes.** Tenant-prefixed buckets seeded by init script. |
| SQS | Full | **Yes.** |
| Secrets Manager | Full | **Yes.** Seeded by `scripts/localstack-seed.sh`. |
| SSM Parameter Store | Full | **Yes.** |
| KMS | Full (envelope encryption works) | **Yes.** Per-tenant DEKs seeded. |
| IAM / STS | Partial (policies parsed but not enforced) | **Yes** for credential issuance. |
| **Cognito** | **Partial — UI flows broken, JWKS endpoint flaky** | **No.** Use the in-process JWT fixture per [aws-cognito] §11. |
| EKS | Pro-only | **No.** Use `kind` or `minikube` if k8s required locally; usually not. |
| RDS | Pro-only | **No.** Use the `postgres` Compose service directly. |
| MSK | Pro-only | **No.** Use the `kafka` Compose service directly. |

**Cognito fallback:** [aws-cognito] §11 ships a `LocalJwtIssuer` Testcontainer that produces RSA-signed Cognito-format tokens with the right claims. Integration tests use it; `bootRun --profile=local` uses it via `LocalJwtIssuerAutoConfiguration` (excluded from non-local profiles).

**Seeding:** `scripts/localstack-seed.sh` runs in the `localstack-init` container (depends_on the localstack health check). It creates a `dev-tenant-a` and `dev-tenant-b` with: S3 prefixes, KMS keys, Secrets Manager entries for DB password, SSM parameters for non-secret config. Idempotent.

## 8. AWS SSO Setup

We use AWS IAM Identity Center (SSO). **No long-lived access keys** — never. Per [java-security] and [aws-platform] §9.

```bash
aws configure sso
# SSO start URL:  https://taazaa.awsapps.com/start
# SSO Region:     us-east-1
# Choose profile name: asgedge-dev (one per env you have access to)
```

After setup you should have (subject to your role grants):
- `asgedge-dev` — Developer role in dev account
- `asgedge-stg` — ReadOnly in staging (PowerUser if on-call)
- `asgedge-prod` — ReadOnly in prod (no write — Atlantis owns it)

Refresh: `aws sso login --profile asgedge-dev`. Sessions are 8h.

**Helper alias** in your shell rc:
```bash
alias awsd='aws --profile asgedge-dev'
alias awss='aws --profile asgedge-stg'
alias awsp='aws --profile asgedge-prod'
```

Confirm: `awsd sts get-caller-identity` returns your federated identity.

## 9. First PR Walkthrough (`make first-pr`)

The single most important onboarding ritual. **Every new engineer ships a PR on day 1.** No exceptions.

`make first-pr` does:

1. Asks for your name.
2. Cuts a branch `intern/<your-name>/onboarding-<date>` per [java-git-workflow] §2.
3. Scaffolds a tiny doc change: appends "Onboarded: `<Your Name>` on `<date>`" to `CONTRIBUTORS.md`.
4. Runs pre-commit hooks (so you see them work).
5. Commits with an `Intent:` block per [java-git-workflow] §3.
6. Pushes the branch.
7. Opens a PR via `gh pr create` against `main` with a "First PR" label.
8. Prints the PR URL.

You then:
- Watch CI run.
- See the Atlantis "no infra changes — nothing to plan" comment.
- Get review from your buddy.
- Merge.

This takes ~10 minutes and exercises every CI path you'll use forever after.

## 10. Tunneling into EKS

Direct kubectl is **read-only** for engineers. For port-forwards (debug a dev pod, hit Actuator), use:

```bash
make tunnel-auth ENV=dev    # port-forwards :8080 → svc/auth-service:8080 in dev EKS
make tunnel-auth ENV=stg    # same but staging
# prod tunneling requires a justification ticket and on-call approval
```

Behind the scenes: `aws eks update-kubeconfig --region us-east-1 --name asgedge-<env> --profile asgedge-<env>` and `kubectl port-forward`. Wrapped so you don't have to remember the args.

**Never** `kubectl edit` anything in any environment. Drift detection (per [java-iac-terraform] §11) will revert it within an hour, and you will have introduced a SOC 2 finding. All k8s state is Terraform/Helm.

## 11. Troubleshooting — Top 10 Day-1 Errors

| Error | Cause | Fix |
| ----- | ----- | --- |
| `java: command not found` after fresh shell | SDKMAN init not in shell rc | `source ~/.sdkman/bin/sdkman-init.sh`, then add it to `~/.zshrc` |
| Gradle daemon OOM during build | JVM heap too small | Add `org.gradle.jvmargs=-Xmx4g` to `~/.gradle/gradle.properties` |
| `Cannot connect to Docker daemon` | Docker Desktop not running | Start Docker Desktop; verify with `docker ps` |
| `make compose-up` hangs on kafka | Insufficient Docker memory | Docker Desktop → Settings → Resources → 8 GB RAM minimum |
| `aws: error: An error occurred (ExpiredToken)` | SSO session expired (8h) | `aws sso login --profile asgedge-dev` |
| `kubectl: connection refused` | Wrong context | `aws eks update-kubeconfig --name asgedge-dev --profile asgedge-dev` |
| `bind: address already in use :8080` | Old `bootRun` still running | `lsof -i :8080`, kill, or restart your shell |
| `Testcontainers: could not find a valid Docker environment` | Docker socket path issue (M1/M2 Mac) | `export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` |
| `gitleaks` blocks commit citing AWS key | Real secret in diff, OR false positive | Real → remove + rotate the key. False → add to `.gitleaks.toml` allowlist with PR review. |
| `terraform plan` shows `Cannot find required provider` | Provider cache stale | `terraform init -upgrade` in the env directory |
| Spring Boot fails `local` profile with `Cognito JWKS unreachable` | Trying to use real Cognito locally | Set `app.auth.local-jwt-fixture.enabled=true` in `application-local.yml` per [aws-cognito] §11 |
| IntelliJ shows red errors on records but build passes | Project SDK not set to 21 | File → Project Structure → Project SDK = 21; Language Level = 21 |

If your problem is none of these: ask in `#asgedge-engineering` Slack. Then update this table.

## 12. The Verification Loop

Before declaring yourself "onboarded":

- [ ] `make doctor` is all green.
- [ ] `make verify` exits 0.
- [ ] You ran a service via `make run-starter` and hit its `/actuator/health`.
- [ ] You opened the Jaeger UI and saw a trace from your local request.
- [ ] You ran `make tf-plan ENV=dev` and the plan completed without errors.
- [ ] Your first PR is merged.

If any of those failed, you are not onboarded. Don't skip them.

## 13. Cross-References

- [java-stack] — version catalog, Gradle conventions, what's allowed on the classpath
- [java-testing] — Testcontainers patterns, integration vs unit, contract tests
- [java-git-workflow] — branch naming, `Intent:` commits, mandatory hooks
- [java-config] — profiles (`local`/`test`/`dev`/`stg`/`prod`), externalized config
- [java-iac-terraform] — Terraform conventions, `make tf-plan` internals, Atlantis flow
- [aws-platform] — AWS account structure, EKS topology, IRSA, what `make tunnel` actually does
- [aws-cognito] — local JWT fixture, why LocalStack Cognito isn't used
- [java-security] — AWS SSO rationale (no long-lived keys), secret handling

## 14. Anti-patterns — Refuse

- Long-lived AWS access keys on your laptop. **Always SSO.**
- Bypassing pre-commit hooks (`--no-verify`) without explicit approval.
- `kubectl edit` / `helm install` directly to any environment. Atlantis owns infra; CI/CD owns service deploys.
- `gradle build` from your laptop targeting a remote registry. CI builds + publishes images.
- "Works on my machine" — if it doesn't run in the devcontainer, it doesn't ship.
- Adding tools to onboarding without updating the verification command in §1.
- Running `make tf-apply ENV=prod`. Prod applies are Atlantis-only.
- Disabling Spring DevTools and forgetting to re-enable. DevTools is the loop.
- Pulling images from Docker Hub for production. ECR only ([aws-platform] §11).
- Using Lombok. Java 21 records + IDE-generated methods + MapStruct cover every case.

## 15. Reference

- [SDKMAN docs](https://sdkman.io/usage)
- [Spring Boot Buildpacks](https://docs.spring.io/spring-boot/reference/packaging/container-images/cloud-native-buildpacks.html)
- [LocalStack coverage matrix](https://docs.localstack.cloud/references/coverage/)
- [AWS SSO CLI guide](https://docs.aws.amazon.com/cli/latest/userguide/sso-configure-profile-token.html)
- [lefthook](https://github.com/evilmartians/lefthook)
- [Testcontainers Java](https://java.testcontainers.org/)
