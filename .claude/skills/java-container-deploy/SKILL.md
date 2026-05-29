---
name: java-container-deploy
description: Use whenever building a container image, writing Kubernetes manifests, tuning the JVM for a container, or wiring liveness/readiness/startup probes. Covers Jib over Dockerfile, JVM container memory awareness (MaxRAMPercentage, GC choice), k8s probes done right, resource requests/limits, HPA/PDB, and graceful shutdown.
---

# Containers & Kubernetes

Java in containers fails in predictable ways — wrong memory, wrong probes, no graceful shutdown. This skill is the framework's deployment posture.

## 1. Image — Spring Boot Buildpacks (default) or Jib

### Default: Spring Boot buildpacks

```bash
./gradlew bootBuildImage \
  --imageName=ghcr.io/${ORG}/${SERVICE}:${VERSION} \
  -PimagePush=true
```

- Multi-stage, layered (deps / classes), CNB-compliant.
- Base image: paketobuildpacks BellSoft Liberica or Temurin.
- Reproducible. CVE-scanned by the platform.

### Alternative: Jib

```kotlin
plugins { id("com.google.cloud.tools.jib") version "3.4.4" }
jib {
    from { image = "eclipse-temurin:21-jre-alpine" }
    to   { image = "ghcr.io/${rootProject.property("org")}/${project.name}" }
    container {
        ports = listOf("8080", "8081")          // 8080 app, 8081 management
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC",
                          "-Xss512k", "-XX:+ExitOnOutOfMemoryError")
        environment = mapOf("SPRING_PROFILES_ACTIVE" to "prod")
        labels = mapOf("org.opencontainers.image.source" to "https://github.com/${ORG}/${SERVICE}")
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}
```

- No Dockerfile, no `docker daemon` required.
- Faster builds, smaller layers.
- Always specify a versioned base, never `latest`.

### What we don't do

- Hand-written `Dockerfile`. The two patterns above cover every case in this framework.
- Distroless without measuring — the lack of shell/coreutils hurts debugging more than it helps when you're chasing a crashloop.
- `alpine` JVM images for high-allocation services without measurement — musl can underperform glibc for some workloads.

## 2. JVM Container Awareness

JDK 21 detects cgroup limits correctly **only if you pass memory limits to the container**. The defaults are dangerous; pin them.

| Flag                            | Value                       | Why                                                          |
| ------------------------------- | --------------------------- | ------------------------------------------------------------ |
| `-XX:MaxRAMPercentage`          | **75.0**                    | Heap up to 75% of container memory limit. Leaves room for metaspace, threads, native, off-heap caches. |
| `-XX:InitialRAMPercentage`      | 50.0                        | Start with 50% so growth is observable, not a cliff.         |
| `-XX:+UseG1GC`                  | (default 21)                | Good for typical service workloads.                          |
| `-XX:+ExitOnOutOfMemoryError`   | enabled                     | OOM → exit → k8s restarts → recovery. Don't try to keep limping. |
| `-XX:NativeMemoryTracking=summary` | enabled in prod          | Diagnose native leaks via `jcmd`.                            |
| `-Xss512k`                      | (down from 1m default)      | Save memory; rarely a real stack-depth issue.                |
| `-XX:+HeapDumpOnOutOfMemoryError` | enabled                   | Dump to `/tmp/heapdump.hprof` — mount a volume to capture.   |

GC choice:
- **G1** for throughput-balanced services (default).
- **ZGC** (`-XX:+UseZGC`) for sub-10ms pause sensitive services (large heaps, low-latency reads).
- **ParallelGC** for batch / throughput-only.

Validate with `jcmd <pid> VM.flags` inside the container.

## 3. Graceful Shutdown

```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 30s
```

- On SIGTERM: stop accepting new HTTP, drain in-flight up to 30s, then exit.
- k8s `terminationGracePeriodSeconds: 60` (≥ shutdown timeout + buffer).
- Kafka consumers: ack already-processed messages, then close the container. Spring Kafka handles this if you don't fight it.
- Long-running scheduled jobs: check a shutdown signal (`@PreDestroy`) and break out cleanly.

