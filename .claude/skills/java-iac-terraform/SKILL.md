---
name: java-iac-terraform
description: Use whenever provisioning, modifying, or destroying AWS infrastructure — VPCs, EKS clusters, RDS, MSK, S3, Cognito, IAM, KMS, Route 53. Covers the Terraform repo layout, S3+DynamoDB state, module conventions, a worked `rds-postgres` and `tenant` composite module, tflint/Checkov/OPA policy-as-code, terratest, GitHub Actions OIDC, Atlantis, drift detection, and secrets-in-Terraform discipline. Per ADR-017.
---

# Terraform — AWS Infrastructure as Code

Per [ADR-017], **Terraform is the sole IaC tool**. No CloudFormation, no CDK, no console-driven changes. Every AWS resource has a Terraform owner. Drift = production incident.

## 1. Repository Layout

The `infra/` directory is in the mono-repo (not a separate repo — co-located with services so a PR can change both).

```
infra/
├── README.md
├── versions.tf                       # terraform + provider pins
├── providers.tf                      # default_tags, region, aliases (e.g., us-west-2 DR)
├── backend.tf                        # S3+DynamoDB backend template
├── modules/
│   ├── network/                      # VPC, subnets, NAT, VPC endpoints
│   ├── eks-cluster/                  # EKS + node groups + IRSA OIDC provider
│   ├── eks-addons/                   # Karpenter, ALB ctrl, External-DNS, ESO, KEDA, Linkerd
│   ├── rds-postgres/                 # see §6
│   ├── msk/                          # MSK cluster + Glue Schema Registry hookup
│   ├── cognito-platform-pool/        # the platform User Pool
│   ├── cognito-tenant-pool/          # one-per-tenant pool (see [aws-cognito])
│   ├── s3-bucket/                    # SSE-KMS + Block Public Access + versioning
│   ├── ecr-repo/                     # per-service repo, immutable tags
│   ├── service-deployment/           # ALB + ACM + Route 53 + IRSA role
│   ├── kms-key/                      # KMS key with rotation
│   ├── secrets/                      # Secrets Manager + rotation lambda
│   ├── monitoring/                   # CloudWatch dashboards, AMP alarms, PagerDuty SNS
│   └── tenant/                       # composite — see §7
├── envs/
│   ├── _shared/                      # locals: account IDs, region map, tag baselines
│   ├── dev/
│   │   ├── network.tf                # one state per concern, not per env
│   │   ├── eks.tf
│   │   ├── shared-rds.tf
│   │   └── terragrunt.hcl            # if using Terragrunt; otherwise plain backend.tf
│   ├── staging/
│   ├── prod/
│   └── dr/                           # us-west-2 disaster recovery
└── tenants/
    ├── _shared/                      # baseline schema for a tenant module call
    ├── warby-parker/
    │   ├── tenant.tf                 # invokes module.tenant
    │   ├── variables.tf
    │   └── backend.tf
    └── acme-corp/
```

**Rules:**
- Modules live in `infra/modules/` only. No inline duplicated resource blocks across envs.
- Each environment is its own state file. **Never** mix prod + dev in one state.
- One state per *concern* per env (network, EKS, shared-RDS, ESO secrets, ...). Per ADR-017, monolithic state has nonlinear blast radius and plan time.
- Each tenant is its own state file. Per-tenant blast radius isolation.

## 2. Versions and Providers

```hcl
# versions.tf
terraform {
  required_version = "~> 1.7"
  required_providers {
    aws    = { source = "hashicorp/aws",    version = "~> 5.50" }
    random = { source = "hashicorp/random", version = "~> 3.6"  }
    tls    = { source = "hashicorp/tls",    version = "~> 4.0"  }
  }
}

# providers.tf
provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Tenant      = var.tenant_slug   # may be "platform" for non-tenant resources
      Environment = var.environment
      Component   = var.component
      Owner       = var.owner_team
      CostCenter  = var.cost_center
      ManagedBy   = "Terraform"
      Repo        = "asg-edge-plus"
    }
  }
}

provider "aws" {
  alias  = "dr"
  region = "us-west-2"
}
```

`default_tags` ensures the [aws-platform] §16 cost-allocation tags appear on every resource. **Never** override `ManagedBy` — it is the SOC 2 indicator that a resource came from Terraform.

## 3. Backend — S3 + DynamoDB

```hcl
# backend.tf
terraform {
  backend "s3" {
    bucket         = "asgedge-tfstate-${var.aws_account_id}"
    key            = "envs/${var.environment}/${var.concern}.tfstate"
    region         = "us-east-1"
    dynamodb_table = "asgedge-tflocks"
    encrypt        = true
    kms_key_id     = "alias/tfstate"
  }
}
```

