---
cluster: databases
canonical_id: 01KQ0P44P8Y3AJNGEFQ9WZR6DQ
title: Data Lakehouse
type: article
tags:
- databases
- data-lakehouse
- big-data
- delta-lake
- apache-iceberg
status: active
date: 2025-05-15
summary: Technical analysis of Data Lakehouse architectures. Covers open table formats (Iceberg/Delta), ACID compliance on object storage, and time travel.
auto-generated: false
---

# Data Lakehouse: Transactional Big Data

A Data Lakehouse unifies the scalability and cost-efficiency of a Data Lake with the transactional reliability and performance of a Data Warehouse.

## 1. The Core Innovation: Open Table Formats

Traditional data lakes (raw Parquet/Avro files) lack ACID guarantees. Lakehouses use a metadata layer (Table Format) to coordinate writes.
*   **Delta Lake:** Uses a transaction log (JSON) alongside Parquet files. Developed by Databricks.
*   **Apache Iceberg:** Uses manifest files to define snapshots. Designed for petabyte-scale performance.
*   **Apache Hudi:** Optimized for streaming and incremental updates (Upserts).

## 2. ACID Compliance on Object Storage

By using these formats, developers can perform standard database operations on S3/GCS:
*   **Atomicity:** A write operation either completes fully or fails; there are no partial writes.
*   **Consistency:** The metadata layer ensures readers only see valid, committed snapshots.
*   **Isolation:** Multiple concurrent writers are managed via **Optimistic Concurrency Control (OCC)**.

## 3. Performance Features

*   **Predicate Pushdown:** The metadata layer tells the query engine which files *not* to read (e.g., skip all files where `date < '2024-01-01'`), drastically reducing I/O.
*   **Time Travel:** Query the data as it existed at a specific point in history:
    `SELECT * FROM sales VERSION AS OF 15;`
*   **Z-Ordering:** A technique to colocate related data in the same files, increasing query speed for multi-dimensional filters.

## 4. The Medallion Architecture

Data is typically processed through three refined layers:
1.  **Bronze (Raw):** Direct ingestion of source data. No transformations.
2.  **Silver (Filtered/Cleaned):** Joins, deduplication, and schema enforcement.
3.  **Gold (Business Ready):** Aggregated, high-performance tables ready for BI and reporting.

---
**See Also:**
- [Data Warehouse Design](DataWarehouseDesign) — Comparative modeling.
- [Data Versioning](DataVersioning) — Managing changes in data state.
- [Normalization And Denormalization](NormalizationAndDenormalization) — Performance trade-offs.
