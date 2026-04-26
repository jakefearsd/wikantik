---
title: Database Partitioning
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- partitioning
- postgres
- horizontal-partitioning
summary: Postgres declarative partitioning — when it's the right answer, how
  to pick the partition key, and the operational concerns (vacuum, indexes,
  attaching/detaching) that decide whether partitioning helps or hurts.
related:
- DatabaseSharding
- DatabaseDesign
- DatabaseIndexingStrategies
- PostgresqlAdvancedFeatures
hubs:
- Databases Hub
---
# Database Partitioning

Partitioning is splitting one logical table across multiple physical tables. Same database; same schema; different storage and indexing per partition. Different from sharding (which splits across machines).

In Postgres, declarative partitioning since PG10 makes this a manageable operational technique. It's the right answer for some specific patterns and the wrong answer for many.

## What partitioning actually solves

- **Time-series data that grows forever.** Drop old partitions instead of `DELETE` (which is expensive and produces bloat).
- **Very large tables (10s-100s of GB).** Index maintenance and vacuum become per-partition; scales better than one huge table.
- **Multi-tenant workloads with per-tenant access patterns.** Partition by tenant; queries hit only the relevant partition.
- **Data with natural physical locality** that maps to partitions cleanly.

What partitioning doesn't solve:

- **Single-row latency.** Hitting one partition is similar to hitting one table; no magic speed-up.
- **Scaling to multiple machines.** That's sharding; see [DatabaseSharding].
- **Bad query patterns.** A bad query is bad on a partitioned table too.

## The three partitioning strategies

### Range partitioning

```sql
CREATE TABLE events (
    id BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB
) PARTITION BY RANGE (occurred_at);

CREATE TABLE events_2026_q1 PARTITION OF events
    FOR VALUES FROM ('2026-01-01') TO ('2026-04-01');

CREATE TABLE events_2026_q2 PARTITION OF events
    FOR VALUES FROM ('2026-04-01') TO ('2026-07-01');
```

Most common. Time-series workloads.

### List partitioning

```sql
CREATE TABLE users (...) PARTITION BY LIST (country_code);

CREATE TABLE users_us PARTITION OF users FOR VALUES IN ('US');
CREATE TABLE users_eu PARTITION OF users FOR VALUES IN ('DE','FR','IT','ES');
```

Useful for known small-cardinality categorical splits.

### Hash partitioning

```sql
CREATE TABLE messages (...) PARTITION BY HASH (conversation_id);

CREATE TABLE messages_p0 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 0);
-- ... seven more
```

For when you want even distribution but no natural key. Rarely the right pick — if you have a natural key, range / list usually works better.

## Picking a partition key

Like sharding, the partition key is hard to change once you've committed. Choose carefully.

Rules:

- **The key should appear in most query WHERE clauses.** Otherwise the planner scans every partition.
- **The key shouldn't have skew.** A partition with 80% of the data isn't really partitioned.
- **For range partitioning, the key should be monotonically increasing or otherwise predictable** so you can plan partition creation.
- **For multi-tenant, partition key = tenant_id**, with sub-partitioning if needed.

For time-series: partition by month / quarter / year depending on data volume. Monthly is common for high-volume; quarterly for moderate.

## Constraint exclusion / partition pruning

The win is partition pruning. A query like:

```sql
SELECT * FROM events WHERE occurred_at >= '2026-04-01' AND occurred_at < '2026-04-30';
```

Hits only the `events_2026_q2` partition. Postgres skips the others.

Verify with `EXPLAIN`:

```
Append
  -> Seq Scan on events_2026_q2  (only this one)
```

If the plan shows scans on partitions that shouldn't match, your query doesn't use partition keys appropriately, or your queries aren't constraint-exclusion-compatible.

## Indexes on partitioned tables

Indexes are per-partition. Creating an index on the parent declares it for all partitions:

```sql
CREATE INDEX ON events (user_id);
-- Postgres creates per-partition indexes
```

This avoids having to remember to add indexes per new partition. Cost: more total index storage; some maintenance overhead.

Constraints (UNIQUE, FK) are also per-partition. A unique constraint must include the partition key; otherwise it can't be enforced across partitions.

