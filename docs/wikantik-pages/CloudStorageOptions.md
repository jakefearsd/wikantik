---
canonical_id: 01KQ0P44NGM0PV4D7CTA1XVXDW
title: Cloud Storage Options
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: Object storage (S3) vs. block storage (EBS) vs. file storage (EFS) — what
  each is for, the storage classes that change cost dramatically, and the patterns
  that actually save money.
tags:
- cloud-storage
- s3
- ebs
- efs
- storage-classes
related:
- AwsFundamentals
- CloudDatabases
- CdnArchitecture
- FileUploadPatterns
hubs:
- CloudPlatformsHub
---
# Cloud Storage Options

AWS (and other cloud platforms) offer multiple storage types, each with different characteristics and costs. Picking the right type for the right data is one of the larger cost levers.

This page covers the major options and the patterns that work.

## The three categories

### Object storage (S3, GCS, Azure Blob)

- Files identified by key (URL-like path)
- Each file fully replaced or deleted (no in-place edits)
- Durable: 99.999999999% (11 nines) for S3 Standard
- Scales to any size; no limits in practice
- Accessed via HTTP API

Use for:
- User uploads
- Backups
- Static assets
- Build artifacts
- Data lakes

S3 is the universal default. If unsure, start with S3.

### Block storage (EBS for EC2, persistent disks for GCE)

- Looks like a regular disk to the OS
- Read/write any byte
- Attached to a single instance (typically)
- Faster than object storage; more expensive per GB

Use for:
- Boot volumes for EC2
- Databases that need real disk I/O
- Application working data

### File storage (EFS, FSx, Azure Files)

- POSIX file system semantics
- Network-mounted; accessible from many instances
- Slower than EBS; faster than object storage
- More expensive than block storage

Use for:
- Shared filesystems across instances
- Legacy applications expecting POSIX
- Lift-and-shift workloads

## S3 storage classes

The biggest cost lever in S3:

| Class | Use case | Cost (rough) |
|-------|----------|--------------|
| Standard | Hot data | $0.023/GB-month |
| Intelligent-Tiering | Unknown access patterns | Auto-moves; small monitoring fee |
| Standard-IA (Infrequent Access) | Warm; <1 access/month | $0.0125/GB-month |
| One Zone-IA | Same; one AZ only | $0.01/GB-month |
| Glacier Instant Retrieval | Archive; rare access; instant | $0.004/GB-month |
| Glacier Flexible Retrieval | Archive; minutes-hours retrieval | $0.0036/GB-month |
| Glacier Deep Archive | Long-term archive; 12-hour retrieval | $0.00099/GB-month |

The cost difference is 23x between Standard and Deep Archive. For data that's truly cold, the savings are real.

### Lifecycle rules

Automate the transition:

```yaml
- After 30 days → Standard-IA
- After 90 days → Glacier
- After 1 year → Glacier Deep Archive
- After 7 years → Delete
```

Lifecycle rules are free. Set them up; they save real money on long-tail data.

### Storage class trade-offs

- **Retrieval cost**: IA and Glacier classes charge for retrieval. If you actually access cold data often, the savings evaporate.
- **Minimum duration**: IA classes have minimum 30-day storage; Glacier has 90 days. Storing for less time costs full duration.
- **First-byte latency**: Glacier classes have minutes-to-hours retrieval. Match the class to access patterns.

## S3 patterns

### Versioning

Protect against accidental deletes/overwrites. Slightly higher cost (multiple versions stored).

### Encryption

Always encrypt. SSE-S3 (AWS-managed keys) or SSE-KMS (your KMS keys). Enable at bucket level.

### Static website hosting

S3 + CloudFront for static sites. Cheap, fast, scales automatically.

### Pre-signed URLs

Generate time-limited URLs for direct upload/download without going through your application. See [FileUploadPatterns](FileUploadPatterns).

### Cross-region replication

For disaster recovery. Asynchronous replication to another region. Useful for compliance and DR.

## EBS patterns

### Volume types

- **gp3**: general purpose SSD; default choice
- **gp2**: older general purpose (gp3 is usually better)
- **io2**: provisioned IOPS for high-performance databases
- **st1**: throughput-optimized HDD; for sequential workloads
- **sc1**: cold HDD; rarely used

For most workloads, gp3 is right.

### Snapshots

Point-in-time copies stored in S3. Use for:
- Backup
- Creating new volumes from existing data
- AMI creation

Snapshots are incremental — only changed blocks are stored. Cost is roughly proportional to actual data, not volume size.

## EFS patterns

EFS is expensive. Use only when you actually need shared POSIX semantics. For most "share files across instances" needs, S3 with code that fetches/uploads is cheaper.

The use case where EFS earns its cost: legacy applications you can't refactor that expect a shared filesystem.

## Cost optimization

### Lifecycle to colder classes

Most data follows a power-law: heavily-accessed at first, rarely accessed later. Lifecycle rules capture this naturally.

### Delete what you don't need

Old logs, old backups, old build artifacts. Set retention; delete what's past retention.

### Compression

Pay for less data. Gzip text files; Parquet/ORC for analytical data.

### Multi-part uploads with abort

Incomplete multipart uploads accumulate; configure lifecycle to abort after N days.

### Inventory and analyze

S3 Inventory provides reports of what's stored. S3 Storage Lens provides usage and cost analytics. Use them.

## Common failure patterns

- **Standard storage class for everything.** Lifecycle rules save real money.
- **No encryption.** Compliance and security risk.
- **Incomplete multipart uploads accumulating.** Configure lifecycle.
- **EFS for everything "because it's a filesystem."** S3 + code is usually cheaper.
- **Cross-region replication for low-value data.** Doubles storage cost.
- **EBS volumes much larger than needed.** Pay per GB; right-size.

## Further Reading

- [AwsFundamentals](AwsFundamentals) — AWS context
- [CloudDatabases](CloudDatabases) — Database storage adjacent
- [CdnArchitecture](CdnArchitecture) — S3 + CloudFront patterns
- [FileUploadPatterns](FileUploadPatterns) — Direct-to-S3 uploads
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index
