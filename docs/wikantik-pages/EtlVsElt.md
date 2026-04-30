---
canonical_id: 01KQ0P44Q7XSTJT11T5EQ84PG0
title: ETL vs. ELT
type: article
cluster: data-engineering
status: active
date: '2026-04-26'
summary: The shift from ETL to ELT — what changed, why modern data warehouses enabled
  it, and the cases where each paradigm fits.
tags:
- etl
- elt
- data-warehouse
- data-engineering
- transformation
related:
- DataPipelineDesign
- DbtAndAnalyticsEngineering
- DataModelingFundamentals
- CloudDatabases
hubs:
- DataEngineeringHub
---
# ETL vs. ELT

The traditional pattern: Extract, Transform, Load (ETL). Pull data from sources, transform in a separate process, load the cleaned data into the warehouse.

The modern pattern: Extract, Load, Transform (ELT). Pull raw data, load it as-is into the warehouse, transform inside the warehouse using SQL.

The shift is real and recent. ELT is the dominant paradigm in modern data stacks.

## Why ETL was the default

Old data warehouses (Teradata, Oracle, on-prem) were:
- Expensive (licensed by capacity)
- Constrained (limited storage and compute)
- Slow at heavy transformation

Loading raw data and transforming inside was expensive. The natural pattern: clean and reduce data before loading; warehouse only sees the final shape.

Tools: Informatica, Talend, custom Python/Java jobs running outside the warehouse.

## What changed

Modern cloud data warehouses (Snowflake, BigQuery, Redshift, Databricks) are:
- Storage and compute decoupled
- Storage cheap; compute scales elastically
- SQL-on-anything capable

Loading raw data is cheap. Transformation in SQL is fast. The warehouse can do what dedicated ETL tools used to do, but in SQL that analysts can read and write.

The natural pattern flipped: load raw; transform in warehouse.

## ELT in practice

```
Sources → Ingestion (Fivetran, Airbyte, custom) → Raw warehouse layer → dbt → Analytics tables
```

The warehouse holds:
- **Raw**: as it came from the source
- **Staging**: light cleaning, naming
- **Intermediate**: business logic
- **Marts**: shaped for analysis

Each layer is SQL transforms over the previous. dbt is the dominant tool for managing these transforms. See [DbtAndAnalyticsEngineering](DbtAndAnalyticsEngineering).

## Pros of ELT

### Simplicity

Most transformations are SQL. Analysts can read and modify them. No separate ETL skill required.

### Reproducibility

Raw data preserved. Transformations re-run produce the same result. Bug found? Fix the SQL; re-run.

### Discoverability

Data is in the warehouse already. Analysts can explore raw and transformed data freely.

### Speed

Modern warehouses are fast. Transformations in SQL on Snowflake/BigQuery often beat external ETL.

### Backfills are easier

Raw data is there; just re-run transforms. No need to re-extract from sources.

## Pros of ETL (still)

### Some transformations don't fit SQL

Complex JSON parsing, ML feature engineering, image processing. Sometimes the transform belongs in code, not SQL.

### Privacy / compliance

Sometimes you can't load raw data. PII redaction must happen before warehouse.

### Source system load

Heavy raw data load can stress source systems. Lightweight extracts may be necessary.

### Cost at extreme scale

ELT in cloud warehouses is cheap until it isn't. At extreme scale, dedicated processing (Spark, Flink) can beat warehouse compute.

## When ETL still wins

- PII or compliance requires pre-load redaction
- Transformation is non-SQL-shaped (ML, complex parsing)
- Source data is enormous and warehouse loading is impractical
- Specific cost optimization at scale

For most modern data stacks, ELT is right. ETL is for specific cases.

## The "ELTL" hybrid

Some modern stacks do:
- Extract → Load (raw) → Transform (in warehouse) → Load (to operational stores)

The transformations happen in the warehouse; the result is loaded to operational stores (search indexes, caches, etc.) for low-latency access.

This is functional ELT plus a "publish" step.

## Tooling

### Ingestion (the "EL" part)

- **Fivetran, Stitch**: managed connectors; pay for convenience
- **Airbyte**: open-source equivalent
- **Custom**: when no managed connector exists

### Warehouse (the "T" target)

- **Snowflake**: dominant; multi-cloud
- **BigQuery**: GCP-native; very fast
- **Redshift**: AWS-native; older
- **Databricks**: lakehouse architecture

### Transformation (the "T" engine)

- **dbt**: dominant for SQL transforms
- **SQL**: for simpler cases
- **Spark / PySpark**: for non-SQL transformations

### Orchestration

- **Airflow**: heavy; classic
- **Prefect, Dagster**: modern alternatives
- **dbt Cloud**: integrated with dbt

## Common failure patterns

- **Treating ELT as "load everything raw"** without thinking about what's actually needed. Massive raw data with no usage is just cost.
- **No transformation testing.** SQL transforms can have bugs; tests are needed.
- **Heavy lift-and-shift of old ETL.** Often the right answer is to redesign around ELT, not port.
- **Privacy data loaded raw.** PII in the warehouse without redaction.
- **dbt without lineage tracking.** Can't tell what depends on what.

## Further Reading

- [DataPipelineDesign](DataPipelineDesign) — Pipeline patterns
- [DbtAndAnalyticsEngineering](DbtAndAnalyticsEngineering) — The transformation tool
- [DataModelingFundamentals](DataModelingFundamentals) — What you're modeling
- [CloudDatabases](CloudDatabases) — Warehouse options
- [DataEngineering Hub](DataEngineeringHub) — Cluster index
