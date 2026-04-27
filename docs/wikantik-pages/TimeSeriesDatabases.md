---
canonical_id: 01KQ0P44XQTDJ9RJY6BF64FRGK
title: Time-Series Databases
type: article
cluster: databases
status: active
date: '2026-04-26'
summary: How time-series databases work — InfluxDB, TimescaleDB, Prometheus — and
  the cases where they fit better than general-purpose databases.
tags:
- time-series
- influxdb
- timescaledb
- prometheus
- databases
related:
- CloudDatabases
- ElasticsearchFundamentals
- CloudMonitoring
---
# Time-Series Databases

Time-series data: measurements indexed by time. Sensor readings, metrics, financial ticks, log events. Each point has a timestamp; you usually query ranges of time.

General-purpose databases handle this poorly at scale. Time-series databases are optimized for the specific access patterns.

This page covers when they fit and the major options.

## What makes time-series different

### Data shape

Mostly inserts, append-only. Updates rare; deletes mostly via retention.

```
metric_name | timestamp | value | tags
cpu_usage   | t1        | 0.45  | host=A region=us
cpu_usage   | t2        | 0.47  | host=A region=us
cpu_usage   | t3        | 0.52  | host=A region=us
```

Each insert is small; the volume is enormous.

### Queries

- Aggregations over time windows: "average CPU per minute over last hour"
- Recent data dominates: "last 5 minutes" is queried more than "last 5 years"
- Downsampling: convert high-resolution data to lower resolution for older data

### Lifecycle

Data has retention: keep raw for 30 days, downsampled for 1 year, monthly aggregates for 5 years. Older data automatically deleted.

## Why general-purpose databases struggle

### Index size

A typical PostgreSQL setup with B-tree indexes on timestamp + tags: indexes get huge as data grows. Inserts slow.

### Aggregations

Computing "average over 1 hour" requires scanning many rows. Without specialized storage, slow.

### Compression

Time-series data compresses extremely well (similar values close in time). General DBs don't optimize for this.

### Retention

Deleting old data is expensive in MVCC databases. Time-series DBs handle this efficiently via partitioning.

## The major options

### InfluxDB

Purpose-built time-series database. Tag-based; SQL-like query language (Flux); good performance.

InfluxDB 1.x vs. 2.x: significant changes; the v2 API is different. Pick deliberately.

### TimescaleDB

PostgreSQL extension. Time-series features (chunked storage, automatic partitioning) on top of PostgreSQL.

For organizations that want PostgreSQL ecosystem + time-series performance.

### Prometheus

Metrics-focused. Pull-based (scrapes targets). Built-in query language (PromQL). Limited durability — typically used with long-term storage backend (Thanos, Cortex, Mimir).

Standard for Kubernetes/cloud-native monitoring.

### Apache Druid

Real-time analytics on time-series. Heavyweight; for large-scale analytics.

### ClickHouse

Columnar OLAP database. Very fast aggregations; not strictly time-series but used for it.

### TimestreamDB (AWS)

Managed time-series on AWS. Serverless; pay-per-use.

For most use cases:
- **Metrics monitoring**: Prometheus + long-term storage
- **General time-series application data**: TimescaleDB
- **High volume custom metrics**: InfluxDB or ClickHouse

## Specific patterns

### Downsampling

High-resolution data is expensive to store long-term. Periodically aggregate:

```
Raw 1-second data → 1-minute averages (kept 90 days) → 1-hour averages (kept 1 year)
```

Most time-series DBs have automated downsampling.

### Continuous aggregates

TimescaleDB feature: precomputed aggregates that update incrementally. Queries hit the precomputed view instead of raw data.

### Tags / labels

Time-series points have tags ("host=A", "region=us"). Tags index the data; queries filter and group by tags.

Cardinality matters: too many distinct tag combinations explode storage.

### Retention policies

Built-in: "keep raw 30 days; aggregated 1 year; monthly summaries forever."

Without retention: data grows until storage fills.

### Compression

Time-series compresses 10-100x. The DB handles this; you don't manage manually.

## When time-series DBs are right

- **Metrics**: CPU, memory, request latency, business metrics
- **IoT data**: sensor readings
- **Financial ticks**: high-frequency trading data
- **Application telemetry**: per-request timings, custom counters
- **Log events** (sometimes): when grouped by time

## When they're not

### Transactional data

Order created at time T isn't really "time-series" — it's an order. Use a relational DB.

### Heavy updates to existing points

Time-series DBs assume append-mostly. Update-heavy workloads don't fit.

### Ad-hoc analytical queries

Some time-series DBs are limited in query expressiveness. For complex analysis, OLAP or warehouse.

### Small data

A few thousand points per day fit in any database. Don't introduce time-series infrastructure for small needs.

## Cardinality management

The biggest scaling concern. Each unique combination of tags is a "series."

Bad:
```
tags: user_id=user-123  ← creates a new series for every user
```

Good:
```
tags: country=US, plan=premium  ← bounded set
```

High cardinality (millions of series) crushes most time-series DBs. Plan tag schemas accordingly.

## Common failure patterns

- **Using time-series for non-time-series.** Wrong tool.
- **High cardinality.** Performance collapse.
- **No retention.** Storage explodes.
- **No downsampling.** Hot data old; expensive to query.
- **Custom dashboards reading raw data.** Slow; should use precomputed.
- **Single time-series DB for both metrics and logs.** Different access patterns; usually want different tools.

## A reasonable starter

For monitoring needs: Prometheus + Grafana for current; long-term storage (Thanos/Mimir) if retention matters.

For application time-series: TimescaleDB. Familiar SQL; strong performance.

For very high-cardinality or specialized needs: evaluate InfluxDB, ClickHouse.

## Further Reading

- [CloudDatabases](CloudDatabases) — Database options
- [ElasticsearchFundamentals](ElasticsearchFundamentals) — Adjacent for log data
- [CloudMonitoring](CloudMonitoring) — Where metrics fit
