---
canonical_id: 01KQ12YDTJB450KWE7Q8ZXV6DC
title: Database Performance Monitoring
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- monitoring
- performance
- postgresql
- query-optimization
summary: The Postgres metrics that catch real problems early — connection saturation,
  slow queries, lock waits, replication lag, autovacuum drift — plus the dashboards
  worth building.
related:
- DatabaseIndexingStrategies
- PostgresqlAdvancedFeatures
- DatabaseDesign
- DistributedTracing
hubs:
- Databases Hub
---
# Database Performance Monitoring

Database problems are usually slow-developing — the system gets gradually worse over weeks until it tips over and a query that used to be 10ms is 30s. Catching this requires monitoring. The right metrics turn slow degradation into a Tuesday-morning "we should look at that" instead of a 3am page.

This page is the working set of metrics for Postgres specifically; principles transfer.

## The signals that matter

Five categories. Get these and you catch most database problems.

| Category | Top metrics |
|---|---|
| **Connections** | Active + idle counts, max-connection limit utilisation, pool wait time |
| **Queries** | p95/p99 latency, slow-query rate, top queries by total time |
| **Locks** | Lock wait time, deadlock count, longest-held locks |
| **I/O & cache** | Cache hit ratio, dirty page rate, disk I/O wait |
| **Replication & WAL** | Replication lag, WAL volume, archive failures |

Each of these has a sane Postgres view to read from. Most observability stacks have prebuilt exporters (`postgres_exporter` for Prometheus); use them.

## Connections

Connection pressure is the single most common Postgres issue. Postgres uses one process per connection; `max_connections` defaults to 100, can go higher but doesn't scale linearly.

Track:

- `pg_stat_activity` — current connections by state (`active`, `idle`, `idle in transaction`).
- `idle in transaction` count — these are bugs in your application (transactions started but not committed). High counts indicate connection leaks.
- Connection-pool wait time — how long client requests wait for a free connection. Should be near zero.

Alert:

- Active connections > 80% of `max_connections` for 5+ minutes.
- `idle in transaction` count > 5 for any sustained period.
- Pool wait time p95 > 100ms.

The almost-universal fix for connection pressure is **PgBouncer in transaction mode** in front of the database. PgBouncer multiplexes thousands of client connections to a pool of hundreds (or fewer) backend connections. Mandatory at any meaningful scale.

## Queries: pg_stat_statements

`pg_stat_statements` is the most useful Postgres extension. It records normalised query stats — `total_exec_time`, `calls`, `mean_exec_time`, `rows` — for every query the database has seen.

Top-by-total-time view:

```sql
SELECT query, calls, total_exec_time, mean_exec_time, rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;
```

This tells you what to optimise. The query at the top of "total time" is where the database is actually spending its life — even if it's individually fast, high call counts add up.

Alert:

- Mean exec time of any top-50 query > 100ms (or whatever your threshold is).
- New query appears in top-10 (regression from a deploy).
- Total exec time per minute trending up week-over-week (workload growth).

## Slow query log

Set `log_min_duration_statement = 500` (or wherever your latency threshold is). Slow queries land in the Postgres log. Tools like `pgBadger` summarise.

Use it as the complement to `pg_stat_statements` — the latter aggregates, the former gives you full bound-parameter examples to reproduce.

## Locks and waits

`pg_locks` joined with `pg_stat_activity` shows currently-held locks and waiting queries.

Key metrics:

- **Long-held locks** (> 1s for ROW EXCLUSIVE; > 100ms for ACCESS EXCLUSIVE).
- **Lock-wait queue length** — queries waiting on others.
- **Deadlock count** (`pg_stat_database.deadlocks`) — should be near zero. Non-zero means application code with conflicting transaction ordering.

Most problems show up here:

- DDL operations holding `ACCESS EXCLUSIVE` waiting on a long-running query, blocking everything.
- Application code with `SELECT FOR UPDATE` holding rows; another transaction hangs.
- Autovacuum bumping into a long transaction, can't proceed.

## I/O and cache

Postgres uses shared buffers (configurable) plus the OS page cache.

