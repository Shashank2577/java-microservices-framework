---
name: aws-platform
description: Use whenever touching AWS services — VPC, EKS, RDS, MSK, S3, CloudFront, Secrets Manager, KMS, ECR, IAM, Route 53, CloudWatch, AMP/Grafana, X-Ray, AWS Backup, Budgets. The omnibus AWS service-catalog skill — account structure, region strategy, EKS topology, IRSA, Helm base chart, KEDA Kafka-lag, RDS conventions, MSK topics, S3 layout, cost tags, FinOps. Per ADR-015 and ADR-018. Cognito has its own skill: [aws-cognito].
---

# AWS Platform — Service Catalog and Conventions

Per [ADR-015], AWS is the sole cloud. Per [ADR-018], EKS is the orchestration. This skill is the operational catalog: every AWS service we use, the convention we apply, and what *not* to do.

## 1. AWS Account Structure (Organizations)

We use AWS Organizations. **Per-environment account isolation** — blast radius and IAM boundaries are non-negotiable.

```
root-org (asg)
├── shared-services         # SSO, Organizations master, CloudTrail org-trail, AMP, AMG, billing
├── audit-archive           # CloudTrail + Config recordings, immutable
├── dev                     # one EKS cluster, one RDS, one MSK
├── staging                 # mirrors prod topology
├── prod                    # production
├── dr                      # warm DR in us-west-2
└── sandbox                 # disposable engineer accounts (24h TTL via cron)
```

**Future:** per-tenant accounts for top-tier customers (regulated / sensitive data). The tenant module ([java-iac-terraform] §6) is designed so cross-account migration is mechanical when the day comes.

SSO via IAM Identity Center; everyone federates. No IAM users with passwords. No long-lived access keys.

## 2. Region Strategy

| Region | Role | Why |
| ------ | ---- | --- |
| `us-east-1` | Primary | Largest service availability, Cognito region |
| `us-west-2` | DR (warm) | Cross-region snapshots, MirrorMaker 2 from MSK |
| `ca-central-1` | Future (Canadian data residency) | When the first CA tenant signs |
| `eu-central-1` | Future (GDPR data residency) | When the first EU tenant signs |

**Cross-region data:** only what DR needs (RDS snapshots, S3 replication, MSK MM2). No live cross-region traffic. Latency budget assumes single-region.

## 3. VPC and Networking

One VPC per environment per region. Non-overlapping `/16` CIDR ranges across all envs (future-proof for VPC peering / Transit Gateway).

```
prod    10.0.0.0/16
staging 10.10.0.0/16
dev     10.20.0.0/16
dr      10.30.0.0/16
```

Each VPC:
- 3 AZs minimum. Public + private + DB subnets per AZ.
- **Private subnets default** — services have no public IP.
- NAT Gateways per AZ for egress (avoid the single-AZ NAT SPOF).
- **VPC endpoints (gateway/interface)** for: S3 (gateway), ECR (interface), Secrets Manager, KMS, STS, CloudWatch Logs, SSM. Cuts NAT cost and removes "service down because NAT down" failure modes.
- Flow logs to CloudWatch (90d retention prod, 14d dev). Required by Checkov + SOC 2.

```hcl
# infra/modules/network/main.tf (sketch)
module "vpc" {
  source = "terraform-aws-modules/vpc/aws"
  version = "~> 5.13"
  cidr = var.cidr
  azs  = data.aws_availability_zones.available.names
  public_subnets = [for i, az in local.azs : cidrsubnet(var.cidr, 4, i)]
  private_subnets = [for i, az in local.azs : cidrsubnet(var.cidr, 4, i + 4)]
  database_subnets = [for i, az in local.azs : cidrsubnet(var.cidr, 4, i + 8)]
  enable_nat_gateway = true
  single_nat_gateway = !local.is_prod   # cost optimization for non-prod
  enable_vpn_gateway = false
  enable_flow_log = true
  flow_log_destination_type = "cloud-watch-logs"
}
```

## 4. EKS Cluster Topology (per ADR-018)

**One cluster per environment.** Tenancy is in-code per ADR-004; no per-tenant namespaces.

