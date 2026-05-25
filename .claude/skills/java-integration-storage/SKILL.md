---
name: java-integration-storage
description: Use when handling file uploads, downloads, blob storage (S3/GCS/Azure Blob), signed URLs, virus scanning, large/resumable uploads, lifecycle policies, or tenant-scoped storage keys. Covers direct-to-storage uploads (never proxy bytes through the JVM), short-TTL signed URLs, tenant-prefixed keys, ClamAV/sidecar virus scanning, and GDPR-aligned deletion.
---

# Integration — Blob Storage

Blob storage is one of the easiest places to torch reliability, cost, and security at once. The rules below are non-negotiable defaults for this framework.

## 1. Don't Proxy Bytes Through the JVM

A 500 MB upload streamed through Spring `MultipartFile` is one heap allocation away from OOM, and you pay JVM CPU + egress for every byte. **Never accept large file bytes through your application.**

Use **presigned PUT/POST URLs**. The server only mints time-limited URLs and records metadata. The client uploads directly to S3.

Flow:
```
client  ──POST /v1/files/upload-intent──▶  server
                                               │  (issues presigned PUT + key, writes row INTENT_ISSUED)
client  ◀──── { url, key, fileId } ───────────┘
client  ──PUT bytes────────────────▶  S3
client  ──POST /v1/files/{id}/finalize──▶  server
                                               │  (mark UPLOADED, enqueue scan)
                                               └──▶ scanner ──▶ AVAILABLE | INFECTED
```
No business endpoint ever sees the bytes. The JVM stays cheap, predictable, and small.

## 2. Presigned URLs — Concrete

AWS SDK v2 example:
```java
@RequiredArgsConstructor
class S3SignedUrlAdapter implements StoragePort.SignedUrls {
    private final S3Presigner presigner;

    public URI presignPut(String key, String contentType, long maxBytes, Duration ttl) {
        var put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .metadata(Map.of("tenant-id", TenantContext.current().value().toString()))
            .build();
        var signed = presigner.presignPutObject(b -> b
            .signatureDuration(ttl)               // 5–15 min
            .putObjectRequest(put));
        return signed.url().toURI();
    }

    public URI presignGet(String key, Duration ttl) {
        var get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(b -> b
            .signatureDuration(ttl)               // 1–5 min
            .getObjectRequest(get)).url().toURI();
    }
}
```

| Operation | Default TTL | Hard ceiling |
| --------- | ----------- | ------------ |
| Upload PUT | 10 min | 15 min |
| Download GET | 2 min | 5 min |
| Multipart part | 15 min | 1 h |

- Constrain by `Content-Type`, `Content-Length` range (`x-amz-content-length-range` for POST policies), and `x-amz-meta-tenant-id`.
- For downloads, generate per-request signed GET URLs. **Never cache them.**

## 3. Tenant-Prefixed Keys

Key format is fixed:
```
t/<tenant_id>/<entity>/<file_id>[/<original_name>]
e.g.  t/0d8bf2.../orders/4c1a.../invoice.pdf
```
- Bucket policy denies cross-tenant access at the bucket layer as defense in depth — IAM `Condition` on `s3:prefix` matched to the caller's tenant claim.
- Never put two tenants' files under the same prefix. Ever. Even for "shared templates" — those go under `t/_shared/` with explicit read-only ACL.

## 4. File Metadata Table

```sql
CREATE TABLE files (
  id            UUID         PRIMARY KEY,
  tenant_id     UUID         NOT NULL,
  key           TEXT         NOT NULL UNIQUE,
  content_type  VARCHAR(255) NOT NULL,
  size          BIGINT       NOT NULL,
  sha256        CHAR(64),
  state         VARCHAR(32)  NOT NULL,
  uploaded_by   UUID         NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  scanned_at    TIMESTAMPTZ,
  scan_result   VARCHAR(32)
);
```
State machine:
```
INTENT_ISSUED ──▶ UPLOADED ──▶ SCANNING ──▶ AVAILABLE
                                         └─▶ INFECTED
                                         └─▶ FAILED
```
Only `AVAILABLE` files are queryable by end users. Everything else is hidden by repository-layer predicates — no `where state = ...` scattered across services.

## 5. Virus Scanning

- **ClamAV sidecar** (small/medium teams) or **AWS GuardDuty Malware Protection / Lambda trigger** (managed).
- On `finalize` → enqueue scan job → on result, update `state` + `scan_result`.
- Quarantine `INFECTED` objects by moving (S3 `CopyObject` + `DeleteObject`) to a separate `*-quarantine` bucket with no public path. Alert security; notify uploader.
- Until scan completes, the file is not downloadable by other users. Uploader-preview is OK if the product needs it — but mark the URL response with `X-Scan-Pending: true` so the UI can warn.

## 6. Resumable / Multipart Uploads

For files > 100 MB, use S3 multipart:
1. `CreateMultipartUpload` → returns `uploadId`.
2. Server issues per-part presigned URLs (5 MB – 5 GB per part).
3. Client `PUT`s each part, collects `ETag`s.
4. Server `CompleteMultipartUpload` with the part list.

Track `parts_uploaded` in a sidecar table for resume support. **Abort incomplete uploads after 24 h** via S3 lifecycle policy — orphaned parts are billed silently otherwise.

## 7. Checksums

