---
canonical_id: 01KQ0P44T76SSF84NC0RMXA6MJ
title: Object Storage Patterns
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: How to use object storage (S3, GCS, Azure Blob) effectively — the patterns
  that scale, the cost optimizations, and the architectural cases where object storage
  is the right answer.
tags:
- object-storage
- s3
- gcs
- distributed-systems
- cloud
related:
- CloudStorageOptions
- FileUploadPatterns
- BatchProcessingPatterns
- CdnArchitecture
---
# Object Storage Patterns

Object storage (S3, GCS, Azure Blob) is the universal cloud storage primitive. Cheap; durable; infinitely scalable; HTTP-accessible. Modern cloud architectures rely heavily on it.

This page covers patterns for using object storage effectively.

## The basics

Object storage characteristics:
- Files identified by key (path-like string)
- Each file is fully replaced or deleted; no in-place edit
- Highly durable: 99.999999999% (11 nines) for S3 Standard
- Massively scalable: petabytes; no per-bucket limits
- HTTP-accessible: GET, PUT, DELETE
- Eventually consistent in older versions; strongly consistent in modern S3

The model differs from filesystems (no directories really), block storage (fully replaced not edited), databases (key-value, not query).

## What object storage is good for

### Large files

Videos, images, documents, backups. Files where size matters.

### Static assets

CSS, JS, images for web apps. Combined with CDN, fastest possible serving.

### Data lake / lakehouse

Parquet files, JSON, CSV. Queried by Athena, BigQuery, Spark.

### Backups

Database backups, log archives, snapshots. Cheap long-term storage.

### Object archive

Compliance retention, legal hold. Retention rules and lifecycle to cold storage.

### Event-driven workflows

S3 upload triggers Lambda; ETL pipeline begins.

## What object storage is not

### Database

No queries, no transactions, no joins. Don't use as a key-value DB; the access pattern doesn't fit (eventual consistency historically; per-request cost).

### Filesystem

No real directories; "directory" is just a prefix. No POSIX semantics; no in-place writes. Don't mount as a filesystem for application access.

### Streaming media (raw)

You can store videos in S3, but for streaming you typically want a CDN or specialized streaming infrastructure.

## Cost optimization

Storage classes change cost dramatically. See [CloudStorageOptions](CloudStorageOptions) for the full breakdown.

### Lifecycle rules

Move objects through storage classes based on age:

```
After 30 days → Standard-IA
After 90 days → Glacier Flexible Retrieval
After 1 year → Glacier Deep Archive
After 7 years → Delete
```

Lifecycle rules are free; the savings are real.

### Compression

Compress before storing. Gzip text; specialized compression for binary.

### Deduplication

Don't store the same object twice. Hash content; use hash as key.

### Inventory and analyze

S3 Inventory; S3 Storage Lens. Tools to see what's actually stored, where, what's hot vs. cold.

### Multi-part upload abort

Incomplete multi-part uploads accumulate. Lifecycle rule to abort after 7 days.

## Specific patterns

### Pre-signed URLs

For uploads from clients (mobile, web), sign a URL; client uploads directly to S3.

```
Client → app server: "I want to upload"
App server → S3: generate signed URL
App server → client: signed URL
Client → S3: PUT to signed URL with file
```

App server doesn't proxy the file. See [FileUploadPatterns](FileUploadPatterns).

### Versioning

Bucket versioning preserves old versions. Useful for:
- Accidental delete protection
- Object Lock for compliance
- Audit trail

Costs extra storage; old versions stay around.

### Replication

Cross-region replication for DR. Cross-account for security/compliance.

Asynchronous; some lag. For critical data, both regions accessible.

### Object Lock

WORM (Write Once, Read Many). Compliance feature. Object can't be deleted or modified for retention period.

### Event notifications

S3 event → Lambda, SQS, or EventBridge. The event-driven pattern.

```
S3 PUT object → Lambda triggered → Process the new file
```

For thumbnail generation, indexing, validation, etc.

### Server-side encryption

SSE-S3 (S3-managed keys) or SSE-KMS (your KMS keys). Encrypt at rest. Default for many compliance regimes.

### Object metadata

Each object can have user-defined metadata. Useful for tagging, lifecycle, application context.

## Performance considerations

### Hot keys / partition

S3 partitions buckets internally. Sequential keys (timestamps, auto-incrementing IDs) hot-spot.

For high-throughput, randomize the prefix:

```
Bad: 2026-04-26-001, 2026-04-26-002, ...
Good: a1f3-2026-04-26-001, b8e2-2026-04-26-002, ...
```

S3's internal partitioning was improved but the pattern still helps.

### Multi-part upload

For large files (>100 MB), multi-part upload is faster and more resilient. The client uploads parts in parallel; failed parts retry.

### Range requests

GET part of an object via Range header. Useful for partial reads of large files.

### Transfer acceleration

S3 Transfer Acceleration uses CloudFront edges for faster uploads from far-away clients. Costs more; useful for global apps.

### CloudFront in front

For frequent reads of public objects, CloudFront caches at edge. Massively reduces S3 cost and latency.

## Specific architectural patterns

### Static website hosting

S3 + CloudFront serves static sites. Fast; cheap; scales infinitely.

### Data lake

Raw, processed, curated layers in S3. Athena/BigQuery for queries.

### Backup destination

Primary databases dump to S3. Encrypted; lifecycle to Glacier; cross-region replication.

### Build artifact storage

CI produces JARs, container images (via ECR which uses S3); stored versioned in S3.

### Log archive

Application logs ship to S3 for long-term retention. SIEM ingests for active analysis.

## Common failure patterns

- **Public bucket exposing data.** Periodic problem; use S3 Block Public Access.
- **No lifecycle.** Storage cost grows; Standard tier for cold data.
- **Sequential keys at scale.** Performance ceiling.
- **No encryption.** Compliance gap.
- **Forgotten multi-part uploads.** Storage cost from incomplete uploads.
- **Treating S3 as filesystem.** POSIX expectations don't apply.

## Further Reading

- [CloudStorageOptions](CloudStorageOptions) — Storage class details
- [FileUploadPatterns](FileUploadPatterns) — Upload via S3
- [BatchProcessingPatterns](BatchProcessingPatterns) — Often reads from S3
- [CdnArchitecture](CdnArchitecture) — S3 + CDN pattern
