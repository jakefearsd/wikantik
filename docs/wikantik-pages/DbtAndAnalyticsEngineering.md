---
canonical_id: 01KQ0P44PFK6PNPFVF9E9ZV4V5
title: dbt and Analytics Engineering
type: article
cluster: data-engineering
status: active
date: '2026-04-26'
summary: How dbt works as a transformation tool, what "analytics engineering" actually
  means, and the patterns for building maintainable warehouse transformations.
tags:
- dbt
- analytics-engineering
- data-warehouse
- sql
- transformations
related:
- EtlVsElt
- DataPipelineDesign
- DataModelingFundamentals
- CleanCodePrinciples
hubs:
- DataEngineeringHub
---
# dbt and Analytics Engineering

dbt (data build tool) is the dominant tool for warehouse-resident transformations. Combined with the rise of cloud data warehouses, dbt enabled a shift in how data work is done — and a new role, "analytics engineering," which sits between data engineering and data analysis.

This page covers how dbt works and what analytics engineering actually means.

## What dbt does

dbt is a tool for transforming data inside a warehouse using SQL.

The core idea: write SQL SELECT statements that define transformations; dbt executes them in dependency order; the result is materialized tables/views in the warehouse.

```sql
-- models/staging/stg_orders.sql
SELECT
    id AS order_id,
    customer_id,
    amount,
    status,
    created_at
FROM {{ source('raw', 'orders') }}
```

The `{{ source(...) }}` is dbt's Jinja templating. dbt resolves it to the actual table name. Models reference each other via `ref()`:

```sql
-- models/marts/customer_lifetime_value.sql
SELECT
    customer_id,
    SUM(amount) AS lifetime_value
FROM {{ ref('stg_orders') }}
WHERE status = 'completed'
GROUP BY customer_id
```

dbt builds a DAG of dependencies. Run `dbt run` and it executes models in order.

## The analytics engineering role

The role that emerged with dbt:

### The traditional split

- **Data engineers**: pipelines, infrastructure, performance
- **Data analysts / scientists**: SQL queries, dashboards, insights

### The gap

Between raw data and analyst-ready data, transformations are needed. Traditionally, data engineers built these in code (Python, Spark) or analysts did ad-hoc SQL. Neither was great.

### Analytics engineering

A new role that:
- Owns the transformation layer (using dbt)
- Bridges data engineering and analysis
- Software engineering practices applied to SQL: testing, version control, code review, documentation
- Result: trustworthy data marts that analysts can use

dbt is the tool; analytics engineering is the practice.

## dbt features that matter

### `ref()` for dependencies

Models reference each other. dbt builds the DAG; runs in correct order.

### Tests

```yaml
models:
  - name: stg_orders
    columns:
      - name: order_id
        tests:
          - unique
          - not_null
```

Run `dbt test` — assertions on the data. Catches regressions, broken assumptions, schema drift.

### Documentation

Every model can have description; columns can have descriptions and tests. `dbt docs generate` produces a website.

### Macros

Reusable SQL snippets. For common patterns (date casting, type coercion, etc.).

### Incremental models

For large tables, only process new rows:

```sql
{{ config(materialized='incremental', unique_key='id') }}

SELECT * FROM {{ ref('source') }}
{% if is_incremental() %}
WHERE created_at > (SELECT MAX(created_at) FROM {{ this }})
{% endif %}
```

Speeds up runs dramatically for append-mostly tables.

### Sources

```yaml
sources:
  - name: raw
    schema: raw_data
    tables:
      - name: orders
        loaded_at_field: _ingested_at
        freshness:
          warn_after: { count: 12, period: hour }
```

Source freshness checks; lineage from external systems.

## Patterns

### Three-layer model structure

```
sources → staging → intermediate → marts
```

- **Sources**: raw data
- **Staging** (`stg_*`): one model per source table; cleaning, naming
- **Intermediate** (`int_*`): business logic, joins
- **Marts** (`fct_*`, `dim_*`): final shape for analysts

The standardized structure makes large dbt projects manageable.

### Tests as CI

Run `dbt test` in CI. Tests must pass before merge. Catches data issues before they reach production.

### Version control

dbt projects live in git like any code. Branch, PR, review. The same engineering rigor as application code.

### Documentation as code

Documentation lives next to models. Stays current.

## When dbt is the right tool

- Warehouse-resident transformations (BigQuery, Snowflake, Redshift, etc.)
- SQL-friendly transformations
- Team that values software engineering practices for data

## When dbt isn't enough

- Streaming transformations (use Flink, Spark Streaming)
- Heavy ML preprocessing (use Spark, Python)
- Source-side transformations (use ingestion tools)

dbt is for "transform inside the warehouse." Other transformations need other tools.

## Common failure patterns

- **No tests.** Models break silently when source data changes.
- **No structure.** All models in one folder; nobody can navigate.
- **Heavy logic in marts without intermediate.** Hard to debug; hard to reuse.
- **Macros where SQL would do.** Premature abstraction.
- **No documentation.** Other people can't use the data.
- **Heavy non-incremental models.** Slow runs that could be incremental.

## Further Reading

- [EtlVsElt](EtlVsElt) — Why dbt fits the modern stack
- [DataPipelineDesign](DataPipelineDesign) — Where dbt sits in pipelines
- [DataModelingFundamentals](DataModelingFundamentals) — What you're modeling
- [CleanCodePrinciples](CleanCodePrinciples) — Apply to SQL too
- [DataEngineering Hub](DataEngineeringHub) — Cluster index
