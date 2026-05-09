---
cluster: databases
canonical_id: 01KQ0P44PP2JT1AGY6TM70J6FR
title: Dimensional Modeling
type: article
tags:
- data-warehousing
- dimensional-modeling
- star-schema
- obt
- snowflake
- bigquery
summary: Technical guide to dimensional modeling, contrasting the traditional Kimball Star Schema with modern OBT (One Big Table) patterns in columnar warehouses like Snowflake and BigQuery.
auto-generated: false
date: 2025-05-15
---

# Dimensional Modeling: From Star Schema to OBT

Dimensional modeling is a design technique for data warehouses intended to optimize query performance and usability for business intelligence. While the traditional Kimball Star Schema remains foundational, the rise of modern columnar warehouses like Snowflake and BigQuery has popularized a different pattern: **One Big Table (OBT)**.

---

## 1. The Fact Table

The fact table contains the quantitative metrics or "facts" of a business process. Each row represents a specific event or measurement.

### 1.1 Grain
The grain is the fundamental definition of what a single row in the fact table represents (e.g., "one line item per sales transaction"). Establishing a clear grain is the first step in dimensional design, as it dictates the level of detail available for analysis and ensures consistent aggregation.

### 1.2 Fact Table Types
*   **Transaction Fact Tables:** Record events as they occur (e.g., a single retail sale).
*   **Periodic Snapshot Fact Tables:** Record the state of a process at regular intervals (e.g., end-of-month inventory levels).
*   **Accumulating Snapshot Fact Tables:** Track the progress of a process with defined milestones (e.g., an order fulfillment lifecycle from submission to delivery).

### 1.3 Measures and Keys
Fact tables consist of foreign keys that link to dimension tables and numeric measures. Measures are usually additive (can be summed across all dimensions), semi-additive (can be summed across some dimensions but not others), or non-additive (ratios).

---

## 2. Dimension Tables

Dimension tables provide the descriptive context for the facts. They answer the "who, what, where, when, and why" of the business process.

### 2.1 Surrogate Keys
A surrogate key is an internally generated, unique identifier (usually an integer) used as the primary key for a dimension table. It decouples the data warehouse from changes in the source system's natural keys and enables historical tracking.

### 2.2 Hierarchies
Dimensions often contain hierarchies, such as a Product dimension with Category and Sub-category levels, or a Date dimension with Year, Quarter, and Month levels.

---

## 3. Slowly Changing Dimensions (SCD)

SCD techniques manage how the warehouse handles changes to dimension attributes over time.

*   **SCD Type 1 (Overwrite):** The new value overwrites the old value. No history is maintained.
*   **SCD Type 2 (Add New Row):** A new record is created with a new surrogate key. This is the most common method for maintaining full historical accuracy.
*   **SCD Type 3 (Add New Column):** Only the current and immediate previous states are tracked.
*   **SCD Type 4 (History Table):** Current values in the main table, all historical changes in a separate table.

---

## 4. Schema Geometry: Star Schema vs. OBT

The architecture of your model depends heavily on the underlying database engine and the consumption patterns of your users.

### 4.1 Star Schema (The Kimball Classic)
In a star schema, the fact table is surrounded by a single layer of dimension tables.
*   **Advantages:** 
    *   **Storage Efficiency:** Data is normalized (mostly), reducing redundancy.
    *   **Logical Clarity:** Easy for business users to understand the relationship between facts and dimensions.
    *   **Flexibility:** Dimensions can be reused (conformed) across multiple fact tables.
*   **Constraint:** Requires joins at query time. In legacy row-based databases, this was the optimal balance.

### 4.2 One Big Table (OBT) / Wide Tables
OBT is a fully denormalized model where every dimension attribute is flattened directly into the fact table.
*   **The Columnar Revolution:** Modern warehouses (Snowflake, BigQuery, ClickHouse) are **columnar**. They store each column separately and use aggressive compression (Run-Length Encoding, Dictionary Encoding).
*   **Advantages:**
    *   **Maximum Performance:** No joins means no shuffle or broadcast operations during query execution. Data is simply scanned and aggregated.
    *   **User Simplicity:** End-users (or BI tools) query a single table without needing to understand complex join logic.
    *   **Storage Paradox:** While OBT has massive redundancy (e.g., repeating the "Customer Region" string millions of times), columnar compression makes the storage footprint almost identical to a Star Schema.
*   **Disadvantages:**
    *   **Update Complexity:** Updating a single dimension attribute (e.g., changing a Category name) requires rewriting or updating the entire OBT, which can be computationally expensive.
    *   **Lack of Conformation:** It is harder to ensure that "Product Name" is identical across three different OBTs compared to a single conformed dimension table.

### 4.3 Summary Comparison

| Feature | Star Schema | One Big Table (OBT) |
| :--- | :--- | :--- |
| **Normalization** | Partially Normalized | Fully Denormalized |
| **Join Cost** | High (Shuffle/Broadcast) | Zero |
| **Storage Usage** | Low | High (Mitigated by Columnar Compression) |
| **Maintenance** | Easy (Update Dim Table) | Hard (Full Table Re-materialization) |
| **Best Engine** | Traditional RDBMS (Postgres, Oracle) | Columnar Warehouses (Snowflake, BQ) |

---

## 5. Advanced Patterns

### 5.1 Junk Dimensions
Consolidates disparate, low-cardinality attributes like flags and status codes into a single table.

### 5.2 Bridge Tables
Used to handle many-to-many relationships.

### 5.3 Fact Constellation
Multiple fact tables sharing common dimension tables.

---

## 6. Implementation Considerations

*   **Surrogate Key Generation:** Usually handled during the ETL process.
*   **Null Handling:** Dimension keys should never be null; point to "Unknown" records.
*   **Modern Strategy:** Most modern teams use **dbt (data build tool)** to maintain a normalized Star Schema as their "Core" or "Silver" layer, and then programmatically generate OBT "Gold" views or tables for BI consumption. This provides the best of both worlds: maintainability and performance.

---
**See Also:**
- [Data Warehouse Design](DataWarehouseDesign) — Comparative modeling.
- [Data Lakehouse](DataLakehouse) — Transactional Big Data.
- [Normalization And Denormalization](NormalizationAndDenormalization) — Performance trade-offs.