## 4. Probes — Three, Not One

| Probe       | Endpoint                          | Meaning                                                                 | Action on fail        |
| ----------- | --------------------------------- | ----------------------------------------------------------------------- | --------------------- |
| `startup`   | `/actuator/health/liveness`       | Process is starting (Flyway, tenant init).                              | Wait, then start liveness checks. |
| `liveness`  | `/actuator/health/liveness`       | Process is alive — JVM not deadlocked. **No downstream checks.**        | Restart pod.          |
| `readiness` | `/actuator/health/readiness`      | Service can serve traffic — DB up, Kafka producer up, tenant config loaded. | Remove from endpoints. **Do not restart.** |

```yaml
spec:
  containers:
  - name: app
    startupProbe:
      httpGet: { path: /actuator/health/liveness, port: 8081 }
      failureThreshold: 30        # 30 × 10s = 5 min max startup
      periodSeconds: 10
    livenessProbe:
      httpGet: { path: /actuator/health/liveness, port: 8081 }
      periodSeconds: 10
      failureThreshold: 3
    readinessProbe:
      httpGet: { path: /actuator/health/readiness, port: 8081 }
      periodSeconds: 5
      failureThreshold: 3
```

Spring Boot 3 wires liveness/readiness automatically. Custom indicators add to readiness only — **never** to liveness.

```java
@Component
class KafkaProducerHealth implements HealthIndicator {
    @Override public Health health() {
        return producerReady() ? Health.up().build() : Health.down().build();
    }
}
// Wire into readiness group, not liveness — kafka blip ≠ restart pod
```

## 5. Resource Requests & Limits

| Setting              | Rule                                                                          |
| -------------------- | ----------------------------------------------------------------------------- |
| `requests.memory`    | What the pod actually uses at steady state. Schedule decision.                |
| `limits.memory`      | Hard ceiling. Set `requests == limits` for prod stability (no overcommit).    |
| `requests.cpu`       | Realistic average. Used for HPA.                                              |
| `limits.cpu`         | Soft ceiling (throttle, no kill). **Don't set CPU limits** unless workload truly benefits — they hurt latency under bursty load. |

Pick numbers from load test, not "looks about right". Add 20% headroom on memory above measured working set.

## 6. HPA + PDB

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: orders-svc }
spec:
  scaleTargetRef: { kind: Deployment, name: orders-svc }
  minReplicas: 2
  maxReplicas: 20
  metrics:
  - type: Resource
    resource: { name: cpu, target: { type: Utilization, averageUtilization: 65 } }
  - type: Pods
    pods: { metric: { name: http_server_requests_seconds_count_rate }, target: { type: AverageValue, averageValue: 200 } }
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: orders-svc }
spec:
  minAvailable: 1
  selector: { matchLabels: { app: orders-svc } }
