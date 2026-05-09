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
summary: Technical analysis of Data Lakehouse architectures, detailing metadata structures in Apache Iceberg and Delta Lake that enable ACID compliance on object storage.
auto-generated: false
---

# Data Lakehouse: Transactional Big Data

A Data Lakehouse unifies the scalability and cost-efficiency of a Data Lake with the transactional reliability and performance of a Data Warehouse. It eliminates the need to move data between a lake (for ML/DS) and a warehouse (for BI) by bringing the warehouse's transactional integrity to the lake.

---

## 1. The Core Innovation: Open Table Formats

Traditional data lakes (raw Parquet/Avro files) lack ACID guarantees. If a write fails halfway, you are left with "orphan" files that corrupt subsequent reads. Lakehouses use a metadata layer (Table Format) to coordinate writes and provide a consistent view of the data.

### A. Delta Lake: The Transaction Log Approach
Delta Lake (developed by Databricks) uses a centralized **Transaction Log** (located in the `_delta_log/` directory) to track every change to the table.
*   **Metadata Structure:**
    *   **JSON Commit Files:** Every transaction generates a new JSON file (e.g., `000001.json`). This file contains actions: `add` (new data files), `remove` (deleted files), and `metaData` (schema changes).
    *   **Checkpoints (Parquet):** Every 10 commits, Delta aggregates the state into a Parquet checkpoint file. This prevents the query engine from having to replay thousands of JSON files to find the current state.
*   **ACID Mechanism:** ACID is achieved through **Optimistic Concurrency Control (OCC)**. The protocol relies on the atomicity of the underlying object store's "put-if-absent" capability for the log files. If two writers attempt to create `000002.json` simultaneously, only one succeeds; the other must rebase and retry.

### B. Apache Iceberg: The Snapshot Hierarchy
Apache Iceberg (originally from Netflix) uses a tree-based metadata structure designed for extreme scale and performance on object storage.
*   **Metadata Structure:**
    1.  **Metadata File:** The root of the table state. Points to the current **Snapshot**.
    2.  **Manifest List:** A file containing a list of **Manifest Files** for a specific snapshot. It includes statistics (min/max values) for each manifest to enable fast pruning.
    3.  **Manifest File:** A list of the actual **Data Files** (Parquet/ORC/Avro) and their partition information.
*   **ACID Mechanism:** Iceberg uses **Snapshot Isolation**. Every write creates a new set of manifest files and a new metadata root. Commits are atomic swaps of the root metadata file. Unlike Delta, Iceberg does not rely on file listing; it reads the explicit list of files in the manifests, which is significantly faster on object stores like S3.

### C. Apache Hudi: Optimized for Upserts
Designed for "Hadoop Upserts Deletes and Incrementals," Hudi is optimized for streaming data with high-frequency updates. It uses a "Timeline" to manage transactions and offers two storage types: **Copy on Write (CoW)** and **Merge on Read (MoR)**.

---

## 2. ACID Compliance on Object Storage

By using these formats, developers can perform standard database operations on S3/GCS:
*   **Atomicity:** A write operation either completes fully or fails; there are no partial writes. If a writer crashes, the data files it wrote remain "unreferenced" in the metadata and are later cleaned up by vacuuming.
*   **Consistency:** The metadata layer ensures readers only see valid, committed snapshots. Schemas are enforced strictly at the metadata level.
*   **Isolation:** Multiple concurrent writers are managed via OCC. Iceberg and Delta both support serializable isolation levels.
*   **Durability:** Data is stored in immutable Parquet/Avro files on durable object storage.

---

## 3. Performance Features

*   **Predicate Pushdown:** The metadata layer tells the query engine which files *not* to read (e.g., skip all files where `date < '2024-01-01'`), drastically reducing I/O.
*   **Time Travel:** Query the data as it existed at a specific point in history:
    `SELECT * FROM sales VERSION AS OF 15;` (Delta) or `AS OF TIMESTAMP '2024-01-01'` (Iceberg).
*   **Z-Ordering / Data Skipping:** A technique to colocate related data in the same files based on multiple dimensions, increasing query speed by maximizing the effectiveness of min/max metadata pruning.

---

## 4. The Medallion Architecture

Data is typically processed through three refined layers:
1.  **Bronze (Raw):** Direct ingestion of source data. No transformations. Retains historical history of raw data.
2.  **Silver (Filtered/Cleaned):** Joins, deduplication, and schema enforcement. This is the "Truth" layer where data is cleansed for downstream use.
3.  **Gold (Business Ready):** Aggregated, high-performance tables ready for BI and reporting. Often denormalized for specific business use cases.

---
**See Also:**
- [Data Warehouse Design](DataWarehouseDesign) — Comparative modeling.
- [Data Versioning](DataVersioning) — Managing changes in data state.
- [Normalization And Denormalization](NormalizationAndDenormalization) — Performance trade-offs.