| Env | Control plane | Node groups |
| --- | ------------- | ----------- |
| dev | 1.30, single-AZ OK | 1× `services-spot` (m6i.large, max 6) |
| staging | 1.30, 3-AZ | `system` (3× t3.medium), `services-on-demand` (3× m6i.large) |
| prod | 1.30, 3-AZ | `system` (3× m6i.large, taint `dedicated=system:NoSchedule`), `services-on-demand` (3-12× m6i.large), `services-spot` (0-20× m6i.large mixed), all Karpenter-managed beyond bootstrap |
| dr | 1.30, 3-AZ scaled to 1 node | scales up on DR drill |

### Namespaces

- `platform-system` — Karpenter, AWS Load Balancer Controller, External-DNS, cert-manager, External Secrets Operator, Linkerd control plane.
- `observability` — OTel collector, AMP agent, Linkerd-viz (optional), Fluent Bit DaemonSet.
- `audit` — AuditService (receives events from everything).
- `<bounded-context>` — one per component (`auth`, `accounting`, `asc842`, `contract`, `documents`, ...).

**No per-tenant namespaces.** Tenancy is in-code. K8s namespaces are per-environment + per-component only.

### Cluster Add-Ons (managed via Terraform `modules/eks-addons`)

- AWS Load Balancer Controller
- External-DNS (Route 53)
- cert-manager (ACM via cert-manager-aws-pca-issuer or DNS-01 via Route 53)
- **Karpenter** (cluster autoscaler successor)
- External Secrets Operator (Secrets Manager + SSM Parameter Store)
- Metrics Server
- **KEDA** (Kafka-lag-driven autoscaling for spoke services — see §6)
- **Linkerd** (mTLS, golden metrics, simpler than Istio per ADR-018)
- OpenTelemetry Operator (manual SDK preferred; operator for older services)
- AWS for Fluent Bit (logs → CloudWatch)
- EBS CSI driver (PVC)
- CloudWatch Container Insights

## 5. IRSA — IAM Roles for Service Accounts

Every service has its own IAM role. No shared roles. No node IAM permissions for app workloads.

```yaml
# k8s manifest
apiVersion: v1
kind: ServiceAccount
metadata:
  name: contract-service
  namespace: contract
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<account>:role/contract-service
```

Spring picks up AWS credentials transparently via the [AWS SDK default credentials provider chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html); no code change.

```java
// Java side — DefaultCredentialsProvider walks the IRSA web identity token automatically
S3Client s3 = S3Client.builder()
    .region(Region.US_EAST_1)
    .build();
```

IRSA role policies are least-privilege — only the AWS actions and resources this service needs. Generated by the `modules/service-deployment/` Terraform module.

## 6. Helm Base Chart — `platform-service`

Every service's chart extends `infra/helm/charts/platform-service/`. The base provides Deployment, Service, ServiceAccount, IRSA, HPA/KEDA, Linkerd, OTel, ALB Ingress, External Secrets.

```yaml
# values.yaml — what a service overrides
image:
  repository: 123456789012.dkr.ecr.us-east-1.amazonaws.com/contract-service
  tag: "1.42.0"     # NEVER `latest`; immutable tags per ADR-018
serviceAccount:
  iamRoleArn: arn:aws:iam::123456789012:role/contract-service
replicaCount:
  min: 2
  max: 12
autoscaling:
  type: hpa         # or `keda` for spoke services
  cpu:
    targetAverageUtilization: 65
ingress:
  enabled: true
  hostname: api.asgedge.com
  pathPrefix: /contracts
externalSecrets:
  - name: db-credentials
    refreshInterval: 5m
    secretStoreRef: aws-secrets-manager
    data:
      - secretKey: SPRING_DATASOURCE_PASSWORD
        remoteRef:
          key: rds/shared/contract-service
          property: password
linkerd:
  inject: enabled
observability:
  otel:
    endpoint: http://otel-collector.observability.svc.cluster.local:4317
    sampleRate: 0.1   # 10% in prod, 1.0 in dev
```

```yaml
# templates/deployment.yaml (excerpt)
spec:
  template:
    metadata:
      annotations:
        linkerd.io/inject: {{ .Values.linkerd.inject }}
        config.linkerd.io/proxy-cpu-request: 100m
        config.linkerd.io/proxy-memory-request: 64Mi
    spec:
      serviceAccountName: {{ .Values.serviceAccount.name }}
      containers:
        - name: app
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          env:
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: {{ .Values.observability.otel.endpoint }}
            - name: OTEL_TRACES_SAMPLER_ARG
              value: "{{ .Values.observability.otel.sampleRate }}"
          envFrom:
            - secretRef: { name: db-credentials }
          readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 } }
          livenessProbe: { httpGet: { path: /actuator/health/liveness, port: 8080 } }
          startupProbe: { httpGet: { path: /actuator/health, port: 8080 }, failureThreshold: 30 }
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { cpu: 1000m, memory: 1Gi }
```

