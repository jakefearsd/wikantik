---
auto-generated: false
type: article
status: active
cluster: databases
date: '2026-04-26'
title: Read Replicas and Replication
tags:
- replication
- databases
- high-availability
- scalability
summary: Database replication topologies (sync, async, cascading), RYOW consistency
  trade-offs, lag monitoring in PostgreSQL, failover mechanics, and operational risks.
related:
- CloudDatabases
- DatabaseBackupStrategies
- DatabaseConnectionSecurity
- TwoPhaseCommitProtocol
canonical_id: 01KQ0P44V6YY71F6E8GE82698Y
---

Database replication is the process of synchronizing data across multiple nodes to achieve high availability, read scalability, and geographic distribution.

## Replication Models

1. **Synchronous:** Primary waits for all replicas to acknowledge before confirming the commit.
   - **Pro:** Zero data loss on failover.
   - **Con:** Latency equals the slowest replica; primary hangs if a replica fails.
2. **Asynchronous:** Primary commits immediately and replicates in the background.
   - **Pro:** Low latency, primary availability decoupled from replicas.
   - **Con:** **Replication Lag**; data loss risk if primary fails before sync.
3. **Semi-Synchronous:** Primary waits for at least $N$ replicas to acknowledge. (A common compromise in MySQL/Postgres).

## Topologies

- **Single-Primary (Master-Slave):** All writes go to one node; reads are distributed. The industry standard.
- **Multi-Primary (Master-Master):** Writes accepted on any node. Requires complex conflict resolution (e.g., LWW or CRDTs).
- **Cascading Replication:** Primary replicates to a "relay" replica, which then feeds other replicas. Reduces CPU load on the primary.

## Monitoring Replication Lag

Replication lag is the time delta between a write on the primary and its appearance on the replica.

### PostgreSQL Monitoring
```sql
-- Run on Primary to see replica lag in bytes and time
SELECT
    application_name,
    client_addr,
    state,
    (pg_current_wal_lsn() - replay_lsn) AS lag_bytes,
    EXTRACT(second FROM (now() - reply_time)) AS lag_seconds
FROM pg_stat_replication;
```

## Consistency Challenges

### Read-Your-Own-Writes (RYOW)
A user updates their profile (Write to Primary) and immediately refreshes (Read from Replica). If lag is $500\text{ms}$, they see their old profile.

**Solution Patterns:**
- **Primary-Pinning:** After a write, pin that user's session to the Primary for $N$ seconds (where $N > \text{Max Lag}$).
- **Version Tracking:** Include the last known LSN (Log Sequence Number) in the user's session. The replica rejects the read if its own LSN is lower than the user's.
- **Synchronous Replication:** For the specific transaction, force sync to at least one replica.

## Failover Mechanics

When the primary fails, the system must **Promote** a replica.
1. **Detection:** Health checks (Consul/Zookeeper) confirm primary failure.
2. **Fencing:** Ensure the old primary is truly dead (STONITH) to prevent split-brain.
3. **Promotion:** Pick the replica with the most advanced LSN.
4. **Reconfiguration:** Update DNS/Load Balancer to point to the new primary.

## Operational Risks
- **Long-Running Queries:** A heavy `SELECT` on a replica can block the replication applier, causing lag to spike.
- **Network Asymmetry:** Replicas in different regions must use asynchronous replication to avoid unusable write latency.
- **Schema Migrations:** Large `ALTER TABLE` operations can saturate the replication stream. Use tools like `gh-ost` or `pt-online-schema-change`.
