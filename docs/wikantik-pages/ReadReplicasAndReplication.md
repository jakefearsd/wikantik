---
canonical_id: 01KQ0P44V6YY71F6E8GE82698Y
title: Read Replicas and Replication
type: article
cluster: databases
status: active
date: '2026-04-26'
summary: How database replication works — async vs. sync, lag handling, the patterns
  for scaling reads with replicas, and the trade-offs of each replication topology.
tags:
- replication
- read-replicas
- databases
- scaling
related:
- CloudDatabases
- DatabaseBackupStrategies
- DatabaseConnectionSecurity
- TwoPhaseCommitProtocol
---
# Read Replicas and Replication

Replication: maintaining copies of database data across multiple servers. The reasons: availability (failover), read scaling (replicas serve queries), geographic proximity (replicas closer to users), backup (cold replica as snapshot source).

This page covers how replication works and the patterns for using it.

## Replication models

### Synchronous (sync)

Primary commits only after replicas have applied the change.

Pros: zero data loss on primary failure.
Cons: every commit waits for replicas; latency increases; replica failure stops primary.

For critical financial data, sometimes worth it. For most applications, async is the right tradeoff.

### Asynchronous (async)

Primary commits independently; replicates to followers afterward.

Pros: fast commits; primary unaffected by replica health.
Cons: replication lag; potential data loss if primary fails before replication.

Default for most cloud-managed databases.

### Semi-synchronous

Hybrid: at least one replica must acknowledge before commit; others async.

Pros: reduces data loss risk vs. async; faster than full sync.
Cons: more complex; one replica's slowness affects primary.

### Logical vs. physical

Physical: byte-for-byte replication. Replica is identical to primary.
Logical: replicates statements or row changes. Allows different schemas, different versions, selective tables.

Cloud-managed databases usually offer both.

## Use cases

### Read scaling

Application reads can go to replicas; writes only to primary. Spreads read load across many machines.

```python
def get_user(id):
    return read_replica.fetch("SELECT * FROM users WHERE id = ?", id)

def update_user(id, data):
    return primary.execute("UPDATE users SET ... WHERE id = ?", id)
```

For read-heavy workloads, this is the standard scaling path.

### Geographic distribution

Replicas in different regions. Users connect to nearest. Lower latency.

Cross-region replicas are async (latency forces this). Reads return slightly stale data.

### Failover

If primary fails, promote a replica. Application reconnects to new primary.

Some systems do this automatically (RDS Multi-AZ; managed). Some require manual promotion.

### Reporting / analytics

Run heavy analytical queries on replicas. Doesn't affect primary's performance.

### Backup source

Snapshot from a replica to avoid impacting primary.

## Replication lag

The big async caveat. Replicas are behind primary by some amount of time:

- Network latency
- Replica throughput (can it apply changes as fast as primary produces them?)
- Long transactions on primary delaying replication

Typical lag: milliseconds to seconds. Spikes during heavy writes.

### Read-after-write consistency

User updates their profile; immediately reads it back. If reading from replica, might see old version.

Patterns:
- Read writes from primary briefly after write
- Sticky read for specific user (their reads go to primary for short period)
- Accept eventual consistency (UI doesn't show stale data)

### Lag monitoring

Critical metric. Alarm on excess lag:
- Application sees stale data
- Replica falling behind unrecoverably
- Eventually replica becomes useless

## Topologies

### Primary-replica (single primary)

One primary; multiple replicas. Writes to primary; reads from replicas.

Most common topology. Simplest to reason about.

### Multi-primary (multi-master)

Multiple primaries; writes accepted at any. Conflict resolution required.

Complex; rarely the right choice. Examples: Galera, MySQL Group Replication.

### Cascading replication

Primary → replica → replica's replica.

Reduces load on primary (only one direct replica). Increases lag for downstream replicas.

### Cross-region

Primary in region A; replica in region B. Async due to latency.

For DR; for geographic users.

### Logical replication for partial copies

Replicate only specific tables to specific replicas. Different schemas. Multi-source replication.

For specialized analytical replicas, audit replicas, etc.

## Cloud-managed replication

### RDS / Aurora

- Multi-AZ: synchronous replica in another AZ; automatic failover
- Read replicas: async; up to 15 (Aurora) or 5 (RDS)
- Cross-region read replicas: async; for DR

Aurora separates storage from compute; replicas share storage; very fast replica creation.

### Cloud SQL / Cloud Spanner

GCP equivalents.

### Managed replication is dramatically easier than self-managed

The complexity is real. Cloud-managed handles configuration, monitoring, failover.

## Patterns

### Connection routing

Application logic to route reads vs. writes:

```python
class DatabasePool:
    def read(self, query):
        return self.replicas[hash % len(replicas)].execute(query)

    def write(self, query):
        return self.primary.execute(query)
```

Or use a proxy that does this (PgBouncer, ProxySQL, MaxScale).

### Sticky reads

After a write, route reads from the same user/session to primary briefly:

```python
session.set_sticky_to_primary_for(seconds=5)
# All reads in this session go to primary
```

Avoids the user seeing stale data right after their own write.

### Lag-aware queries

For long-running analytical queries, accept stale data. For user-facing reads of just-written data, route to primary.

### Failover detection

If primary stops responding, automatic promotion of a replica. Cloud-managed handles this.

For self-managed: tools like Patroni (PostgreSQL), Orchestrator (MySQL).

## Common failure patterns

- **Reading from replica with high lag.** Stale data; user confusion.
- **Write to replica.** Some systems silently route to primary; some fail.
- **No lag monitoring.** Replicas fall behind unnoticed.
- **Single replica.** No redundancy if it fails.
- **Cross-region without DR plan.** Replica exists; nothing knows when to failover.
- **Heavy queries on primary that should be on replica.** Primary unnecessarily loaded.

## Further Reading

- [CloudDatabases](CloudDatabases) — Replication features
- [DatabaseBackupStrategies](DatabaseBackupStrategies) — Backups + replication
- [DatabaseConnectionSecurity](DatabaseConnectionSecurity) — Security across replicas
- [TwoPhaseCommitProtocol](TwoPhaseCommitProtocol) — Distributed transaction protocol