**One-time bootstrap** (the only manual `aws` step we accept): create the state bucket + lock table per AWS account, encrypted with a KMS key whose alias is `tfstate`. After that, everything is Terraform-managed.

Bucket protection:
- Versioning **on**.
- MFA-delete **on** (root account).
- Block Public Access **on**.
- Lifecycle: noncurrent versions to Glacier after 90 days; never delete (SOC 2 evidence).

## 4. Module Conventions

Every module under `infra/modules/<name>/` MUST have:

```
<name>/
├── README.md          # purpose, inputs, outputs, example
├── variables.tf       # all inputs, typed, with descriptions, defaults where sensible
├── main.tf            # resources
├── outputs.tf         # exported outputs
├── versions.tf        # pinned providers
└── examples/
    └── basic/         # a minimal example invoking the module
        └── main.tf
```

**Rules:**
- Every variable has `type` and `description`. Required vars have no default.
- Every output has `description` and `sensitive` set where applicable.
- No `count` for "is this thing enabled?" — use `for_each` over a set/map, even for one item. (`count` makes refactors painful.)
- Use `moved` blocks for refactors. Never `terraform state mv` by hand.
- Tags are inherited from provider `default_tags` — modules do not re-emit them.

## 5. A Worked Module — `modules/rds-postgres/`

This is the canonical "modify when scaling" module. Read it to learn the style.

```hcl
# modules/rds-postgres/variables.tf
variable "name"               { type = string  description = "Logical name; appears in resource IDs." }
variable "environment"        { type = string  description = "dev/staging/prod/dr — used for env-conditional defaults." }
variable "instance_class"     { type = string  description = "e.g., db.m6g.large. Right-size in staging before prod." }
variable "allocated_storage"  { type = number  default = 100 }
variable "max_allocated_storage" { type = number default = 1000  description = "Storage autoscaling ceiling." }
variable "engine_version"     { type = string  default = "16.4" }
variable "subnet_ids"         { type = list(string) }
variable "vpc_security_group_ids" { type = list(string) }
variable "kms_key_arn"        { type = string  description = "KMS key for storage encryption." }
variable "multi_az"           { type = bool    default = null  description = "Default: true for prod, false otherwise." }
variable "backup_retention_period" { type = number default = null description = "Default: 30 for prod, 7 otherwise." }
variable "performance_insights_enabled" { type = bool default = true }
variable "parameter_group_family" { type = string default = "postgres16" }
variable "deletion_protection" { type = bool default = null  description = "Default: true for prod, false otherwise." }
```

```hcl
# modules/rds-postgres/main.tf
locals {
  is_prod         = var.environment == "prod"
  multi_az        = coalesce(var.multi_az, local.is_prod)
  backup_days     = coalesce(var.backup_retention_period, local.is_prod ? 30 : 7)
  deletion_protect = coalesce(var.deletion_protection, local.is_prod)
}

resource "aws_db_parameter_group" "this" {
  name        = "${var.name}-pg"
  family      = var.parameter_group_family
  description = "Tuned for ASG Edge Plus — autovacuum, work_mem, statement_timeout."

  parameter { name = "autovacuum_vacuum_scale_factor" value = "0.05" }
  parameter { name = "autovacuum_analyze_scale_factor" value = "0.02" }
  parameter { name = "work_mem"                       value = "32768" }   # 32 MB
  parameter { name = "statement_timeout"              value = "300000" }  # 5 min hard ceiling
  parameter { name = "log_min_duration_statement"     value = "1000" }    # slow-query log
  parameter { name = "shared_preload_libraries"       value = "pg_stat_statements,auto_explain"  apply_method = "pending-reboot" }
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-subnets"
  subnet_ids = var.subnet_ids
}

resource "aws_db_instance" "this" {
  identifier                 = var.name
  engine                     = "postgres"
  engine_version             = var.engine_version
  instance_class             = var.instance_class
  allocated_storage          = var.allocated_storage
  max_allocated_storage      = var.max_allocated_storage
  storage_type               = "gp3"
  storage_encrypted          = true
  kms_key_id                 = var.kms_key_arn
  db_subnet_group_name       = aws_db_subnet_group.this.name
  vpc_security_group_ids     = var.vpc_security_group_ids
  parameter_group_name       = aws_db_parameter_group.this.name
  multi_az                   = local.multi_az
  backup_retention_period    = local.backup_days
  backup_window              = "03:00-04:00"
  maintenance_window         = "sun:04:30-sun:05:30"
  copy_tags_to_snapshot      = true
  deletion_protection        = local.deletion_protect
  skip_final_snapshot        = !local.is_prod
  final_snapshot_identifier  = local.is_prod ? "${var.name}-final-${formatdate("YYYYMMDDhhmm", timestamp())}" : null
  performance_insights_enabled = var.performance_insights_enabled
  performance_insights_retention_period = local.is_prod ? 731 : 7
  monitoring_interval        = 60
  monitoring_role_arn        = aws_iam_role.rds_monitoring.arn
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  manage_master_user_password = true     # Secrets Manager owns + rotates
  master_user_secret_kms_key_id = var.kms_key_arn
  apply_immediately          = !local.is_prod
}

resource "aws_iam_role" "rds_monitoring" { /* AmazonRDSEnhancedMonitoringRole */ }
```

