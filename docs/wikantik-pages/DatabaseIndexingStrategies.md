---
canonical_id: 01KQ12YDTH1542JECTHCYJ8SSM
title: Database Indexing Strategies
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- indexing
- btree
- gin
- brin
- query-optimization
summary: Picking the right index type for the query — B-tree, hash, GIN, GiST,
  BRIN, partial, covering — and the failure modes of indexes that look right
  but aren't.
related:
- DatabaseDesign
- DatabasePerformanceMonitoring
- PostgresqlAdvancedFeatures
- DatabasePartitioning
hubs:
- Databases Hub
---
# Database Indexing Strategies

An index is a data structure the database maintains alongside a table so that some queries can answer in `O(log N)` instead of `O(N)`. Pick the wrong index, or no index, and the query planner does what it has to do — read every row.

Most index problems are one of two: (a) the right index doesn't exist, or (b) the index exists but the planner doesn't use it. This page is a working catalogue, focused on Postgres because it's the most expressive but the principles transfer.

## The index types you actually use

| Type | Postgres name | What it indexes | Best for |
|---|---|---|---|
| **B-tree** | `BTREE` (default) | Ordered scalar columns | Equality, range, ORDER BY, LIMIT |
| **Hash** | `HASH` | Hashed scalar columns | Equality only — almost always B-tree wins instead |
| **GIN** | `GIN` | Composite values (arrays, JSONB, full-text) | "Does the array contain X", "is this JSON path matching" |
| **GiST** | `GIST` | Geometric/spatial, range types, custom | Geographic queries, range overlap |
| **BRIN** | `BRIN` | Block range summaries | Huge tables with naturally clustered values (time-series) |
| **Bloom** | `bloom` extension | Set membership | Many low-selectivity columns combined |
| **Hash (unique)** | n/a (use B-tree) | n/a | Don't reach for hash. Postgres B-tree handles equality fine. |

For 90% of OLTP queries, B-tree is the answer. The rest of the table matters when your workload has specific shapes.

## When each index type wins

### B-tree

Default. Sorted, supports equality and range. Multi-column B-tree on `(a, b, c)` supports queries with `a`, with `(a, b)`, with `(a, b, c)` — but NOT just `b` or just `c`. The "leftmost-prefix" rule.

Use covering indexes (`INCLUDE`) to add non-key columns the query needs without paying for them in the sort key:

```sql
CREATE INDEX idx_orders_user ON orders (user_id) INCLUDE (status, total);
```

This makes index-only scans work for `SELECT status, total FROM orders WHERE user_id = ?`. Saves the trip to the heap.

### GIN

For arrays, JSONB, full-text, trigram. The right answer for:

- `WHERE tags @> ARRAY['urgent', 'paid']` — JSONB or array containment.
- `WHERE doc_tsv @@ to_tsquery('database & sharding')` — full-text.
- `WHERE name % 'jak'` — trigram for fuzzy match.

Trade-off: GIN indexes are slower to update (especially under heavy write workloads) than B-tree. For read-heavy JSONB columns, very much worth it. For write-heavy, profile before adding.

### BRIN

For tables where physical order on disk correlates with the indexed column — typically time-series. Stores summaries of block ranges (min/max per range). Tiny on disk. Useful only when the correlation is real.

```sql
CREATE INDEX idx_metrics_time ON metrics USING BRIN (recorded_at)
  WITH (pages_per_range = 32);
```

A BRIN on a 1 TB time-series table might be 50 MB compared to a 100 GB B-tree. Lookup is approximate but precise enough — the planner narrows to a few hundred MB then scans them.

If you don't have natural ordering, BRIN doesn't help. `CLUSTER` rebuilds the table in index order (offline, expensive); for time-series, ordering tends to be natural.

### GiST

Geographic queries (`PostGIS`), range type queries, custom data types. Pick when those apply; otherwise ignore.

### Partial indexes

An index with a `WHERE` clause. Indexes only the rows that match.

```sql
CREATE INDEX idx_orders_active ON orders (user_id) WHERE status = 'active';
```

Wins when:
- The filtered subset is small (< 10% of the table).
- Queries always include the same filter.

The index is smaller, faster to maintain, and can dramatically speed up the targeted query. Underused in practice.

## Selectivity decides whether the index runs

The query planner uses an index only when it estimates the index will be selective enough that the savings outweigh the heap-fetch overhead. A common rule of thumb: at > 10% of rows, sequential scan often wins.

This is why:

- An index on `is_deleted` (boolean) usually doesn't help — half the table matches.
- An index on `country_code` is bad if 95% of users are in one country — for that country, the planner picks seq-scan.
- The fix for low-selectivity columns is usually composing them: `(country_code, signup_date)` cuts the matching set faster than either alone.

Check the planner's choice with `EXPLAIN (ANALYZE, BUFFERS)`. If you expected an index scan and got a sequential scan, the planner probably has accurate statistics and is right; investigate the row counts before "fixing" with a hint.

## Multi-column indexes: the order matters

Index `(a, b, c)` supports queries on `a`, `(a, b)`, `(a, b, c)`. It does not support queries on `b` alone or `c` alone — they'd need their own indexes.

Heuristic for ordering:
1. **Equality columns first.** `WHERE a = 1 AND b BETWEEN 10 AND 20` — equality on `a` first.
2. **High-selectivity columns first** within the equality group.
3. **Range / sort columns last.** `ORDER BY` columns at the end of the index let the index satisfy the sort.

Common mistake: indexing `(created_at, user_id)` when the query is `WHERE user_id = ? ORDER BY created_at`. The index won't help — it's ordered by `created_at` first. Reverse the order.

## Index-only scans

When all columns the query needs are in the index (key columns or `INCLUDE` columns), Postgres can answer entirely from the index without touching the heap. This is a 5–50× speedup.

For an index-only scan, Postgres also needs the visibility map up to date — `VACUUM` regularly. A table with high update churn and no recent vacuum can defeat index-only scans even if the index covers all needed columns.

## Index maintenance, the part people skip

Indexes degrade. They bloat under heavy update workloads. They become invalid after partition operations. They develop dead tuples after lots of deletes.

- **Periodic `REINDEX CONCURRENTLY`.** Postgres bloat accumulates; rebuilding compacts. Quarterly for high-churn indexes.
- **Watch `pg_stat_user_indexes`.** `idx_scan = 0` over months means no one's using this index. Drop it; reduce write amplification.
- **Watch `pg_stat_indexes` for bloat.** `pgstattuple` shows free-space ratios. Above 50% free space = candidate for REINDEX.
- **Don't over-index.** Each index slows down inserts and updates by a measurable amount. 5 indexes on a hot table can cut write throughput by 30–40% versus 1.

The bias most teams have is "more indexes are better." It isn't. Profile the actual queries, index for those, drop the rest.

## Failure modes

**Function on the indexed column kills the index.** `WHERE LOWER(email) = ?` doesn't use an index on `email`. Either index `LOWER(email)` (functional index) or store the lowercased version.

**Implicit type casts kill the index.** `WHERE id = '42'` (int column, string literal) sometimes prevents index use. Match types.

**`OR` queries often kill index usage.** Combine with `UNION ALL` of two indexed queries, or use a multi-column index that covers both branches.

**Statistics out of date.** After bulk loads, `ANALYZE` to update stats. Old stats lead to wrong plans (sequential scan when index would win, or vice versa).

**Index used but heap is the bottleneck.** Index says "go fetch row X"; row X is on cold disk. Profile via `EXPLAIN BUFFERS`; consider covering index or partitioning.

## Tools

- **`EXPLAIN (ANALYZE, BUFFERS, VERBOSE)`** — first stop for any slow query. Read the plan; understand where time goes.
- **`pg_stat_statements`** — top N queries by total time. Aggregate; great for finding what to fix first.
- **`pgbadger`** — log-based slow-query report; useful for retrospective analysis.
- **`pgstattuple`** — bloat measurement.
- **HypoPG** — try a hypothetical index without creating it; planner shows whether it would help.

## A diagnostic workflow

1. Find slow query (via `pg_stat_statements`).
2. `EXPLAIN ANALYZE` it. What does the planner do?
3. If the wrong index is chosen → check stats with `ANALYZE`, check selectivity assumptions.
4. If no index exists → add one. Pick the right type. Verify the planner uses it.
5. If index exists but isn't used → check function-on-column, type cast, OR clause.
6. Re-run; verify before/after.

This loop catches 80% of indexing problems. The other 20% are workload-shape issues (write-heavy + read-heavy on the same hot row, partitioning needed, sharding needed) that go beyond indexing.

## Further reading

- [DatabaseDesign] — schema choices that make indexing tractable
- [DatabasePerformanceMonitoring] — surfaces what to index
- [PostgresqlAdvancedFeatures] — Postgres-specific index types in depth
- [DatabasePartitioning] — when indexes alone aren't enough