## 7. KEDA — Kafka-Lag Autoscaling for Spokes

Accounting spoke services scale on Kafka consumer lag. Per ADR-007 hub-and-spoke + ADR-018.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: asc842-spoke-scaler
  namespace: accounting
spec:
  scaleTargetRef:
    name: asc842-spoke
  minReplicaCount: 2
  maxReplicaCount: 20
  pollingInterval: 15
  cooldownPeriod: 120
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: b-1.asgedge-msk.amazonaws.com:9098,b-2.asgedge-msk.amazonaws.com:9098
        consumerGroup: asc842-spoke-consumer
        topic: accounting.events.v1
        lagThreshold: "500"           # scale up when each pod has >500 messages lag
        offsetResetPolicy: latest
        sasl: aws_msk_iam
        tls: enable
```

**Rule:** spoke services scale on lag, not CPU. CPU is a secondary signal. Lag is the SLI.

## 8. RDS Postgres

Per [ADR-003] + ADR-004 + ADR-014.

| Convention | Why |
| ---------- | --- |
| **Postgres 16** | Current major, supported through 2028 |
| **Multi-AZ in prod** (staging too — catch failover bugs early) | RTO target |
| **Performance Insights on** (731 days prod, 7 days dev) | Required by SRE for query-level RCA |
| `manage_master_user_password = true` | Secrets Manager owns + rotates |
| Parameter group tuned: `autovacuum_*_scale_factor`, `work_mem=32MB`, `statement_timeout=5m`, `pg_stat_statements`, `auto_explain` | See [java-iac-terraform] §5 |
| **Read replicas for reports** — `report-replica` (sync lag tolerated) | Per [java-patterns-database]; reports never hit primary |
| **No public IP**, private subnets only | Network isolation |
| **gp3 storage** | Cost + throughput |
| **Storage autoscaling on**, ceiling sized per env | Avoid 3am "disk full" pages |
| **Maintenance window** Sun 04:30-05:30 UTC | Low-traffic window |
| **Backup retention** 30d prod, 7d dev | RPO + audit |
| **Final snapshot** required in prod | Recovery insurance |
| **PITR enabled** (automated via backup retention) | RPO ≤ 5min target |
| **Deletion protection on** in prod | Anti-foot-gun |

## 9. MSK (Managed Kafka)

| Convention | Why |
| ---------- | --- |
| `kafka.m5.large` × 3 brokers minimum in prod (× 6 at scale) | Quorum + headroom |
| `kafka.t3.small` × 3 dev only | Cost — never prod |
| **IAM auth** (`sasl.aws_msk_iam`) | No password rotation; IRSA-friendly |
| **TLS in transit** mandatory | OWASP |
| **Encryption at rest** (SSE-KMS) | Compliance |
| **Glue Schema Registry** for Avro | One source of schema truth |
| **Topic naming:** `<bounded-context>.events.v<n>` (e.g., `accounting.events.v1`) | Versionable, owned |
| **Partition count:** 12 per topic baseline; sized by throughput before prod | Future-proof key partitioning |
| **Retention:** 7d default; 30d for audit topics; 90d for `outbox.dlq` | Trade-off compute vs replay |
| **MirrorMaker 2 to DR** | Cross-region replication for tier-1 topics |

Spring config:

```yaml
spring.kafka:
  bootstrap-servers: ${MSK_BOOTSTRAP}
  security.protocol: SASL_SSL
  sasl:
    mechanism: AWS_MSK_IAM
    jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
    client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
```

## 10. S3 — Bucket Layout

**One bucket per environment** (`asgedge-prod`, `asgedge-stg`, ...). **Tenant-prefixed keys.**

```
s3://asgedge-prod/
├── <tenant-slug>/
│   ├── contracts/<contract-id>/<version>/<filename>
│   ├── documents/<doc-type>/<doc-id>
│   ├── exports/<export-id>.csv
│   └── attachments/<entity>/<id>/<filename>
└── _platform/
    └── ... (rare; non-tenant assets only)
