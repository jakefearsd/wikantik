---
canonical_id: 01KQ0P44PBAABJX1RZ5ZPNPEQH
title: Database Backup Strategies
type: article
cluster: databases
status: active
date: '2026-04-26'
summary: How to back up databases that you can actually restore — point-in-time recovery,
  cross-region copies, automated testing of restores, and the practices that prevent
  the worst case (backup that doesn't restore).
tags:
- database-backup
- recovery
- pitr
- databases
related:
- ReadReplicasAndReplication
- DatabaseConnectionSecurity
- CloudDisasterRecovery
- CloudStorageOptions
---
# Database Backup Strategies

Database backups exist to recover from data loss: hardware failure, accidental deletion, malicious action, application bugs that corrupt data. The goal isn't "we have backups" — it's "we can restore."

The difference matters. Many organizations have backups they've never restored. They're unverified. The first restore attempt during a real incident is the worst time to discover problems.

This page covers the practices that produce restorable backups.

## Backup types

### Full backup

Complete copy of the database. Largest; longest to take; longest to restore.

### Incremental backup

Changes since last backup (full or incremental). Smaller; faster; chains together for restore.

### Differential backup

Changes since last full backup. Larger than incremental but simpler restore.

### Continuous archiving / WAL shipping

PostgreSQL: write-ahead log files shipped continuously. Enables point-in-time recovery to any moment.

For most production systems, continuous archiving + periodic full backups is the standard.

## Recovery objectives

### RPO (Recovery Point Objective)

How much data are you willing to lose? With daily backups, up to 24 hours. With continuous archiving, seconds.

### RTO (Recovery Time Objective)

How long can recovery take? Tied to backup type and size.

Match RPO/RTO to business needs. Tighter requirements cost more.

## Cloud-managed databases

For RDS, Aurora, Cloud SQL, etc., backups are largely automatic:

### Automated backups

- Daily snapshots
- Continuous WAL backup
- Configurable retention (1-35 days typically)
- Point-in-time recovery within retention window

### Manual snapshots

In addition to automated. For long-term retention; before major changes.

For most cloud databases, automated + manual snapshots covers most needs.

## Self-managed databases

For self-hosted databases, you implement backup yourself.

### PostgreSQL

`pg_basebackup` for full backups; WAL archiving for continuous.

Tools: pgBackRest, Barman, WAL-E/WAL-G. Production-grade backup tools handle compression, encryption, retention, parallel restore.

### MySQL

`mysqldump` for logical backups; Percona XtraBackup for hot backups.

### MongoDB

`mongodump` for logical; filesystem snapshots for hot.

## What to back up

### Data

The actual database contents.

### Configuration

Database configuration, user accounts, roles, schemas. The "rebuild from scratch" requires this too.

### Application code

Without app code, database alone doesn't help.

### Infrastructure

VPC, security groups, IAM. Terraform usually handles this.

## Storage location

### Same region

Fast access; vulnerable to region failure.

### Cross-region

DR-ready. Costs more (transfer + storage).

### Cross-cloud / off-cloud

Multi-cloud DR. Highest cost; protects against cloud-provider failure.

For most production systems: same-region for speed; cross-region for disaster recovery.

## Encryption

Backups should be encrypted at rest and in transit. The "tar file in S3" pattern is standard:
- Encrypt with KMS key
- Stored in S3 with SSE-KMS or SSE-S3
- Versioned bucket

If backups can be decrypted with a single key compromise, that's a security vulnerability. Manage keys carefully.

## Testing restores

The single most important practice. Backups that have never been restored are aspirational.

### Periodic restore tests

Monthly or quarterly: restore a backup; verify it works.

What to verify:
- The restore completes
- Data is intact
- Application can connect
- Recent transactions are present (or absent for a specific point-in-time test)

### Automated restore tests

Spin up a fresh database; restore latest backup; run smoke tests; tear down.

In CI/CD or scheduled. Continuous verification.

### Real disaster simulation

Annual: pretend the primary is gone. Restore in DR region; failover applications. Time it.

This finds problems automated tests miss: documentation gaps, manual steps, organizational coordination.

## Retention

### Daily backups

Keep 7-30 days. Recent enough for normal recovery.

### Weekly backups

Keep for 1-3 months. Catches issues discovered later.

### Monthly backups

Keep for 1-7 years. Compliance retention.

### Annual archives

Long-term retention as required.

Lifecycle from hot storage to cold (Glacier, etc.) saves cost as backups age. See [CloudStorageOptions](CloudStorageOptions).

## Specific scenarios

### Accidental DELETE / DROP TABLE

Point-in-time recovery to just before the bad operation.

Without PITR: restore last full backup; lose data since then.

### Compromised database

Attacker may have planted persistence. Restore to before compromise; verify.

If compromise was long ago, may need to restore very old backup and replay transactions. Or accept loss of recent data.

### Logical corruption

Application bug corrupted data. Restore to before bug; reapply known-good transactions if possible.

### Region outage

Restore in another region from cross-region backup. Re-point applications.

## Common failure patterns

### Backups never tested

Restore fails when actually needed.

### Backups in same place as data

Region failure loses both.

### Restore documentation outdated

Procedures don't match current environment.

### No alerting on backup failures

Backups silently stop; nobody notices.

### No retention policy

Storage cost grows; eventually backups are deleted to save money; the wrong ones get deleted.

### Manual backup steps

Human forgets; backups missing.

### Backup credentials stored with the data

Compromise of database = compromise of backups.

## A reasonable starter

For typical production databases:

1. Cloud-managed if possible (handles backups automatically)
2. Daily automated; PITR enabled
3. Cross-region replication for DR
4. Manual snapshots before major changes
5. Lifecycle to cold storage after 30 days
6. Monthly restore test
7. Quarterly DR drill
8. Documented restore procedure
9. Alerts on backup failures

## Further Reading

- [ReadReplicasAndReplication](ReadReplicasAndReplication) — Adjacent practice
- [DatabaseConnectionSecurity](DatabaseConnectionSecurity) — Backup security
- [CloudDisasterRecovery](CloudDisasterRecovery) — Broader DR
- [CloudStorageOptions](CloudStorageOptions) — Where backups land