```

- **`minReplicas: 2`** for any prod service. Single pod ≠ HA.
- Scale on a business metric (req/s) in addition to CPU; CPU alone scales late.
- PDB for every Deployment so cluster maintenance doesn't take you down.

## 7. Secrets and ConfigMaps (k8s)

- Secrets: `envFrom: secretRef`. Read-only volume for cert files.
- ConfigMaps: non-secret overrides (`SPRING_*` env vars).
- `imagePullSecrets` for private registries.
- `serviceAccountName` per service, with minimum RBAC.
- `securityContext`: `runAsNonRoot: true`, `readOnlyRootFilesystem: true` (with `emptyDir` for `/tmp`), `allowPrivilegeEscalation: false`.

## 8. Image Hygiene

| Concern                | Practice                                                                       |
| ---------------------- | ------------------------------------------------------------------------------ |
| CVE scanning           | Trivy or Snyk in CI; fail build on critical, alert on high.                    |
| Image signing          | `cosign sign --key cosign.key` in CI; admission controller verifies in cluster.|
| SBOM                   | Generated at build (`syft`); attached to release.                              |
| Tag policy             | Immutable `:<version>` tags. **Never** redeploy a `latest`. Use `:v1.4.2` plus a moving `:stable` tag if you must. |
| Reproducible builds    | `creationTime=USE_CURRENT_TIMESTAMP` is fine; binary layers reproducible via buildpacks. |

## 9. Logs, Metrics, Files

- Logs to stdout. K8s collects via fluent-bit/loki agent.
- `/actuator/prometheus` exposed on management port (8081), **not** on the public 8080. ServiceMonitor for Prometheus Operator.
- No app-managed file writes outside `/tmp` (emptyDir).

## 10. Local Dev Parity

`docker-compose.yml` at repo root spins up: Postgres, Kafka, Redis, Keycloak, the schema registry. Mirrors the prod topology in shape, not scale.

Match Spring profile `local` to compose endpoints. Pre-commit + CI run against Testcontainers (separate from compose) so dev and CI converge.

## 11. Anti-patterns — Refuse

- `Dockerfile` for a Spring Boot service (use buildpacks or Jib).
- `:latest` tag in any environment.
- One replica in prod.
- No PDB.
- `liveness` probe that hits the DB (database blip → cascade restart).
- `-Xmx` fixed value ignoring container limits.
- Running as root (`USER 0`) or default uid.
- Same secret bundled into the image at build time.
- `kubectl exec` as routine ops — capture what you need via logs/metrics/traces; exec only for forensic.

## 12. Pre-Merge / Pre-Deploy Checklist

- [ ] Image built via buildpacks or Jib; tag is the version, not `latest`.
- [ ] JVM flags include `MaxRAMPercentage`, `ExitOnOutOfMemoryError`.
- [ ] Three probes wired with realistic timings.
- [ ] `requests == limits` on memory.
- [ ] `minReplicas ≥ 2` for prod.
- [ ] PDB present.
- [ ] `securityContext` non-root + read-only root FS.
- [ ] Trivy / dep scan clean (no critical CVEs).
- [ ] Graceful shutdown configured.

## 13. Service Mesh (Istio / Linkerd)

A mesh is leverage, not decoration. Adopt it when the service count and policy surface justify the sidecar tax.

### When to adopt a mesh

- ≥10 services and growing.
- Compliance demands mTLS everywhere east-west.
- Need traffic policies (canary, mirroring, failover) without baking them into each service.
- If you have <5 services, the mesh's ops cost likely outweighs benefit. Stay with `NetworkPolicy` + Spring's own retries/CB until the curve bends.

### What the mesh does for you (so the app doesn't)

| Concern               | Without mesh (app code)                              | With mesh (config)                                     |
| --------------------- | ---------------------------------------------------- | ------------------------------------------------------ |
| mTLS east-west        | mTLS in each Spring Security config + cert files     | Sidecar handles it; STRICT mode at namespace           |
| Retries               | Resilience4j per call                                | `VirtualService` `retries` (Istio)                     |
| Circuit breaking      | Resilience4j                                         | `DestinationRule` outlier detection                    |
| Timeouts              | `@TimeLimiter`                                       | `VirtualService` `timeout`                             |
| Canary / weighted     | Gateway routes only                                  | `VirtualService` weighted traffic                      |
| Mirroring             | Custom                                               | `VirtualService` mirror                                |
| Observability headers | OTel instrumentation                                 | Sidecar adds telemetry; OTel still recommended for spans |

Rule: **don't double-up.** If the mesh handles retry, remove `@Retry` from app code (it'll multiply attempts and trip outlier detection). Pick one layer per concern and document it.

### Istio vs Linkerd

- **Istio** for richest features (AuthorizationPolicy, JWT validation at sidecar, telemetry v2). Heavier.
- **Linkerd** for simplicity, lower resource cost, easier upgrades. Less feature surface (no policy-as-code at the level of Istio).
- For this framework: **Linkerd by default**, switch to Istio when you genuinely need WASM/EnvoyFilter/AuthorizationPolicy.

### Sidecar resource cost

- Envoy sidecar ~50–100m CPU, 100–200Mi memory per pod at low traffic.
- Linkerd proxy ~10–30m CPU, 30–50Mi memory.
- Account for it in pod resource requests/limits (see §5).

### mTLS posture

- Cluster-wide STRICT after mesh install.
- Exceptions only for namespaces with non-mesh workloads.
- Verify in tests by hitting a service directly via NodePort/port-forward without sidecar — must fail.

### Traffic policies — Istio VirtualService example

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata: { name: orders-svc }
spec:
  hosts: [orders-svc]
  http:
    - route:
        - destination: { host: orders-svc, subset: v1 }
          weight: 90
        - destination: { host: orders-svc, subset: v2 }
          weight: 10
      timeout: 2s
      retries:
        attempts: 2
        perTryTimeout: 1s
        retryOn: 5xx,connect-failure,refused-stream
```

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata: { name: orders-svc }
spec:
  host: orders-svc
  subsets:
    - { name: v1, labels: { version: v1 } }
    - { name: v2, labels: { version: v2 } }
  trafficPolicy:
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 10s
      baseEjectionTime: 30s