- `pg_stat_database.blks_hit / (blks_hit + blks_read)` — buffer cache hit ratio. > 99% is healthy; < 95% means working set doesn't fit and you're going to disk constantly.
- `pg_statio_user_tables` — per-table I/O stats. Identifies which tables are I/O-heavy.
- Disk wait time on the host — `iostat`, node-exporter. High `await` or `util` indicates I/O bottleneck.

Solutions:

- More shared buffers (typical: 25% of RAM up to ~16GB).
- Larger machine (more RAM = more page cache).
- Better indexes (less data to scan).
- Partitioning to keep hot working sets smaller.

## Replication

For setups with replicas:

- **Replication lag in seconds** — `pg_stat_replication.replay_lag` on the primary, or `pg_last_xact_replay_timestamp()` on the replica.
- **WAL volume produced/sec** — surge often indicates a runaway query writing many rows.
- **Replication slot status** — physical/logical slots that fall behind cause WAL retention on the primary; disk fills.

Alert:

- Replica lag > 30s (or whatever your tolerance is).
- WAL volume > 2× baseline.
- Any replication slot inactive for > 5 minutes.

## Autovacuum / bloat

Postgres MVCC creates dead tuples; autovacuum cleans them up. When autovacuum can't keep up, tables bloat.

Track:

- **Dead tuple ratio per table** (`pg_stat_user_tables.n_dead_tup / n_live_tup`).
- **Last autovacuum time** per table — should be recent for active tables.
- **Autovacuum running count** — surge means catching up; sustained = workers full = problem.

Tooling: `pgstattuple` extension shows true bloat. `pg_repack` rebuilds bloated tables online.

A high-write table with > 50% dead-tuple ratio means autovacuum is losing. Tune `autovacuum_vacuum_scale_factor` lower for that table; check that long-running transactions aren't blocking vacuum.

## What to put on the dashboard

The dashboard a DBA actually looks at:

1. **Connection panel** — active, idle, idle in transaction, max.
2. **Query latency panel** — p50, p95, p99 over time. Top 10 slow queries by total time.
3. **Lock panel** — current waiting queries, longest held lock, recent deadlocks.
4. **Cache panel** — hit ratio, top tables by I/O.
5. **Replication panel** — replica lag, WAL produced/sec.
6. **Bloat panel** — top bloated tables, autovacuum activity.

If you're staring at this for the first time and one of the panels is missing, that's where the next outage is hiding.

## Tools

- **`pg_stat_statements`** — non-negotiable. Enable in postgresql.conf.
- **`postgres_exporter` + Prometheus + Grafana** — open-source stack. Many grafana dashboards exist; start with the official Postgres one.
- **`pgBadger`** — log analysis, slow-query reports.
- **Datadog / Sentry / commercial APM** — paid options; integrate well.
- **`pgstattuple`** — bloat measurement, when needed.
- **`auto_explain`** — automatically logs query plans for slow queries; invaluable for "why is this slow."

## A minimum starter stack

For a team setting up Postgres monitoring from scratch:

```
- Enable pg_stat_statements
- Enable auto_explain (log_min_duration = 1000, log_analyze = on)
- Run pgBouncer in transaction mode in front of Postgres
- Run postgres_exporter; ship to Prometheus
- Build grafana dashboard with the six panels above
- Set log_min_duration_statement = 500 to catch slow queries
- Configure pgBadger to run nightly on logs
- Set up alerts on the metrics above
```

A day's work; permanent operational visibility.

## Failure modes monitoring catches

Real examples this monitoring stack catches:

- "Why is the app slow?" → connection pool wait time spiked → check `idle in transaction` → the new feature has a missing `commit()`. Fix.
- "Why did this query slow down?" → `pg_stat_statements` shows it changed plan → `auto_explain` shows missing index → add index.
- "We seem to be running out of disk?" → replication slot inactive → WAL retention growing forever → fix the dead replica.
- "Updates seem stuck?" → lock-wait dashboard shows row lock held by long-running transaction → kill it.

Without monitoring, each of these is a frantic investigation. With it, a click and a fix.

## Further reading

- [DatabaseIndexingStrategies] — what to do once monitoring tells you what's slow
- [PostgresqlAdvancedFeatures] — Postgres-specific tuning
- [DatabaseDesign] — schema choices that affect monitorability
- [DistributedTracing] — joining DB telemetry to broader app traces
