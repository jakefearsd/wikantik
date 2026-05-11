---
summary: How to design data pipelines that are observable, idempotent, and resilient
  — the patterns that scale, the failure modes to avoid, and the orchestration tools
  in modern data stacks.
date: '2026-04-26'
cluster: data-engineering
related:
- EtlVsElt
- MapReduceParadigm
- DbtAndAnalyticsEngineering
- DataModelingFundamentals
canonical_id: 01KQ0P44P962TNREGKV1782DA8
type: article
title: Data Pipeline Design
tags:
- data-pipelines
- etl
- data-engineering
- airflow
- orchestration
status: active
hubs:
- DataEngineeringHub
- DataModelingFundamentals Hub
---
# Data Pipeline Design

A data pipeline moves and transforms data: from sources, through stages, to destinations. The patterns that work in production differ from the textbook ETL examples. Real pipelines must handle failures, late-arriving data, schema changes, retries, and observability.

This page covers the patterns that hold up.

## The components

Most pipelines have:

- **Sources**: where data comes from (databases, APIs, event streams, files)
- **Ingestion**: pulling data into your system
- **Transformation**: cleaning, joining, aggregating
- **Storage**: data lake, warehouse, operational store
- **Orchestration**: scheduling, dependencies, retries
- **Observability**: monitoring, alerting, lineage

## Idempotency

The most important property. Running the same pipeline twice should produce the same result.

Why it matters:
- Failures happen; retries are expected
- Backfills require re-running
- Bugs require fixing data after the fact

Idempotency requires:
- **Deterministic transformations**: same input → same output
- **Upserts not inserts**: re-running doesn't double-insert
- **Time windows that don't shift**: yesterday's job processes a defined window

Anti-patterns:
- Auto-incrementing IDs assigned in pipeline (re-runs produce different IDs)
- "Just append" patterns (duplicates on retry)
- Operations on "yesterday" without explicit dates (retry days later runs different data)

## Partitioning

Most pipelines partition by date:

```
data/
  events/
    date=2026-04-25/
    date=2026-04-26/
    date=2026-04-27/
```

Each partition is independent. Re-running just `2026-04-26` doesn't affect other dates. Failures isolate to specific partitions.

Partition keys vary:
- Date (most common)
- Date + hour for high-volume
- Date + region or customer
- Whatever makes work parallelizable

## Late-arriving data

Real-world data arrives late. An event from 2026-04-25 might appear in your source on 2026-04-27.

Strategies:
- **Reprocessing window**: re-run last N days of partitions to catch late data
- **Watermarking**: partition by event time, not arrival time
- **Allow late merge**: pipeline designed to update partitions when late data arrives

The right approach depends on data volume and timeliness requirements. Most pipelines need to handle late data; pretending it doesn't exist produces incorrect analytics.

## Schema evolution

Source data structure changes. Field added, type changed, field renamed.

Patterns:
- **Schema-on-read**: store raw; apply schema when reading. Flexible but slow.
- **Schema-on-write**: enforce schema at ingestion. Fast but rigid.
- **Forward-compatible serialization**: Avro, Protobuf, or schema registry that handles evolution

For data lakes (S3 + Parquet), schema-on-read is common. For warehouses, schema-on-write.

Either way, plan for schema changes. They will happen.

## Orchestration

The scheduler that runs pipelines, manages dependencies, retries failures.

### Apache Airflow

The dominant choice. DAG (Directed Acyclic Graph) of tasks; Python-based.

Pros: powerful; large community; many operators.
Cons: heavyweight; UI is dated; running it well requires real ops investment.

### Prefect, Dagster, Mage

Modern alternatives. Python-based; emphasize developer ergonomics; cleaner UIs.

### dbt

For warehouse-resident transformations. Different role than orchestration; sometimes paired with Airflow. See [DbtAndAnalyticsEngineering](DbtAndAnalyticsEngineering).

### Cloud-native

AWS Step Functions, GCP Cloud Composer (managed Airflow), Azure Data Factory. Less ops; cloud-specific.

For most teams, managed Airflow or one of the modern alternatives is the right choice.

## Observability

Pipelines fail in subtle ways. Without observability, failures are silent.

Required:
- **Job-level metrics**: runtime, rows processed, error count
- **Logging**: structured; queryable
- **Lineage**: which datasets depend on which
- **Data quality checks**: row counts, null rates, value distributions

Tools: dbt has tests; Great Expectations and Soda for explicit data quality; OpenLineage for cross-tool lineage.

Without these, "is the pipeline OK?" is unanswerable.

## Backfills

Re-running pipelines for past data. Common reasons:
- Bug found; re-process to fix
- New computation added to historical data
- Schema change requires regeneration

Designing for backfill:
- Idempotent (covered above)
- Partitioned (so you re-run specific partitions)
- Resource-throttled (don't crush the warehouse during backfill)
- Documented sequence (which partitions in what order)

Backfills that take a week to plan are common. Designing for them up front saves time.

## Streaming vs. batch

Two paradigms:

### Batch

Pipelines run on a schedule. Daily, hourly, every 15 minutes. Process accumulated data.

Pros: simpler; recovery is easier; tooling is mature.
Cons: latency = batch interval.

### Streaming

Pipelines run continuously. Each event processed as it arrives.

Pros: low latency.
Cons: complex; harder to debug; harder to backfill.

Most pipelines should be batch. Stream when latency genuinely matters (real-time fraud, real-time recommendations).

The "Lambda architecture" — batch + streaming both running the same logic — has fallen out of favor; modern systems use one or the other.

## Common failure patterns

- **Non-idempotent pipelines.** Retries cause duplicates.
- **No partitioning.** Failures affect everything.
- **No observability.** Silent failures.
- **Streaming when batch would do.** Complexity without benefit.
- **No data quality checks.** Bad data flows downstream.
- **No backfill plan.** Recovery from bugs takes weeks.
- **Heavy initialization per task.** Slow pipelines.

## Further Reading

- [EtlVsElt](EtlVsElt) — Where transformation happens
- [MapReduceParadigm](MapReduceParadigm) — Foundational batch model
- [DbtAndAnalyticsEngineering](DbtAndAnalyticsEngineering) — Warehouse-resident transform
- [DataModelingFundamentals](DataModelingFundamentals) — What pipelines produce
- [DataEngineering Hub](DataEngineeringHub) — Cluster index
