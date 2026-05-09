---
title: Data Lake Architecture
type: article
cluster: data-engineering
status: active
date: 2026-05-20
summary: Level 3 of the Data Maturity Lifecycle. Focus on storage/compute decoupling and the Medallion Architecture (Bronze/Silver/Gold).
auto-generated: false
---

# Data Lake Architecture: Level 3 Maturity

In Level 3 of the [Data Maturity Lifecycle](DataMaturityLifecycle), organizations decouple storage from compute. By moving data into a **Data Lake** (S3, GCS, Azure Blob), they achieve infinite scalability and the ability to store raw, unstructured data.

## 1. The "Data Swamp" Failure Mode
Without a structural framework, a Data Lake quickly becomes a "Data Swamp"—a collection of unidentifiable files with no schema, no ownership, and no quality guarantees. Level 3 maturity is defined by the implementation of the **Medallion Architecture**.

## 2. The Medallion Architecture (Bronze/Silver/Gold)

### Bronze (Raw)
- **State:** Ingestion fidelity.
- **Goal:** Capture source data as-is (JSON, Avro, Parquet).
- **Structure:** Partitioned by ingestion date (e.g., `s3://bucket/bronze/orders/year=2026/month=05/`).

### Silver (Cleansed)
- **State:** Conformed and validated.
- **Goal:** Apply schema enforcement, filter nulls, and deduplicate.
- **Structure:** Usually stored as Parquet with defined types.

### Gold (Curated)
- **State:** Business-ready aggregations.
- **Goal:** High-performance tables for BI/ML.
- **Structure:** Modeled for specific use cases (e.g., `s3://bucket/gold/monthly_revenue/`).

## 3. Concrete Example: Pipeline Implementation
Using Apache Spark to move data from Bronze to Silver:

```python
# Spark logic for Bronze to Silver transition
df_raw = spark.read.json("s3://bronze/orders/2026/05/*")

# Cleaning: Cast types and filter invalid orders
df_cleansed = df_raw.select(
    col("order_id").cast("string"),
    col("amount").cast("double"),
    to_timestamp(col("ts")).alias("event_time")
).filter(col("amount") > 0).dropDuplicates(["order_id"])

# Write to Silver as Parquet
df_cleansed.write.partitionBy("event_date") \
    .mode("overwrite") \
    .parquet("s3://silver/orders/")
```

## 4. The Transition to Level 4
Level 3 lakes are still "append-only" and lack ACID transactions. Updating a single row requires rewriting an entire partition. To solve this, organizations move to Level 4, the [Data Lakehouse](DataLakehouse).

---
**See Also:**
- [Data Warehouse Design](DataWarehouseDesign) — The predecessor to lakes.
- [Apache Spark Fundamentals](ApacheSparkFundamentals) — Processing lake data.
- [Data Lakehouse](DataLakehouse) — Bringing ACID to the lake.
---