```hcl
# modules/rds-postgres/outputs.tf
output "endpoint"        { value = aws_db_instance.this.endpoint }
output "port"            { value = aws_db_instance.this.port }
output "master_secret_arn" {
  value     = aws_db_instance.this.master_user_secret[0].secret_arn
  sensitive = true
}
output "instance_id"     { value = aws_db_instance.this.id }
output "parameter_group" { value = aws_db_parameter_group.this.name }
```

**Notes:**
- `manage_master_user_password = true` — RDS owns the password, stores it in Secrets Manager, rotates it. The service consumes it via External Secrets Operator. Per [java-security] §6.
- Prod defaults flip via `local.is_prod`. Staging mirrors prod multi_az to catch failover bugs before they reach prod.
- `apply_immediately = false` in prod — schema maintenance happens in the maintenance window, never silently mid-business-hours.

## 6. A Worked Module — `modules/tenant/` (composite)

The tenant onboarding atom. Per ADR-016 + ADR-017 (GAP-031). Calls submodules; supports `terraform destroy` for clean rollback when onboarding fails.

```hcl
# modules/tenant/main.tf
locals {
  tenant_slug = var.tenant_slug
  s3_prefix   = "${var.tenant_slug}/"
}

module "kms" {
  source     = "../kms-key"
  alias_name = "alias/tenant-${local.tenant_slug}"
  description = "Per-tenant DEK wrapping key for ${local.tenant_slug}. [java-security] §12."
  enable_rotation = true
}

module "cognito_pool" {
  source              = "../cognito-tenant-pool"
  tenant_slug         = local.tenant_slug
  display_name        = var.display_name
  hosted_ui_domain    = "auth.${local.tenant_slug}.asgedge.com"
  password_policy     = var.password_policy
  mfa_configuration   = "ON"
  saml_metadata_url   = var.saml_metadata_url    # null until tenant admin uploads
  post_confirmation_lambda_arn = var.user_created_lambda_arn
}

module "rds_schema" {
  source           = "../rds-tenant-schema"
  shared_db_id     = var.shared_rds_id           # tenant-per-schema, ADR-004
  schema_name      = local.tenant_slug
  app_role_secret  = module.kms.dek_arn
}

module "s3_prefix" {
  source        = "../s3-tenant-prefix"
  bucket        = var.platform_bucket
  prefix        = local.s3_prefix
  kms_key_arn   = module.kms.arn
}

module "iam" {
  source      = "../tenant-iam-roles"
  tenant_slug = local.tenant_slug
  kms_key_arn = module.kms.arn
  s3_prefix   = local.s3_prefix
}

module "route53" {
  source      = "../tenant-dns"
  tenant_slug = local.tenant_slug
  hosted_zone = var.platform_hosted_zone
}
```

```hcl
# modules/tenant/outputs.tf
output "user_pool_id"     { value = module.cognito_pool.user_pool_id }
output "user_pool_issuer" { value = module.cognito_pool.issuer_url }
output "kms_key_arn"      { value = module.kms.arn }
output "schema_name"      { value = module.rds_schema.schema_name }
output "rollback_target"  { value = "module.tenant_${local.tenant_slug}" description = "Use with `terraform destroy -target=<this>` to roll back onboarding." }
```

**Rollback (GAP-031):**
```bash
cd infra/tenants/<failed-slug>
terraform destroy -target=module.tenant -auto-approve   # only run after AuthN-team approval
```
This is **atomic at the tenant level** — only the tenant's User Pool, schema, prefix, KMS key, and IAM are removed. No cross-tenant impact. Pre-existing data: the RDS schema drop is destructive — confirm zero objects in the schema before destroy.

## 7. Policy as Code

Three layers, all run in CI on every PR:

