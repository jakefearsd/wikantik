---
cluster: data-engineering
canonical_id: 01KQ0P44MSD0WDREXDH3WM49Z3
title: Business Intelligence Fundamentals
type: article
tags:
- data-modeling
- bi
- snowflake
- dbt
summary: Technical analysis of Star Schema vs. OBT architectures and the implementation of the Semantic Layer in modern BI stacks.
auto-generated: false
---

# Business Intelligence: Architectural Trade-offs and the Semantic Layer

Modern Business Intelligence (BI) has shifted from traditional ETL/OLAP cubes to ELT workflows on cloud data warehouses. The core architectural decision for a data team is the choice between Kimball-style dimensional modeling and the One Big Table (OBT) approach, managed via a unified Semantic Layer.

## 1. Modeling Architectures: Star Schema vs. OBT

### Star Schema (Kimball Methodology)
The Star Schema organizes data into central **Fact tables** (quantitative events) and surrounding **Dimension tables** (descriptive attributes).
*   **Pros:** Minimal data redundancy, maintains clear relationships between entities, and is highly intuitive for users exploring data via SQL.
*   **Cons:** Requires complex JOIN operations. In modern distributed systems (e.g., Spark or Snowflake), large-scale joins can lead to "data shuffling" across nodes, which increases latency and compute costs.

### One Big Table (OBT)
OBT involves denormalizing dimensions directly into the fact table to create a single, wide dataset.
*   **Pros:** Eliminates JOINs entirely, making it highly performant for columnar storage engines that optimize for wide-table scans. Most modern BI tools (Sigma, ThoughtSpot) and cloud warehouses (BigQuery) perform significantly better with OBT.
*   **Cons:** Extreme data redundancy (e.g., a customer's address is repeated for every transaction). It makes maintaining a "single source of truth" difficult without a robust transformation layer (like dbt).

**Technical Recommendation:** Use dbt to maintain a normalized Star Schema in the `marts` layer of your warehouse, then generate OBT views specifically for high-performance BI tool consumption.

## 2. The Semantic Layer: Metrics as Code

The **Semantic Layer** is the abstraction between the physical data models (Star/OBT) and the end-user. It provides a consistent definition for metrics across the entire organization.

### Core Components
*   **Dimensions:** Attributes like `Region`, `Product Category`, or `Fiscal Quarter`.
*   **Measures:** Aggregations like `SUM(Revenue)` or `COUNT(DISTINCT user_id)`.
*   **Metrics:** Business definitions built on measures, such as `MRR` (Monthly Recurring Revenue) or `CAC` (Customer Acquisition Cost).

### Modern Tooling
*   **dbt Semantic Layer:** Uses MetricFlow to define metrics in YAML, allowing them to be queried via a GraphQL or SQL API.
*   **Looker (LookML):** A mature, code-based semantic layer that forces all queries to go through a central definition.
*   **Cube.js:** A headless BI platform that provides a semantic layer and caching for applications.

## 3. The Modern Data Stack (MDS) Workflow

A standard, high-density BI workflow follows this pattern:
1.  **Ingestion (Fivetran/Airbyte):** Extracting raw data from SaaS apps and DBs into the warehouse.
2.  **Storage (Snowflake/BigQuery/Databricks):** The centralized repository for raw and transformed data.
3.  **Transformation (dbt):** Using SQL to clean, join, and model data into Star Schemas or OBTs.
4.  **Semantic Layer:** Defining the business logic (e.g., "What constitutes a 'Churned' customer?") in code.
5.  **Visualization (Tableau/Looker/Sigma):** Consuming the semantic layer to produce dashboards and exploratory reports.

## 4. Performance Optimization Techniques
*   **Materialized Views:** Pre-computing complex joins and aggregations to reduce query time.
*   **Partitioning/Clustering:** Organizing data on disk by keys (e.g., `event_date`) to minimize the amount of data scanned per query.
*   **Caching Layers:** Implementing a BI proxy (like Cube or AtScale) to serve frequent queries from memory rather than re-scanning the warehouse.
