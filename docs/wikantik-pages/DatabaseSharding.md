---
canonical_id: 01KQ12YDTKJP7GTEMGPMJV5FBV
title: Database Sharding
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- sharding
- partitioning
- horizontal-scaling
- consistent-hashing
summary: When sharding is the right answer, the three sharding schemes and what
  each fails at, and the migration steps that don't lose data.
related:
- DatabasePartitioning
- ConsistentHashing
- DatabaseReplication
- DatabaseDesign
- DatabaseIndexingStrategies
hubs:
- Databases Hub
---
# Database Sharding

Sharding is splitting one logical database across multiple physical databases. You do it because a single database has run out of CPU, memory, disk, or write IOPS — and replication can't help because replication scales reads, not writes.

It's also the single largest piece of operational complexity you can take on. Don't shard until you must; do it right when you do.

## Before you shard

In order, exhaust:

1. **Vertical scaling.** A single Postgres on an `r6id.32xlarge` does ~50k writes/sec on a normal workload. That's a lot. It costs $1k/mo. Buy the bigger box first.
2. **Read replicas.** If your workload is read-heavy, replicas eliminate the read pressure on the primary. CPU and IO go to the replicas.
3. **Caching.** Redis in front of expensive queries. Eliminates 80% of read pressure for typical web workloads.
4. **Index and query work.** A missing index can turn a 10ms query into a 10s query. Profile first; rewrite second; shard last.
5. **Move cold data.** Old rows you rarely query move to a separate cheap store. Hot table stays small.

Most teams that ask "should we shard?" need a query rewrite, an index, or a read replica. Genuinely needing to shard means you've actually saturated single-node write throughput, which is uncommon below tens of thousands of QPS sustained.

## The three sharding schemes

| Scheme | How it splits | Good at | Bad at |
|---|---|---|---|
| **Range-based** | Rows in `id` range [0, 1M) → shard 1; [1M, 2M) → shard 2 | Range queries are local; ordered reads | Hot shards (newest IDs always go to last shard); resharding |
| **Hash-based** | `hash(key) % N` chooses the shard | Even load distribution; simple | Range queries hit every shard; resharding (modulo N changes everything) |
| **Directory / lookup** | A separate table maps key → shard | Maximum flexibility; arbitrary remapping | Lookup table is itself a bottleneck; needs caching |

In practice, **consistent hashing** (a refinement of hash-based) is the working default for new systems. It avoids the "modulo N changes everything" problem when you add or remove shards. See [ConsistentHashing] for the mechanics.

## The shard key choice is irrevocable

Once you've sharded by `user_id`, you cannot easily reshard by `tenant_id` later. Choose carefully.

Heuristics:

- **Use the field that appears in nearly every query's WHERE clause.** That ensures most queries hit one shard.
- **Pick a high-cardinality field.** Sharding by `country_code` with 50 distinct values doesn't actually distribute load.
- **Avoid hot keys.** If 1% of users generate 50% of traffic, sharding by `user_id` still produces hot shards. You may need composite keys (user_id, time) or special handling for whales.
- **Anticipate cross-shard queries.** Some queries unavoidably span shards. Plan how those work — usually a fan-out + merge in the application layer.

If you need to query by both `user_id` and `tenant_id`, replicate one of them as a secondary index. Don't try to shard by both.

## Cross-shard joins and transactions

The big-deal trade-off:

- **Cross-shard joins** are slow and complicated. The app layer does fan-out + merge; the planner can't help.
- **Cross-shard transactions** are even harder. 2PC is fragile and slow; saga (compensating actions) requires application-level retry logic and idempotent operations.

Design schema and access patterns to avoid both. Co-locate related data on the same shard. The most common pattern: shard by tenant, so all of one tenant's data is on one shard, and tenant-spanning operations are rare and offline.

## Resharding is the hardest part

You've sharded by `user_id` across 8 shards. Traffic grows; you need 16 shards.

Naive scheme (`hash(user_id) % N`) means changing N from 8 to 16 reshuffles 50% of all rows. Every row needs to be moved, while the system stays online.

Patterns that work:

- **Consistent hashing** moves only `1/N` of keys when adding a shard.
- **Range-based with explicit ranges** lets you split a hot range without affecting others.
- **Directory-based** lets you remap any subset; the directory is updated atomically.

Process for online resharding:

1. **Provision new shards.** Empty.
2. **Dual-write** the relevant key range to both old and new shards.
3. **Backfill** historical data from old shard to new.
4. **Verify** the new shard matches old by checksum.
5. **Cut over reads** to the new shard.
6. **Remove dual-write** once you're confident.

The whole process for a multi-TB shard takes days to weeks. Don't underestimate it. Have the dual-write infrastructure built before you start.

## When the database does it for you

Some databases ship sharding as a feature, removing the application-layer work:

- **CockroachDB** — automatic range-based sharding with Raft per range. SQL surface; most apps don't need to think about shards.
- **Spanner** — Google's, similar idea, more sophisticated time semantics.
- **Vitess** — MySQL with a sharding layer. Used at YouTube, Slack, GitHub.
- **TiDB** — MySQL-compatible distributed SQL.
- **MongoDB** — built-in sharding; widely deployed but a long history of operational sharp edges.
- **DynamoDB / Cassandra** — partition key is the shard key; the database handles distribution.

These trade some complexity (yours) for some complexity (the database's). For new builds at expected-multi-shard scale, picking a database that shards natively saves enormous engineering effort versus sharding Postgres or MySQL yourself.

For existing Postgres/MySQL systems that need to shard, **Vitess for MySQL** and **Citus for Postgres** are the proven additions. Both are mature and used in production at large scale.

## Shard-specific operational concerns

**Backup and restore** become per-shard operations. Test cross-shard restore — your DR plan needs to handle "the entire cluster died" not just "one shard died."

**Schema migrations** become a per-shard operation. Rolling migrations across N shards over hours/days; expect drift between shards during migration; design migrations to be backward-compatible.

**Hot shard mitigation.** A hot key on one shard saturates that shard while others are idle. Have a plan: split the hot range, replicate the hot key to multiple shards, or move it to a separate node.

**Monitoring.** Per-shard metrics, not aggregate. The aggregate latency hides the one shard that's on fire.

**Costs.** N small shards usually cost more than one big node, often by 30–50%. The win is throughput and isolation, not cost.

## The maintenance cost

A sharded system needs:

- Per-shard backups, recovery testing, monitoring.
- A routing layer (in app or in proxy) that knows the shard map.
- Resharding tooling.
- Cross-shard analytics pipeline (usually a separate analytics warehouse).
- On-call procedures that handle "shard 7 is down" gracefully.

All of this is doable but adds up to 1–2 dedicated platform engineers per ~30 product engineers, ongoing. Budget accordingly.

## Further reading

- [DatabasePartitioning] — partitioning vs sharding (related but distinct)
- [ConsistentHashing] — the math behind smooth resharding
- [DatabaseReplication] — usually combined with sharding
- [DatabaseDesign] — schema choices that make sharding tractable
- [DatabaseIndexingStrategies] — per-shard index strategy