| Tool | Purpose | Config |
| ---- | ------- | ------ |
| `terraform fmt -check` | Style | `.terraform-version` pinned |
| `terraform validate` | Syntax | per-module |
| `tflint` | Lint + AWS rules | `.tflint.hcl` with `terraform-aws-modules` ruleset |
| `Checkov` | Security baseline | `checkov.yaml` with our skip-list + custom checks |
| `Conftest` (OPA) | Org policies | `policy/*.rego` |

### `.tflint.hcl` (excerpt)

```hcl
plugin "aws" { enabled = true  version = "0.31.0"  source = "github.com/terraform-linters/tflint-ruleset-aws" }
plugin "terraform" { enabled = true preset = "all" }

rule "terraform_required_version" { enabled = true }
rule "terraform_required_providers" { enabled = true }
rule "terraform_naming_convention" { enabled = true format = "snake_case" }
rule "terraform_unused_declarations" { enabled = true }
rule "aws_resource_missing_tags" {
  enabled = true
  tags = ["Tenant", "Environment", "Component", "Owner", "CostCenter"]
}
```

### Checkov — pinned policies we never skip

- `CKV_AWS_16` — RDS storage encrypted
- `CKV_AWS_17` — RDS public IP disabled
- `CKV_AWS_157` — RDS Multi-AZ in prod (custom: scoped via tag `Environment == prod`)
- `CKV_AWS_18` — S3 Block Public Access
- `CKV_AWS_21` — S3 versioning on
- `CKV_AWS_145` — S3 SSE-KMS
- `CKV_AWS_33` — KMS rotation enabled
- `CKV_AWS_111` — IAM no `*` on `*`
- `CKV_AWS_158` — CloudTrail multi-region

Skip list lives in `checkov.yaml` — every skip has a comment + ticket link.

### OPA — company policies (`policy/`)

```rego
# policy/rds_prod_multi_az.rego
package terraform.aws.rds

deny[msg] {
  rc := input.resource_changes[_]
  rc.type == "aws_db_instance"
  rc.change.after.tags.Environment == "prod"
  not rc.change.after.multi_az
  msg := sprintf("Prod RDS '%s' must be Multi-AZ.", [rc.change.after.identifier])
}
```

Run: `terraform show -json plan.tfplan | conftest test -p policy --combine -`.

## 8. terratest — Module Tests

Modules with non-trivial logic (`tenant`, `eks-cluster`, `rds-postgres`) ship Go tests.

```go
// modules/rds-postgres/test/rds_postgres_test.go
func TestRdsPostgresProdDefaults(t *testing.T) {
    t.Parallel()
    opts := &terraform.Options{
        TerraformDir: "../examples/basic",
        Vars: map[string]interface{}{
            "environment": "prod", "name": "test-" + random.UniqueId(),
            // ...
        },
    }
    defer terraform.Destroy(t, opts)
    terraform.InitAndApply(t, opts)

    assert.True(t, aws.IsRdsMultiAz(t, "us-east-1", opts.Vars["name"].(string)))
}
```

Runs against an **ephemeral test account** dedicated to terratest. CI tears down on failure via `defer`.

## 9. CI/CD — GitHub Actions OIDC + Atlantis

We use **both**:
- **GitHub Actions** for PR validation: `fmt`, `validate`, `tflint`, `checkov`, `conftest`, `terraform plan`. Posts plan output as PR comment. **No `apply`.** Uses OIDC to assume a read-only role.
- **Atlantis** for `apply`: runs in EKS, listens to PR comments (`atlantis apply`), uses an IRSA role per env. Prod apply requires explicit `atlantis apply` from a CODEOWNERS approver. Prod also requires a 2nd approval.

```yaml
# .github/workflows/terraform-plan.yml (excerpt)
permissions: { id-token: write, contents: read, pull-requests: write }
jobs:
  plan:
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::${{ secrets.AWS_ACCOUNT_ID }}:role/github-actions-terraform-readonly
          aws-region: us-east-1
      - uses: hashicorp/setup-terraform@v3
      - run: terraform init && terraform plan -out=plan.tfplan
      - run: terraform show -json plan.tfplan | conftest test -p policy --combine -
      - uses: actions/github-script@v7
        with:
          script: |
            // post plan to PR
```

**No long-lived secrets** — OIDC for GH Actions, IRSA for Atlantis. Per [java-security].

## 10. Drift Detection

Drift = a resource changed outside Terraform. Two defenses:

