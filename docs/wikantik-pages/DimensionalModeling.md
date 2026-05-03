---
cluster: databases
canonical_id: 01KQ0P44PP2JT1AGY6TM70J6FR
title: Dimensional Modeling
type: article
tags:
- data-warehousing
- dimensional-modeling
- star-schema
- business-intelligence
- etl
summary: Technical guide to dimensional modeling for data warehousing, covering fact tables, dimensions, and slowly changing dimensions.
auto-generated: false
---

# Dimensional Modeling

Dimensional modeling is a design technique for data warehouses intended to optimize query performance and usability for business intelligence. It organizes data into two primary table types: Fact tables and Dimension tables.

## 1. The Fact Table

The fact table contains the quantitative metrics or "facts" of a business process. Each row represents a specific event or measurement.

### 1.1 Grain
The grain is the fundamental definition of what a single row in the fact table represents (e.g., "one line item per sales transaction"). Establishing a clear grain is the first step in dimensional design, as it dictates the level of detail available for analysis and ensures consistent aggregation.

### 1.2 Fact Table Types
*   **Transaction Fact Tables:** Record events as they occur (e.g., a single retail sale).
*   **Periodic Snapshot Fact Tables:** Record the state of a process at regular intervals (e.g., end-of-month inventory levels).
*   **Accumulating Snapshot Fact Tables:** Track the progress of a process with defined milestones (e.g., an order fulfillment lifecycle from submission to delivery).

### 1.3 Measures and Keys
Fact tables consist of foreign keys that link to dimension tables and numeric measures. Measures are usually additive (can be summed across all dimensions), semi-additive (can be summed across some dimensions but not others, like account balances over time), or non-additive (ratios).

## 2. Dimension Tables

Dimension tables provide the descriptive context for the facts. They answer the "who, what, where, when, and why" of the business process.

### 2.1 Surrogate Keys
A surrogate key is an internally generated, unique identifier (usually an integer) used as the primary key for a dimension table. It decouples the data warehouse from changes in the source system's natural keys and enables historical tracking.

### 2.2 Hierarchies
Dimensions often contain hierarchies, such as a Product dimension with Category and Sub-category levels, or a Date dimension with Year, Quarter, and Month levels.

## 3. Slowly Changing Dimensions (SCD)

SCD techniques manage how the warehouse handles changes to dimension attributes over time.

*   **SCD Type 1 (Overwrite):** The new value overwrites the old value. No history is maintained.
*   **SCD Type 2 (Add New Row):** A new record is created with a new surrogate key. This is the most common method for maintaining full historical accuracy. It requires `start_date`, `end_date`, and `current_flag` columns.
*   **SCD Type 3 (Add New Column):** The old value is moved to a "previous" column, and the new value is stored in the primary column. Only the current and immediate previous states are tracked.
*   **SCD Type 4 (History Table):** The dimension table stores only current values (Type 1), while all historical changes are moved to a separate history table.

## 4. Schema Geometry

### 4.1 Star Schema
In a star schema, the fact table is surrounded by a single layer of dimension tables. This design minimizes joins and is highly efficient for columnar databases and OLAP engines.

### 4.2 Snowflake Schema
A snowflake schema normalizes dimensions into multiple related tables (e.g., splitting a Product dimension into Product and Category tables). While this reduces data redundancy, it increases query complexity and the number of joins required.

## 5. Advanced Patterns

### 5.1 Junk Dimensions
A junk dimension consolidates disparate, low-cardinality attributes like flags and status codes into a single table. This prevents the fact table from being cluttered with numerous foreign keys for minor indicators.

### 5.2 Bridge Tables
Bridge tables are used to handle many-to-many relationships, such as a single bank account having multiple owners or a student being enrolled in multiple courses simultaneously.

### 5.3 Fact Constellation
A fact constellation (or galaxy schema) consists of multiple fact tables sharing common dimension tables. This allows for cross-functional analysis (e.g., comparing Sales facts and Inventory facts using the same Date and Product dimensions).

## 6. Implementation Considerations

*   **Surrogate Key Generation:** Usually handled during the ETL (Extract, Transform, Load) process.
*   **Null Handling:** Dimension keys in the fact table should never be null. Instead, they should point to a "Not Applicable" or "Unknown" record in the dimension table.
*   **Indexing:** Fact table foreign keys and dimension table primary keys should be indexed to optimize join performance, particularly in row-based relational databases.
