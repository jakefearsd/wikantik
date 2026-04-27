---
canonical_id: 01KQ0P44P9WDGC8990M3AQS80V
title: Data Modeling Fundamentals
type: article
cluster: data-engineering
status: active
date: '2026-04-26'
summary: The basics of data modeling — fact and dimension tables, star and snowflake
  schemas, normalization vs. denormalization, and the choices for OLTP vs. OLAP workloads.
tags:
- data-modeling
- star-schema
- dimensional-modeling
- olap
- oltp
related:
- EtlVsElt
- DbtAndAnalyticsEngineering
- DataPipelineDesign
- JpaAndHibernatePatterns
hubs:
- DataEngineering Hub
---
# Data Modeling Fundamentals

Data modeling is the practice of organizing data for storage and access. The fundamentals — fact tables, dimensions, normalization, denormalization — have been stable for decades. The implementations evolve; the concepts don't.

This page covers the core concepts and the choices for different workloads.

## OLTP vs. OLAP

The fundamental distinction:

### OLTP (Online Transaction Processing)

- Operational systems: orders, users, sessions
- Many small reads and writes
- Strong consistency
- Highly normalized: minimize redundancy

The order is created once; read once or twice; updated occasionally. Data lives in a relational database (PostgreSQL, MySQL).

### OLAP (Online Analytical Processing)

- Analytics: revenue analysis, user cohorts, reporting
- Few large reads
- Aggregations across millions of rows
- Highly denormalized: optimize for read performance

The data is read many times in different aggregations. Data lives in a warehouse (Snowflake, BigQuery, Redshift).

The two have different shapes. OLTP normalizes; OLAP denormalizes.

## Normalization

Splitting data across tables to eliminate redundancy.

### 1NF, 2NF, 3NF

Database normalization rules:

- **1NF**: atomic values (no arrays in cells)
- **2NF**: 1NF + non-key columns depend on the whole key
- **3NF**: 2NF + non-key columns depend only on the key

Most OLTP databases are in 3NF (or close to it).

### Why normalize OLTP

- **Integrity**: update once; consistent everywhere
- **Storage**: less redundancy
- **Updates**: clean

### Why denormalize OLAP

- **Performance**: aggregations across large tables benefit from co-located data
- **Simplicity**: fewer joins for common queries
- **Read-only**: don't need update integrity

## Star schema

The classic OLAP pattern:

### Fact tables

Numerical measurements. Each row is an event or transaction.

```
fct_orders
├── order_id (key)
├── customer_id (FK to dim_customer)
├── product_id (FK to dim_product)
├── date_id (FK to dim_date)
├── amount (measure)
└── quantity (measure)
```

Columns are foreign keys to dimensions plus measures (the numbers).

### Dimension tables

Descriptive context.

```
dim_customer
├── customer_id (key)
├── name
├── email
├── signup_date
├── tier
└── city

dim_product
├── product_id (key)
├── name
├── category
├── brand
└── price
```

The "star" comes from the diagram: fact in center, dimensions around it.

### Why it works

- Joins are simple (always fact → dimensions)
- Aggregations are fast
- Dimensions are denormalized (one row per customer with all attributes)
- Fact tables can be enormous; dimensions stay manageable

## Snowflake schema

Like star, but dimensions are normalized further.

```
dim_customer → dim_city → dim_country
```

Pros: less storage; cleaner.
Cons: more joins.

For modern warehouses (cheap storage, fast joins), star usually wins. Snowflake schemas show up in older OLAP designs.

## Slowly changing dimensions (SCD)

Dimensions change over time. A customer's tier upgrades. Their address changes.

How to handle the change:

### Type 1: overwrite

Just update. No history.

### Type 2: add new row

Old row marked end-of-life; new row created with new values. History preserved.

```
customer_id | name | tier | valid_from | valid_to | is_current
1           | Alice | bronze | 2020-01-01 | 2022-06-01 | false
1           | Alice | gold   | 2022-06-01 | (null)    | true
```

For analytics that care about historical state, Type 2 is essential. "What tier was Alice when she made this purchase?"

### Type 3: separate column for previous value

Limited history (only the previous state).

For most warehouses, Type 2 is the default for dimensions where history matters.

## Wide vs. tall

A choice for fact tables:

### Wide (one column per measurement)

```
date | revenue | costs | profit | margin
```

Easy queries; many columns; harder to add new measurements.

### Tall (one row per measurement)

```
date | metric | value
```

Easy to extend; harder for some queries (need pivots).

For operational metrics, tall is flexible. For business KPIs with stable structure, wide is clearer.

## Modern adjustments

Cloud warehouses change some traditional advice:

### Storage is cheap

Denormalize aggressively. Don't pre-aggregate to save space; let warehouses store the detail.

### Joins are fast

Normalization concerns matter less. Star schemas in modern warehouses join quickly.

### Semi-structured data

JSON columns in PostgreSQL, BigQuery, Snowflake. Sometimes the right answer; often a sign of underbaked modeling.

### dbt-style layers

The staging-intermediate-marts pattern. Each layer applies more business logic; all transformations are SQL.

## When to model up front vs. iteratively

### Up front

For mature business domains where you understand the entities. Designing OLTP schemas for a known domain.

### Iteratively

For analytics where requirements emerge. Start with raw data; build dimensions and facts as needed.

For warehouses, iterative usually wins. The transformation layer in dbt makes restructuring relatively cheap.

## Common failure patterns

- **OLTP schema for OLAP queries.** Slow aggregations.
- **Pre-aggregating in OLTP.** Loses detail; doesn't scale to new questions.
- **JSON for everything.** Hard to query; inefficient at scale.
- **No SCD strategy.** Lose history; can't answer "what was the value at this time."
- **One big wide table.** Hard to update; query patterns get complex.
- **No documentation.** Future engineers don't know what tables represent.

## Further Reading

- [EtlVsElt](EtlVsElt) — How transformations land in models
- [DbtAndAnalyticsEngineering](DbtAndAnalyticsEngineering) — Tool for modeling
- [DataPipelineDesign](DataPipelineDesign) — Pipelines that build models
- [JpaAndHibernatePatterns](JpaAndHibernatePatterns) — OLTP modeling perspective
- [DataEngineering Hub](DataEngineering+Hub) — Cluster index