```

| Bucket setting | Value |
| -------------- | ----- |
| Block Public Access | All four toggles **on** |
| Versioning | On |
| Encryption | SSE-KMS with per-bucket KMS CMK |
| Object Lock | Compliance mode on `audit-logs` bucket (per [ADR-011]) |
| Lifecycle | Intelligent-Tiering after 30d; Glacier Deep Archive at 1y for audit prefixes |
| Logging | Server access logs → `asgedge-s3-access-logs` |
| Replication | Cross-region to `us-west-2` for prod (tier-1 prefixes) |

**Uploads:** never through the JVM per [java-integration-storage]. Always pre-signed URLs:

```java
PutObjectPresignRequest req = PutObjectPresignRequest.builder()
    .signatureDuration(Duration.ofMinutes(15))
    .putObjectRequest(b -> b.bucket("asgedge-prod").key(tenantPrefixedKey).serverSideEncryption("aws:kms"))
    .build();
URL url = s3Presigner.presignPutObject(req).url();
```

## 11. CloudFront

The React frontend is served from CloudFront (origin: S3 `asgedge-prod-web`). Per [asg-react-frontend].

- **Signed URLs** for document downloads (never link directly to S3).
- **WAF** attached: AWS Managed Rules (Common, KnownBadInputs, IP-Reputation).
- **Custom domain:** `app.asgedge.com` + per-tenant `<slug>.app.asgedge.com` (CNAME).
- **Cache policy:** static assets 1y; HTML no-cache (SPA shell mutability).

## 12. Secrets Manager + SSM Parameter Store — The Split

| Goes in | Examples |
| ------- | -------- |
| **Secrets Manager** | RDS passwords, API keys, KMS data keys, Cognito app-client secrets, third-party tokens |
| **SSM Parameter Store** | Non-secret config: feature-flag defaults, endpoint URLs, env identifiers |

**Rotation policies:**
- RDS password — RDS-managed (`manage_master_user_password = true`).
- Third-party API keys — quarterly cadence, Lambda rotation handlers.
- KMS CMKs — automatic annual rotation.

**Kubernetes access:** External Secrets Operator (ESO) syncs both into `Secret` resources. Refresh interval 5 min. **Never** `kubectl create secret` by hand — drift detection will flag it.

## 13. KMS

- **One KMS CMK per concern per env**: `rds`, `s3`, `secrets-manager`, `msk`, `tfstate`.
- **Per-tenant DEK**: created in `modules/tenant` ([java-iac-terraform] §6), wrapped by tenant CMK. Enables crypto-shred per [java-security] §12.
- Automatic annual rotation **on** for all CMKs.
- Aliases (`alias/...`) for human-readable references. Never reference KMS by key ID in TF — always alias.

## 14. ECR

- **One repo per service.** `<account>.dkr.ecr.us-east-1.amazonaws.com/<service>`.
- **Immutable tags** — once a tag is pushed it cannot be overwritten.
- **Image scanning** on push (Inspector v2).
- **Lifecycle policy:** expire untagged images after 7d; expire dev tags after 30d; keep prod tags forever.
- **Cross-region replication** of prod images to `us-west-2` for DR.
- Pull from non-EKS contexts requires `aws ecr get-login-password` — wrapped in `make` per [java-dx].

## 15. IAM

- **SSO for humans** (IAM Identity Center). 8h sessions.
- **IRSA for pods.** No node-level IAM permissions for app workloads.
- **OIDC for GH Actions** (per [java-iac-terraform] §9).
- **Service Control Policies** at org level: block `*:Create*`/`*:Delete*` on stateful resources outside Atlantis role in prod. Block region access outside our four target regions.
- **No long-lived access keys.** Ever. Period.
- **Least privilege** — every IAM policy starts at `Effect: Deny` mentally; you add what's needed.

## 16. Route 53 + ACM

- Public hosted zones: `asgedge.com`, `app.asgedge.com`, per-tenant subdomains (`auth.<slug>.asgedge.com`).
- Private hosted zone per VPC for internal service discovery (rarely used — k8s DNS is canonical).
- ACM certs (DNS validation) for everything; auto-renewed.
- cert-manager in EKS issues from ACM (PCA optional) for k8s Ingress hostnames.

## 17. Observability — Managed Services

- **CloudWatch Logs:** structured JSON (per [java-observability]). Log groups: `/asgedge/<env>/<service>`. Retention: 30d prod, 7d dev.
- **Amazon Managed Prometheus (AMP):** scrape config managed by `kube-prometheus-stack` Helm chart with remote-write to AMP. 30d retention prod.
- **Amazon Managed Grafana (AMG):** dashboards as code (`grafana-dashboards-as-code`). SSO via IAM Identity Center.
- **AWS X-Ray** via OTel collector exporter. Sampling: 10% prod, 100% dev. Trace IDs propagated via W3C traceparent.
- **CloudWatch Container Insights:** cluster-level node + pod metrics.

## 18. AWS Backup + DR

- **AWS Backup vaults** per env: RDS daily snapshots (35d retention), copies to `us-west-2` vault.
- **S3 Cross-Region Replication** for prod tier-1 prefixes (contracts/, audit/).
- **MSK MirrorMaker 2** for tier-1 topics to DR cluster.
- **Quarterly DR drill** per [java-sre] + [java-ops-dr-runbooks]: restore RDS snapshot, scale DR EKS, re-point Route 53. Measure actual RTO/RPO.

## 19. Cost Allocation Tags (mandatory)

Set via Terraform `default_tags` ([java-iac-terraform] §2). Every resource MUST have:

| Tag | Example values |
| --- | -------------- |
| `Tenant` | `warby-parker` / `platform` / `shared` |
| `Environment` | `dev` / `staging` / `prod` / `dr` |
| `Component` | `contract-service` / `eks` / `rds` |
| `Owner` | `team-platform` / `team-accounting` |
| `CostCenter` | `R&D` / `Ops` |
| `ManagedBy` | always `Terraform` |
| `Repo` | always `asg-edge-plus` |

These tags drive AWS Cost Explorer + per-tenant FinOps reporting. Untagged resources fail Checkov.

## 20. AWS Budgets + Cost Anomaly Detection

- **Budget per env** (`dev`, `staging`, `prod`) with email + SNS alerts at 80% and 100% of monthly forecast.
- **Cost Anomaly Detection** with monitor type = `DIMENSIONAL` keyed on `TAG:Component`. Alerts feed PagerDuty per [java-sre] §13 ("cost as a reliability dimension").
- Per-tenant cost dashboards via Cost Explorer with `Tenant` tag.

## 21. Cross-References

- [aws-cognito] — identity (separate skill because it's denser)
- [java-iac-terraform] — how all this gets provisioned
- [java-container-deploy] — Helm + JVM tuning + probes (the app side of EKS)
- [java-observability] — what gets emitted into CloudWatch / AMP / X-Ray
- [java-security] — IRSA + IAM rationale, KMS, secrets, encryption
- [java-multi-tenancy] — what "tenant-prefixed" means at runtime
- [java-messaging] — MSK Kafka client config, outbox, schemas
- [java-integration-storage] — S3 pre-signed URLs (never JVM uploads)
- [java-ops-dr-runbooks] — backups, restore drill mechanics
- [java-sre] — SLOs, alerts, chaos, FinOps as reliability
- ADR-015 (binding), ADR-018 (binding), ADR-017 (binding)

## 22. Anti-patterns — Refuse

- Long-lived AWS access keys. Anywhere.
- Public IPs on RDS / MSK / app workloads.
- `:latest` image tags. ECR immutable, version-pinned only.
- Single NAT Gateway in prod (cost saving that introduces an AZ SPOF for outbound).
- Sharing IAM roles across services. One service, one role.
- Per-tenant namespaces in EKS. Tenancy is in-code per ADR-004.
- Untagged resources. They will fail CI and skew FinOps.
- Console-driven changes. Per [java-iac-terraform].
- "We'll add VPC endpoints later." NAT cost grows linearly with traffic; add them up front.
- `aws s3 sync` from a laptop to a prod bucket. Pre-signed URLs only.
- Disabling MFA-delete on the state bucket.
- Mixing dev + staging + prod resources in one account.
- Reading Cognito JWKS by HTTPS during cold start without cache. (See [aws-cognito] §6.)

## 23. Reference

- [ADR-015 — Cloud Platform AWS](../../../../asg-edge-plus-kb/decisions/ADR-015-cloud-platform-aws.md)
- [ADR-018 — EKS Container Orchestration](../../../../asg-edge-plus-kb/decisions/ADR-018-eks-container-orchestration.md)
- [EKS Best Practices Guide](https://aws.github.io/aws-eks-best-practices/)
- [Karpenter docs](https://karpenter.sh/docs/)
- [KEDA Kafka scaler](https://keda.sh/docs/2.13/scalers/apache-kafka/)
- [Linkerd EKS install](https://linkerd.io/2.15/tasks/install-helm/)
- [External Secrets Operator](https://external-secrets.io/)
