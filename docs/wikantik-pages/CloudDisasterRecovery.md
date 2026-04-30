---
canonical_id: 01KQ0P44NE6QPMAR3QVZXETZ8J
title: Cloud Disaster Recovery
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: How to design DR in cloud — RTO/RPO, the four DR tiers, multi-region patterns,
  and the practical trade-offs between cost and recovery time.
tags:
- disaster-recovery
- dr
- multi-region
- backup
- rto-rpo
related:
- CloudNativeApplicationDesign
- AwsFundamentals
- CloudStorageOptions
- StatusPageBestPractices
hubs:
- CloudPlatformsHub
---
# Cloud Disaster Recovery

Disaster recovery (DR) is the discipline of restoring service after major failure. In cloud, "disaster" usually means: AZ outage, region outage, account compromise, or significant data loss. The cloud reduces some types of risk and introduces others.

This page covers the framework, the tiers, and the patterns for cloud DR.

## RTO and RPO

The two key metrics:

- **RTO (Recovery Time Objective)**: how long can the service be down before recovery completes? Hours, minutes, seconds.
- **RPO (Recovery Point Objective)**: how much data loss is acceptable? Minutes of data, hours, days.

Tighter values cost more. Pick based on business needs:

| Service tier | RTO | RPO | Cost |
|--------------|-----|-----|------|
| Tier 1: critical | <5 min | Near-zero | High |
| Tier 2: important | <1 hour | <15 min | Medium |
| Tier 3: standard | <24 hours | <4 hours | Low |
| Tier 4: archive | Days | Day or more | Minimal |

Match tier to business value. Don't pay tier-1 prices for tier-3 workloads.

## The four DR tiers

### Tier 1: backup and restore

The cheapest DR. Take regular backups; restore manually after disaster.

- **RPO**: backup interval (typically hours)
- **RTO**: time to restore (hours to days)
- **Cost**: minimal (storage)

Fine for non-critical data and slow-recovery acceptable workloads.

### Tier 2: pilot light

Minimal infrastructure running in the secondary region. Data continuously replicated. Compute spun up on disaster.

- **RPO**: replication lag (seconds to minutes)
- **RTO**: time to scale compute (10-30 min)
- **Cost**: storage + minimal compute

Good middle ground for important workloads.

### Tier 3: warm standby

Full infrastructure running in secondary region but at reduced capacity. Scale up on disaster.

- **RPO**: replication lag (seconds)
- **RTO**: minutes
- **Cost**: significant (full infra, reduced size)

For workloads with tight RTO and meaningful but bounded budget.

### Tier 4: multi-region active-active

Both regions handle traffic simultaneously. Failover is just routing change.

- **RPO**: near-zero (synchronous or near-synchronous replication)
- **RTO**: seconds
- **Cost**: high (full infrastructure twice)

For tier-1 workloads where downtime cost exceeds infrastructure cost.

## Cloud-specific patterns

### Multi-AZ (the baseline)

Default for any production workload. AZ outages happen; multi-AZ deployments ride through.

- RDS Multi-AZ: synchronous replica in another AZ
- ALB across AZs
- ASG spanning AZs
- S3 by default replicates across AZs

This isn't really DR; it's basic availability. But it covers AZ-level disasters with no special effort.

### Multi-region

For region-level disasters. AWS regions fail rarely but they do (rare us-east-1 issues, region-specific natural disasters).

Approaches:
- **Cross-region replication**: S3 replication, RDS read replicas in another region, DynamoDB Global Tables
- **Route 53 health checks + failover routing**: detect failure; route traffic to alternate region
- **Active-active across regions**: handle traffic in both; failover is just removing one

### Account-level redundancy

For compromise scenarios — attacker gets cloud account credentials. Mitigations:
- Backups in a separate account (cross-account backup vaults)
- IAM minimization
- MFA on root and admin
- AWS Organizations with separation

## What goes wrong in DR plans

### Untested DR

The DR plan that has never been exercised is fictional. Test annually at minimum:
- Restore a backup
- Failover a database
- Run traffic through the secondary region

Most DR plans don't survive the first test. That's the point of testing.

### DR that depends on the primary region

You backed up to the primary region; lost the region; can't access backups. Cross-region replication for backups.

### Data inconsistency between regions

Asynchronous replication has lag. Failover during high lag means data loss. Tighter consistency costs more.

### Forgotten dependencies

The application fails over. Authentication is in the primary region. Or the database failover takes longer than the application's connection retry. Map all dependencies.

## Backup strategy

The standard recommendation: 3-2-1.

- **3** copies of important data
- **2** different storage types (e.g., S3 + Glacier)
- **1** copy off-site (cross-region or different account)

For cloud workloads:
- Primary in production region
- Cross-region replica
- Long-term archive in Glacier Deep Archive

Costs are typically dominated by long-term archive, which is cheap.

## Specific service patterns

### RDS / Aurora

- Multi-AZ for AZ availability
- Cross-region read replicas for region DR
- Backup retention up to 35 days
- Manual snapshots for compliance retention

### DynamoDB

- Single-region: built-in multi-AZ
- Multi-region: Global Tables (active-active)
- Backup: continuous (PITR up to 35 days)

### S3

- Replication: cross-region or cross-account
- Versioning: protect against accidental delete
- Object Lock: WORM compliance

### EC2

- AMIs in multiple regions
- Snapshots cross-region
- ASG patterns for fast recovery

## Common failure patterns

- **DR plan untested.** Doesn't work when needed.
- **Backups in same region as primary.** Region failure loses both.
- **No alarming on backup failures.** Backups silently stop; nobody notices.
- **DR runbook from 3 years ago.** Doesn't match current architecture.
- **Manual failover procedures.** Slow; error-prone.
- **No cost cap on DR.** Spending too much on tier-4 for tier-3 workloads.

## A starter DR plan

For a typical web application:

1. Multi-AZ deployment (baseline)
2. RDS automated backups + cross-region snapshot weekly
3. S3 cross-region replication for user uploads
4. DR runbook documented; tested quarterly
5. Monthly backup-restore test (restore from backup, verify)
6. Annual full failover drill

Costs are modest. Coverage is real. Iterate based on actual incidents.

## Further Reading

- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — Design for resilience
- [AwsFundamentals](AwsFundamentals) — AWS context
- [CloudStorageOptions](CloudStorageOptions) — Storage and replication
- [StatusPageBestPractices](StatusPageBestPractices) — Communicating during outages
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index