1. **Scheduled `terraform plan`** runs nightly per env against every state. Any non-empty plan posts to `#asgedge-drift` Slack + PagerDuty if it touches prod.
2. **Service Control Policies (SCPs)** at the org level block `*:Create*`, `*:Delete*`, `*:Update*` for stateful resources (RDS, MSK, KMS) **for anyone other than the Atlantis IRSA role** in prod accounts. Engineers have read-only IAM in prod ([java-dx] §8).

Drift in prod is a **SEV-3 incident** per [java-sre]. Investigate; either revert by hand and adopt back into TF, or update TF to match (only if intentional).

## 11. State Management Hazards

- **Never** edit state by hand. No `terraform state rm` without an RFC.
- Use `moved {}` blocks for refactors. They are version-controlled, reviewable, auditable.
- Use `removed {}` blocks (TF 1.7+) for removing resources without destruction (e.g., when adopting into a different state).
- **Always** snapshot state before risky operations: `aws s3 cp s3://...tfstate s3://...tfstate-backup-$(date +%s)`.
- State files contain secrets in plaintext (provider IDs, RDS master ARNs). Bucket encryption + bucket policy restricting `s3:GetObject` to Atlantis + platform-admin roles only. **Never** download state to a laptop.

## 12. Secrets in Terraform

- No literal secrets in `.tf` or `.tfvars`.
- Bootstrap secrets (initial passwords) generated by `random_password` and immediately persisted to Secrets Manager. After that, RDS `manage_master_user_password = true` takes over.
- Data sources read existing secrets by ARN, never by literal value.
- TF state has secrets — protected per §11.

## 13. Provider Version Pinning + Deliberate Upgrades

- `aws` provider pinned to minor (`~> 5.50`). Upgrade by raising the pin in a dedicated PR with the upgrade-guide diff in the PR body.
- Always run `terraform plan` against every env after a provider upgrade — provider patches sometimes change defaults.
- Terraform binary itself pinned via `.terraform-version` (`tfenv`).

## 14. The Tenant-Onboarding Pipeline

End-to-end from sales-trigger to live tenant:

1. Sales/Ops triggers a workflow in the Onboarding Console.
2. `scripts/tenant-scaffold.sh <slug>` templates `infra/tenants/<slug>/`.
3. PR opens with tenant variables.
4. CI runs `plan` against the new state.
5. CODEOWNERS (Platform team) approves.
6. Atlantis runs `apply`.
7. PostConfirmation Lambda begins emitting `UserCreated` events as tenant admins are seeded.
8. AuthenticationService discovers the new pool via DB record; routing live.
9. Tenant Admin UI is enabled for admins to upload SAML metadata.

If step 6 fails: `terraform destroy -target=module.tenant` per §7 — clean rollback.

## 15. Cross-References

- [aws-platform] — what each module provisions and why; cluster topology
- [aws-cognito] — what the `cognito-tenant-pool` module sets up
- [java-multi-tenancy] — what the per-tenant resources mean to running code
- [java-security] — secrets management, KMS, IAM roles, IRSA, no long-lived keys
- [java-container-deploy] — Helm charts that consume IRSA + ESO references
- [java-dx] — `make tf-plan ENV=...` wraps the commands in §9
- [java-sre] — drift detection feeds the SRE alerting pipeline
- [java-ops-dr-runbooks] — DR environment topology (envs/dr)
- ADR-017 (binding)
- ADR-015 (AWS scope)
- ADR-018 (EKS — provisioned by these modules)

## 16. Anti-patterns — Refuse

- Console-driven changes ("just this once"). Drift detection will catch it; **fix the TF first, then apply**.
- One monolithic state per environment. Concerns get their own state.
- `count` for enable/disable toggles. Use `for_each` over a `toset(...)`.
- Editing state by hand.
- Long-lived AWS keys in CI. OIDC + IRSA only.
- Plain-text secrets in `.tfvars`. Reference Secrets Manager.
- Module sprawl: every service inventing its own RDS module. Use `modules/rds-postgres/`; if it lacks an option, add the option.
- Skipping policy checks for "urgent" PRs. Urgency is a process problem; bypass requires a separate approval.
- `apply_immediately = true` in prod RDS without an outage window.
- `terraform destroy` on prod without an approved change ticket.
- Mixing platform and tenant resources in one state.

## 17. Reference

- [Terraform AWS Provider docs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)
- [Checkov AWS coverage](https://www.checkov.io/5.Policy%20Index/aws.html)
- [tflint-ruleset-aws](https://github.com/terraform-linters/tflint-ruleset-aws)
- [terratest patterns](https://terratest.gruntwork.io/docs/)
- [Atlantis docs](https://www.runatlantis.io/docs/)
- ADR-017 — Terraform as IaC (binding)
- ADR-015 — Cloud platform (AWS) (binding)