```

### What the app still owns

- Business logic (obviously).
- **Idempotency** of operations — mesh retries are only safe on idempotent endpoints.
- **Tenant context propagation** — mesh propagates trace headers, but not your `X-Tenant-Id`. Keep that in the app layer.
- **Domain timeouts** — DB query timeouts, Kafka producer timeouts. Mesh timeouts protect the call surface, not the internal work.
- **Authorization at method level** (`@PreAuthorize`) — Istio AuthorizationPolicy is coarser; use both.

### Spring Boot + mesh — concrete config tweaks

- Remove app-level mTLS (sidecar handles).
- `server.tomcat.connection-timeout`: ensure ≥ mesh timeout + small buffer; or rely entirely on mesh.
- Probes: liveness/readiness still served by app (sidecar can't fake business health).
- Resilience4j: keep for application-level concerns (DB pool exhaustion, batch backpressure). Disable retry where the mesh now handles it.

### Tracing through the mesh

- Mesh emits its own spans (Envoy stats / Linkerd telemetry). Combined with OTel from the app: complete picture.
- Span context propagation requires the app to forward `traceparent` and `b3` headers. Spring Boot's OTel starter does this by default.

### Multi-tenancy through the mesh

- Mesh sees `X-Tenant-Id` as an opaque header. Use Istio `AuthorizationPolicy` only for coarse cross-namespace boundaries, not for tenant-level access (that's app-level).
- Per-tenant rate limit at the mesh? Possible via Istio's local rate limiting, but messy; keep tenant rate limit at the gateway (`java-api-gateway`).

### Mesh updates and rollouts

- Sidecar version coupled to mesh version. Upgrading the mesh = restarting every pod (rolling).
- Test mesh upgrades in staging; never auto-update production.

### Mesh anti-patterns — Refuse

- App-level retry + mesh retry both on (multiplies attempts).
- mTLS off in selected namespaces "for testing".
- Mesh ingress used instead of a real API gateway (mesh ingress is for east-west; user-facing needs a proper gateway).
- `VirtualService` changes deployed via UI / `kubectl edit` (drift).
- Adopting mesh "to be modern" without measurement.
- Skipping app-level authorization because "mesh enforces it" (mesh is coarse).

### Mesh pre-merge checklist

- [ ] Pod has sidecar resource requests/limits set.
- [ ] App-level retry removed where mesh now handles it.
- [ ] ServiceAccount configured for mesh identity.
- [ ] mTLS STRICT verified in test.
- [ ] Idempotency on every endpoint the mesh retries.

## 14. Reference

- Spring Boot reference: [Container Images](https://docs.spring.io/spring-boot/reference/packaging/container-images/index.html)
- Jib: https://github.com/GoogleContainerTools/jib
- Paketo buildpacks: https://paketo.io/docs/
- Cloud Native JVM tuning (Sergio del Amo / Spring team) — search "Spring Boot JVM container memory".
- Istio: https://istio.io/latest/docs/
- Linkerd: https://linkerd.io/2/overview/
