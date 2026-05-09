---
cluster: databases
canonical_id: 01KQ0P44PBZW9QRFV0S3W9NFA7
title: Data Warehouse Design
type: article
tags:
- databases
- data-warehouse
- dimensional-modeling
- star-schema
- snowflake-schema
status: active
date: 2025-05-15
summary: Technical analysis of data warehouse schemas. Covers Star vs Snowflake models, Fact/Dimension tables, and SCD Type 2 management.
auto-generated: false
---

# Data Warehouse Design: Dimensional Modeling

Data warehouses are optimized for high-performance analytical queries (OLAP), prioritizing read speed over write efficiency.

## 1. The Star Schema: The Performance Baseline

The Star Schema is the standard for high-performance analytics.
*   **Fact Table:** Central table containing quantitative metrics (e.g., `sales_amount`, `quantity`) and foreign keys to dimensions.
*   **Dimension Tables:** Surrounding tables containing descriptive context (e.g., `CustomerName`, `ProductCategory`).
*   **Key Advantage:** Minimizes joins. Most queries only require one level of joining between the fact and relevant dimensions.

## 2. The Snowflake Schema: The Normalization Alternative

In a Snowflake Schema, dimension tables are normalized into multiple related tables.
*   **Example:** `DimProduct` links to `DimCategory`, which links to `DimDepartment`.
*   **Pros:** Reduces data redundancy (storage efficiency).
*   **Cons:** Increases query complexity and latency due to deep join paths. **Expert Rule:** In modern cloud warehouses (Snowflake, BigQuery), storage is cheap and compute is expensive; favor the **Star Schema** to reduce CPU cycles spent on joins.

## 3. Fact Table Granularity

The "Grain" is the level of detail for a single row in the fact table.
*   **Atomic Grain:** The lowest possible level (e.g., a single line item on a receipt). 
*   **Rule:** Always store data at the atomic grain. You can aggregate up later, but you can never "drill down" into data that was aggregated during ingestion.

## 4. Slowly Changing Dimensions (SCD)

Dimensions change over time (e.g., a customer moves to a new city).
*   **SCD Type 1:** Overwrite the old value. (No history).
*   **SCD Type 2:** Add a new row with `start_date`, `end_date`, and `current_flag`. (Full history).
*   **Concrete Requirement:** Use SCD Type 2 for any attribute used in longitudinal analysis (e.g., tracking sales growth by region even when regions are reorganized).

---
**See Also:**
- [Normalization And Denormalization](NormalizationAndDenormalization) — Performance trade-offs.
- [Data Lakehouse](DataLakehouse) — Implementing dimensional models on lakes.
- [Business Intelligence Fundamentals](BusinessIntelligenceFundamentals) — Consuming the warehouse.