- Client computes **SHA-256 client-side** and sends it in the upload intent.
- Server stores it. After upload:
  - Small/non-multipart: verify against the S3 `ETag` (which is MD5 for these objects — re-derive or use `x-amz-checksum-sha256`).
  - Multipart: enqueue a verify job that streams from S3 and recomputes.
- Detects in-transit corruption. Also enables **content-addressed dedup** — identical hash → reuse the existing object, just create a new `files` row pointing at the same key.

## 8. Lifecycle and Cold Tier

Commit lifecycle rules **as code** (Terraform / CDK), versioned in repo. No console clicks.

| Age | Action |
| --- | ------ |
| > 30 d, no `GET` | → S3 Standard-IA |
| > 365 d, no `GET` | → Glacier Instant Retrieval |
| > 1095 d | → Glacier Deep Archive |
| Multipart parts > 24 h old | Abort |
| Soft-deleted > N d | Hard delete |

Cost stays predictable; no 3 a.m. bill-shock postmortems.

## 9. Deletion — GDPR-Aligned

- **Soft delete**: mark `state = DELETED`, hide from queries. After project-defined retention → hard delete from S3.
- **Tenant offboarding**: dispatch a `delete-all-by-prefix` job (`t/<tenant_id>/`), verify zero remaining keys via `ListObjectsV2`, emit `tenant.purged.v1` audit event.
- **Crypto-shred** (large data, fast): encrypt with a tenant-scoped KMS key; "deletion" = destroy the key. The ciphertext stays but is unreadable. Pair with `java-data-governance` (when written) — useful when scanning petabytes of S3 to actually delete is intractable.

## 10. Signed Download URLs — Gotchas

- **Don't embed signed URLs in long-lived artefacts** — emails, persisted UI state, exported reports. Generate per-view.
- HTTP `Referer` can leak the signature to third parties; serve download pages with `Referrer-Policy: no-referrer`.
- For embedded images/thumbnails, front S3 with a CDN (CloudFront) and use **CDN-signed cookies/URLs** — short TTL, cacheable, lower S3 cost.
- Don't log the full signed URL. Log the key + signer + TTL.

## 11. Multi-Tenancy Enforcement

Every storage read/write is mediated by `StoragePort`. The implementation asserts:
```java
public URI presignGet(String key, Duration ttl) {
    var prefix = "t/" + TenantContext.current().value() + "/";
    if (!key.startsWith(prefix)) {
        meter.counter("storage_cross_tenant_attempt_total").increment();
        throw new IllegalAccessException("cross-tenant key access: " + key);
    }
    return s3.presignGet(key, ttl);
}
```
Cross-tenant signed-URL generation must always be `IllegalAccessException` + alert + audit. Even when "obviously safe" — admin tooling, support flows — funnel it through an explicit `support:impersonate` scope (see `java-multi-tenancy` §9).

## 12. Provider Abstraction

```java
public interface StoragePort {
    String allocateKey(FileEntity f);
    SignedUrls signedUrls();
    void copy(String fromKey, String toKey);
    void delete(String key);
    interface SignedUrls {
        URI presignPut(String key, String contentType, long maxBytes, Duration ttl);
        URI presignGet(String key, Duration ttl);
        MultipartHandle initiateMultipart(String key, String contentType);
    }
}
```
Adapters for S3, GCS, Azure Blob live in `infrastructure/storage/`. Tests run against **MinIO via Testcontainers** or **LocalStack**. Production code must not reference `S3Client` directly outside the adapter — `git grep "S3Client"` outside `infrastructure/storage/` fails CI.

## 13. Observability

| Metric | Labels |
| ------ | ------ |
| `storage_upload_total` | `state` (intent\|uploaded\|failed) |
| `storage_download_total` | `state` (issued\|denied) |
| `storage_virus_scan_total` | `result` (clean\|infected\|error) |
| `storage_bytes_total` | `tenant_class` (free\|pro\|enterprise) |
| `storage_scan_backlog` | gauge |

Alert on:
- `storage_scan_backlog > N` for > 10 min
- `storage_virus_scan_total{result="infected"} > 0` (page security)
- `storage_bytes_total` per tenant exceeds quota * 1.2

## 14. Anti-Patterns — Refuse

- `MultipartFile`-handled large uploads through the JVM
- Public-read buckets ("we'll fix it later")
- Signed URL TTL ≥ 1 day
- Same key prefix shared by multiple tenants
- File downloadable by others before virus scan completes
- Signed URLs persisted in DB rows, audit logs, or email bodies
- Deleting only the DB row and leaving the S3 object orphaned
- `S3Client` calls sprinkled across business code

## 15. Pre-Merge Checklist

- [ ] Uploads go direct-to-storage; no bytes through the JVM
- [ ] TTL ≤ 15 min on upload URLs, ≤ 5 min on download URLs
- [ ] Tenant prefix in every key; bucket policy enforces it
- [ ] Virus scan gates the `AVAILABLE` state
- [ ] Lifecycle policy committed as code (Terraform/CDK)
- [ ] Provider abstracted behind `StoragePort`; no direct `S3Client` outside the adapter
- [ ] Metrics + alerts wired (`storage_*`)

## 16. Reference

- `.claude/skills/lib/jabrena/124-java-secure-coding/references/124-java-secure-coding.md` — file handling, MIME sniffing, path traversal
- AWS S3 — Presigned URLs: https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html
- AWS S3 — Lifecycle configuration: https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html
- LocalStack for Testcontainers: https://java.testcontainers.org/modules/localstack/
- MinIO Testcontainer: https://java.testcontainers.org/modules/minio/