## Operational concerns

### Adding partitions

For range partitioning, schedule new-partition creation:

```sql
-- Monthly job
CREATE TABLE events_2026_05 PARTITION OF events
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
```

`pg_partman` automates this. Worth installing for any production time-series workload.

Forget to create a future partition; inserts that don't match any partition error out. Always have N+1 partitions ready.

### Dropping partitions

For time-series with retention, drop old partitions:

```sql
DROP TABLE events_2024_q1;
```

Fast; no `DELETE`-and-vacuum overhead. The biggest practical reason to partition by time.

### Detaching partitions

Take a partition out of the parent without dropping it:

```sql
ALTER TABLE events DETACH PARTITION events_2024_q1;
-- events_2024_q1 is now an independent table
```

Useful for archival or moving partitions to a separate database.

### Vacuum

Vacuum runs per-partition. For very large parents, this is a win — autovacuum can keep up better with smaller partitions than one giant table.

Be careful: indexes on partitions also need their own vacuum. Default autovacuum settings sometimes don't cover all partitions equally well; tune per-partition for hot ones.

### Index maintenance

`REINDEX` per partition. `pg_repack` works per partition.

### Partition-wise joins / aggregations

Postgres can sometimes execute joins per-partition (each partition joined with the other table; results unioned). Faster for some queries; needs the partition key on both sides.

## Things people get wrong

**Forgetting the partition key in queries.** "Why is this slow?" Because the planner has to scan all partitions. Always include the partition key.

**Partitioning too granularly.** 1000 partitions of 1MB each = overhead exceeds benefit. Partition into chunks that are big enough to matter (10 GB+ each is typical).

**Partitioning prematurely.** A 5 GB table doesn't need partitioning. Reach for partitioning above ~50 GB or where retention-driven drops are needed.

**Using hash partitioning when range / list would do.** Hash hides the meaning of the partition; debugging is harder.

**Cross-partition unique constraints.** Can only enforce uniqueness within one partition unless the partition key is in the constraint.

**Cross-partition foreign keys.** Limited support. Plan accordingly.

## When to skip partitioning

- **Tables under ~50 GB** — vacuum and indexing are fine without partitioning.
- **Workloads without natural partition keys** — forcing one creates pain.
- **Cross-partition queries dominate** — partitioning adds cost without benefit.
- **You're considering sharding instead** — if you're going to need multiple machines anyway, look at sharding now.

## Comparison to sharding

Partitioning and sharding sound similar but solve different problems:

| | Partitioning | Sharding |
|---|---|---|
| Scope | Single database | Multiple databases / machines |
| Use case | Big table; retention | Beyond single-machine capacity |
| Operational complexity | Moderate | High |
| Query routing | Database planner | Application or proxy layer |
| Cross-shard / cross-partition queries | Native (one DB) | Application-level fan-out |

Partitioning is the cheaper of the two; reach for it first. Move to sharding only when partitioning doesn't keep up.

## A concrete example: time-series partitioning

For an `events` table with 50M rows/month, retain 12 months:

```sql
CREATE TABLE events (
    id BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL,
    user_id BIGINT,
    type TEXT,
    payload JSONB,
    PRIMARY KEY (id, occurred_at)  -- include partition key
) PARTITION BY RANGE (occurred_at);

-- Indexes
CREATE INDEX ON events (user_id, occurred_at);
CREATE INDEX ON events USING BRIN (occurred_at);

-- Use pg_partman for partition lifecycle
SELECT partman.create_parent('public.events', 'occurred_at', 'native', 'monthly');
SELECT partman.config_parent('public.events', retention => '12 months');

-- Daily partman maintenance creates new partitions and drops old
```

This handles 600M rows/year, queries hit one or two partitions, retention is cheap.

## Further reading

- [DatabaseSharding] — when partitioning isn't enough
- [DatabaseDesign] — schema design that anticipates partitioning
- [DatabaseIndexingStrategies] — per-partition index choices
- [PostgresqlAdvancedFeatures] — Postgres-specific features (BRIN works well with time-partitioned data)
