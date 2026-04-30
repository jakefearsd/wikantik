---
canonical_id: 01KQ0P44NERXFVWJNMY869DVK9
title: Cloud Migration Strategies
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: The 6 R's of cloud migration — rehost, replatform, refactor, repurchase,
  retire, retain — and the realistic patterns that work vs. the rewrites that consistently
  fail.
tags:
- cloud-migration
- 6rs
- lift-and-shift
- modernization
related:
- CloudNativeApplicationDesign
- AwsFundamentals
- LegacyCodeModernization
- CloudDisasterRecovery
hubs:
- CloudPlatformsHub
---
# Cloud Migration Strategies

Migrating an existing system to the cloud is rarely the right framing. Successful migrations involve many decisions: which workloads, in what order, with what level of change. The "6 R's" framework (originally from AWS) provides vocabulary.

This page covers the strategies and which ones work in practice.

## The 6 R's

### Rehost ("lift and shift")

Move the workload to cloud with minimal changes. Same OS, same application, same architecture — running on cloud VMs instead of on-prem.

**When it fits**:
- Time pressure (data center decommission, contract end)
- Limited engineering resources
- Workload that won't be re-architected anyway
- Need to escape on-prem before optimization

**Trade-offs**:
- Fast migration
- No cloud-native benefits (elasticity, managed services)
- Often more expensive than on-prem at first
- Sets up future incremental modernization

Most successful "we're going to the cloud" projects start with rehost for the bulk, then incrementally improve.

### Replatform ("lift, tinker, and shift")

Rehost plus targeted improvements. Move to cloud and adopt some managed services without full re-architecture.

Common patterns:
- Self-managed PostgreSQL → RDS
- Cron jobs → CloudWatch Events + Lambda
- Self-managed cache → ElastiCache
- File system → S3 + lifecycle

**When it fits**: most migrations. Captures real value without full rewrite.

### Refactor / re-architect

Significant code changes to take advantage of cloud-native patterns. Microservices decomposition, serverless adoption, event-driven architecture.

**When it fits**:
- Specific workloads where the rewrite produces measurable value
- After rehost/replatform have stabilized the cloud presence
- For new features added after migration

**Trade-offs**:
- Long; expensive; risky
- Often delivers less value than expected
- Should not be the *first* step in a migration

### Repurchase

Replace the workload with a SaaS equivalent. Self-hosted CRM → Salesforce. Self-hosted email → Office 365.

**When it fits**: commodity workloads with mature SaaS alternatives. Rarely makes sense to re-host or refactor what you can buy.

### Retire

Some workloads have no business value. The migration discovery surfaces them. Retire instead of migrating.

**When it fits**: every migration has these. The percentage varies but is rarely zero.

### Retain

Some workloads stay where they are — at least temporarily.

**When it fits**:
- Compliance constraints
- Recently deployed; not yet ROI on migration
- Specific dependencies that don't fit cloud

A migration plan that doesn't include "retain" is unrealistic.

## A realistic migration sequence

For a typical enterprise migration:

### Phase 1: discover and decide

Catalog every workload. For each: which R? Often takes months.

Common output:
- 60% rehost (the bulk)
- 20% replatform (with targeted upgrades)
- 10% retire (dead applications)
- 5% repurchase (replace with SaaS)
- 3% refactor (specific high-value rewrites)
- 2% retain (compliance, recently-deployed)

### Phase 2: build cloud landing zone

Set up account structure, networking, IAM, observability, security baselines. Don't migrate workloads until the landing zone is ready.

### Phase 3: migrate in waves

Migrate workloads in dependency order. Start with low-risk; build operational muscle.

### Phase 4: optimize

After workloads run in cloud, find optimization wins:
- Right-sizing
- Reserved/spot instances
- Storage class moves
- Replatform pieces that need it

### Phase 5: modernize

Selective refactoring where it pays. Now you have cloud experience and a stable baseline.

The total timeline for a large migration: typically 2-5 years.

## What usually fails

### "Cloud-native rewrite from day one"

Promises modernization at the same time as migration. Misses deadlines; loses business cases. The rewrite-during-migration pattern has a poor track record.

### "Just lift and shift everything"

Captures escape from on-prem but no cloud benefits. Cost goes up; complaints follow. Need a roadmap to capture value over time.

### "We'll figure it out as we go"

No plan; no architecture; no budget discipline. Costs spiral. Workloads run worse in cloud than on-prem because nobody designed for it.

### "Just move the database"

Half-migration. Application is on-prem, database in cloud. Cross-region latency kills performance. Migrate together or design explicitly for the split.

## Common failure patterns

- **Underestimating effort.** Migrations always take longer than estimated.
- **Not budgeting for cloud cost during transition.** Running both on-prem and cloud doubles cost.
- **Skipping the landing zone.** Workloads pile into ad-hoc cloud setup.
- **Treating cloud as someone else's data center.** It is, but the patterns differ.
- **No retire phase.** Migrating dead workloads.
- **Big-bang cutover.** Multi-year incremental usually wins.

## Further Reading

- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — The destination
- [AwsFundamentals](AwsFundamentals) — Cloud target context
- [LegacyCodeModernization](LegacyCodeModernization) — Adjacent practice
- [CloudDisasterRecovery](CloudDisasterRecovery) — DR during migration
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index
