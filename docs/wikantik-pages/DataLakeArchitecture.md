---
canonical_id: 01KQ0P44P8NKQJY36DAC0MM59C
title: Data Lake Architecture
type: article
cluster: data-engineering
status: active
date: '2026-05-15'
tags:
- data-lake
- medallion-architecture
- storage
- iceberg
- delta-lake
summary: Technical implementation of the Medallion Architecture (Bronze/Silver/Gold) on object storage using transactional table formats.
related:
- DataLakehouse
- DataMeshArchitecture
- ApacheSparkFundamentals
- ChangeDataCapture
hubs:
- DataSystemsHub
auto-generated: false
---
# Data Lake Architecture

A modern Data Lake is a scalable repository that stores raw data in its native format and provides a structured pipeline for refinement. The industry standard for organizing this flow is the **Medallion Architecture**, typically implemented on object storage (S3, GCS, ADLS) using transactional table formats like **Apache Iceberg**, **Delta Lake**, or **Apache Hudi**.

## The Medallion Architecture

### 1. Bronze Layer (Raw / Ingestion)
The landing zone for raw data.
- **Goal**: Ingestion fidelity. Store everything as-is.
- **Schema**: Schema-on-read. Data is often JSON, CSV, or Avro.
- **Retention**: Often immutable and permanent for audit/replayability.
- **Partitioning**: Primarily by ingestion time (e.g., `year=2024/month=05/day=20`).

### 2. Silver Layer (Cleansed / Conformed)
The foundational layer for cross-domain analysis.
- **Goal**: Data quality and consistency.
- **Actions**: Filtering nulls, type casting, deduplication, and basic normalization.
- **Format**: Transactional Parquet (Iceberg/Delta). This is where ACID properties become critical for reliable updates.
- **Schema**: Enforced.

### 3. Gold Layer (Curated / Business)
High-performance consumption layer for BI and ML.
- **Goal**: Low-latency, business-aligned views.
- **Actions**: Aggregation, joining disparate Silver tables, and applying business logic (e.g., CLV calculation).
- **Structure**: Often modeled as Star Schema (Facts and Dimensions).

## Transactional Table Formats (The "Lakehouse")
Traditional object storage lacks ACID properties. Transactional formats solve this by layering a metadata log over Parquet files.
- **ACID Compliance**: Ensures failed writes don't leave partial data.
- **Time Travel**: Access previous versions of data for debugging or rollbacks.
- **Schema Evolution**: Add/rename columns without rewriting the entire table.
- **Data Skipping**: Uses metadata (min/max stats) to skip files during query execution, drastically reducing I/O.

## Concrete Example: Partitioning and File Layout
Effective partitioning is the difference between a sub-second query and a 10-minute scan.

**Scenario**: A clickstream table with billions of events.
- **Bad Partitioning**: `/clicks/date=2024-05-20/` (One massive folder with 10,000 small files).
- **Good Partitioning**: `/clicks/event_type/date=2024-05-20/` (Divided by frequent filter criteria).

**Iceberg Partition Evolution Example**:
In Iceberg, you can change the partitioning strategy without breaking old queries.
```sql
-- Create a table with daily partitioning
CREATE TABLE clickstream (
    user_id bigint,
    event_time timestamp,
    event_type string
)
USING iceberg
PARTITIONED BY (days(event_time));

-- Later, realize you need to partition by event_type too
ALTER TABLE clickstream SET IDENTIFIER FIELDS event_type;
ALTER TABLE clickstream REPLACE PARTITION FIELD days(event_time) WITH event_type;
```

## Storage Optimization Strategies
1. **Compaction (Small File Problem)**: Frequently merging small files (e.g., 100x 1MB files) into a single large Parquet file (100MB) to reduce metadata overhead and improve scan speed.
2. **Z-Ordering / Clustering**: Co-locating related data within files. For example, clustering by `user_id` within a time partition speeds up point-lookups for specific users.
3. **Cold Storage Tiering**: Moving older partitions (e.g., > 2 years) to cheaper storage tiers (S3 Glacier) while keeping metadata available for discovery.

## Summary of Technical implementation added
- Defined the **Medallion Architecture** (Bronze, Silver, Gold) with specific technical goals for each layer.
- Explained the role of **transactional table formats** (Iceberg/Delta) in enabling the "Lakehouse" pattern.
- Provided a concrete SQL example of **Iceberg Partition Evolution**.
- Detailed storage optimizations like **Compaction** and **Z-Ordering**.
- Removed all "AI-sounding" conversational padding.
