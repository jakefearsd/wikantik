---
canonical_id: 01KQ0P44ND503A9N04WAXMS2NY
title: Cloud Databases
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: Managed RDBMS vs. NoSQL services vs. self-managed — what each cloud database
  service is good at, the operational story, and the choice criteria for production
  workloads.
tags:
- cloud-databases
- rds
- dynamodb
- aurora
- managed-databases
related:
- AwsFundamentals
- CloudStorageOptions
- CloudNativeApplicationDesign
- JpaAndHibernatePatterns
hubs:
- CloudPlatformsHub
---
# Cloud Databases

Cloud platforms offer many database options: managed RDBMS, NoSQL, time-series, graph, in-memory, ledger, etc. The right choice depends on data shape, access patterns, scale, and operational needs.

This page is about the major categories and decision criteria. AWS service names dominate the examples; equivalents exist on GCP and Azure.

## Managed RDBMS

### AWS RDS / Aurora

RDS provides managed PostgreSQL, MySQL, MariaDB, Oracle, SQL Server. AWS handles backups, patching, replication, failover.

**Aurora** is AWS's reimagining of MySQL/PostgreSQL with cloud-native storage:
- 6-way replication across 3 AZs (built into storage layer)
- Read replicas with low replication lag
- Auto-scaling storage
- Compatible with standard PostgreSQL/MySQL clients

For PostgreSQL workloads on AWS, Aurora is usually the right default. RDS PostgreSQL is fine for smaller workloads or when Aurora's pricing doesn't fit.

### When managed RDBMS is right

- Relational data with complex queries
- ACID transactions matter
- Familiar SQL ecosystem
- Standard CRUD application

For most application backends, this is the default choice.

## Managed NoSQL

### DynamoDB

Key-value/document store. AWS-managed; fully serverless.

- Pay per request (or provisioned capacity)
- Single-digit ms latency at any scale
- Multi-region replication available
- Scales infinitely (within AWS limits)
- Different access patterns than SQL

DynamoDB requires designing access patterns up front. The data model encodes the queries; you can't ad-hoc query DynamoDB efficiently.

### When DynamoDB wins

- High-scale workloads with predictable access patterns
- Serverless architectures (Lambda + DynamoDB)
- Single-digit ms latency requirements at scale
- Multi-region active-active needs

### When DynamoDB loses

- Ad-hoc queries
- Complex relationships
- Aggregations and reporting
- Familiar SQL needed

DynamoDB has a learning curve. Most teams start with RDBMS; DynamoDB is for specific high-scale needs.

## Specialized databases

### ElastiCache (Redis/Memcached)

In-memory cache. Sub-ms reads. For:
- Session storage
- Application caching
- Rate limiting counters
- Real-time analytics

### DocumentDB (MongoDB-compatible)

Managed document store. MongoDB API compatibility (with caveats). For document-shaped data with JSON workflows.

### Neptune

Managed graph database. For genuinely graph-shaped problems (recommendations, fraud detection, knowledge graphs). Niche.

### Timestream

Time-series database. For metrics, IoT data, observability streams.

### Athena (S3-based queries)

Not a database; a query engine over S3 data lakes. For analytical queries over large infrequently-accessed data.

## Self-managed on cloud VMs

Running PostgreSQL, MySQL, etc. on EC2 yourself. Pros:
- Full control
- Sometimes cheaper
- No managed-service constraints

Cons:
- You manage backups, patching, replication, monitoring
- HA requires significant work
- Operations cost is real (engineer time)

For most teams, the operational savings of managed databases exceed the cost premium. Self-manage only when there's a specific reason (regulatory, cost at scale, specific feature need).

## The decision framework

```
Is the data relational with complex queries?
├── Yes → RDS / Aurora
└── No
    ├── Is access pattern predictable and high-scale?
    │   ├── Yes → DynamoDB
    │   └── No → Reconsider; might still want RDBMS
    ├── Is it cache-shaped (read-heavy, ephemeral)?
    │   └── Yes → ElastiCache
    ├── Is it time-series?
    │   └── Yes → Timestream or InfluxDB-on-EC2
    └── Is it graph-shaped?
        └── Yes → Neptune
```

For typical web apps: RDS PostgreSQL + ElastiCache Redis covers ~90% of needs.

## Operational concerns

### Backups

Managed databases handle backups automatically. Verify retention period. Test restore occasionally — backups that have never been restored are aspirational.

### High availability

Multi-AZ deployments are essential for production. The automatic failover handles AZ outages without manual intervention. Pay the extra cost.

### Monitoring

CloudWatch metrics for RDS/Aurora/DynamoDB. Performance Insights for query-level analysis on RDS/Aurora. Custom metrics where needed.

### Cost

Managed database costs include:
- Compute (instance hours)
- Storage (GB-months)
- I/O or throughput (varies by service)
- Data transfer (cross-AZ, cross-region)
- Backups (storage)

Unexpected cost spikes usually come from data transfer or storage growth.

### Connection pooling

Many cloud databases need connection pooling, especially with Lambda. RDS Proxy is the AWS solution; PgBouncer (community) is an alternative.

## Migration considerations

### From self-hosted to managed

Database Migration Service (DMS) replicates from on-prem or self-hosted to managed services. Useful for migrations with downtime constraints.

### Between managed services

Switching from RDS to Aurora is straightforward (compatible). Switching engines (Postgres ↔ MySQL) requires schema conversion. Switching paradigms (RDBMS ↔ NoSQL) is essentially a rewrite.

## Common failure patterns

- **DynamoDB without designing access patterns.** Discover later that the queries don't fit.
- **Single-AZ in production.** AZ outage = downtime.
- **No connection pooling with Lambda.** Connection-limit exhaustion.
- **Over-provisioned instances.** Pay for capacity you don't use.
- **No backup testing.** Backups exist but don't restore.
- **Ad-hoc admin work in production.** Use IaC or admin runbooks.

## Further Reading

- [AwsFundamentals](AwsFundamentals) — AWS context
- [CloudStorageOptions](CloudStorageOptions) — Object storage alternative
- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — Where databases fit
- [JpaAndHibernatePatterns](JpaAndHibernatePatterns) — Java-side data access
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index
