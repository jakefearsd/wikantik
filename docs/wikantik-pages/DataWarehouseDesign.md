---
auto-generated: false
status: active
type: article
hubs:
- DimensionalModelingHub
date: 2026-05-20T00:00:00Z
cluster: data-engineering
title: Data Warehouse Design
tags:
- data-warehouse
- star-schema
- dimensional-modeling
- data-engineering
summary: Level 2 of the Data Maturity Lifecycle. Detailed analysis of Star Schema
  rigidity and the transition from manual silos to centralized analytical engines.
canonical_id: 01KVJMS10CXAQ9CASZNKR7M010
---

# Data Warehouse Design: Level 2 Maturity

In the [Data Maturity Lifecycle](DataMaturityLifecycle), Level 2 represents the transition from fragmented spreadsheets to a **Centralized Warehouse**. This stage is characterized by **Schema-on-Write** and high-performance SQL analytics.

## 1. The Centralization Mandate
The primary goal of Level 2 is to create a "Single Source of Truth." Data is extracted from operational RDBMS (MySQL, Postgres) via ETL and loaded into a specialized analytical engine (Snowflake, BigQuery, Redshift).

## 2. Dimensional Modeling: The Star Schema
Analytical performance in a warehouse relies on **Dimensional Modeling**.
*   **Fact Table:** Contains quantitative metrics (e.g., `revenue`, `quantity`) and keys to dimensions.
*   **Dimension Tables:** Contain descriptive attributes (e.g., `CustomerName`, `Region`).

### Concrete Example: The Fact Table Grain
The "Grain" is the most critical decision in warehouse design.
```sql
-- Example: Defining the grain at the Line-Item level
CREATE TABLE fact_sales (
    order_id UUID,
    product_id INT,
    customer_id INT,
    date_id INT,
    quantity INT,
    sale_price DECIMAL(10,2),
    -- Foreign keys to dimensions
    CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES dim_customer(customer_id)
);
```
**Rule:** Always store data at the **Atomic Grain**. Aggregating during load (e.g., "Daily Sales") is a Level 1 behavior that prevents future drill-down analysis.

## 3. The Performance vs. Rigidity Trade-off
While the **Star Schema** is optimized for read performance by minimizing joins, it introduces **Rigidity**.
- **Schema Evolution:** Adding a new dimension often requires re-processing historical data.
- **Normalization Limits:** The **Snowflake Schema** (normalizing dimensions) reduces storage but dramatically increases query latency. In modern cloud warehouses, storage is cheap; favor the Star Schema.

## 4. Slowly Changing Dimensions (SCD)
To maintain historical accuracy, warehouses use SCD patterns.
- **SCD Type 2:** Adds new rows with versioning.
```sql
-- Querying SCD Type 2 for point-in-time accuracy
SELECT 
    s.sale_price,
    c.customer_city
FROM fact_sales s
JOIN dim_customer c ON s.customer_id = c.customer_id
WHERE s.sale_date BETWEEN c.row_start_date AND c.row_end_date;
```

## 5. The Level 2 Bottleneck
As maturity increases, the centralized warehouse becomes a bottleneck.
- **Wait Times:** Central data teams are overwhelmed.
- **Cost:** Scaling compute for massive raw datasets in a warehouse is expensive.
- **Solution:** Transition to Level 3, the [Data Lake Architecture](DataLakeArchitecture).

---
**See Also:**
- [Normalization And Denormalization](NormalizationAndDenormalization) — Performance trade-offs.
- [Data Lakehouse](DataLakehouse) — Implementing dimensional models on lakes.
- [Business Intelligence Fundamentals](BusinessIntelligenceFundamentals) — Consuming the warehouse.
---
