---
canonical_id: 01KQEKGD99PGM6JS8ZFR8P8H7V
title: Data Observability
type: article
cluster: data-engineering
status: active
date: '2026-05-24'
tags:
- data-observability
- data-quality
- monitoring
- lineage
- sql
summary: Engineering discipline for monitoring data quality and pipeline health, focusing on the "Five Pillars" of observability and automated drift detection.
auto-generated: false
---
# Data Observability

Software observability asks "Is the server up?" Data observability asks "Is the data correct?" In a modern data stack, a pipeline can be 100% "healthy" according to your orchestrator while delivering 100% "garbage" data to your downstream dashboards.

## The Five Pillars of Data Observability

1. **Freshness:** Is the data up to date? (e.g., Has the `daily_sales` table been updated in the last 24 hours?)
2. **Distribution:** Are the values within expected ranges? (e.g., Is the `null_rate` for `user_email` suddenly 50%?)
3. **Volume:** Did we get the expected number of rows? (e.g., A 90% drop in ingestion volume indicates a source-system failure.)
4. **Schema:** Did a upstream producer change a column name or type without notice?
5. **Lineage:** When a metric breaks, which upstream table caused it?

## Implementing Automated Quality Checks

Do not rely on manual audits. Use **dbt tests** or **Great Expectations** to enforce quality at the pipeline level.

### Example: dbt Data Test
```yaml
# schema.yml
version: 2
models:
  - name: orders
    columns:
      - name: order_id
        tests:
          - unique
          - not_null
      - name: status
        tests:
          - accepted_values:
              values: ['placed', 'shipped', 'completed', 'returned']
```

## Drift Detection with SQL

You can implement basic observability using simple SQL checks run by your orchestrator (Airflow/Dagster).

```sql
-- Check for Volume Anomaly (Comparing today vs. 7-day average)
WITH stats AS (
    SELECT count(*) as row_count
    FROM events
    WHERE event_date > current_date - interval '7 days'
)
SELECT 
    count(*) as today_count,
    (SELECT row_count / 7 FROM stats) as avg_count
FROM events
WHERE event_date = current_date
HAVING count(*) < (SELECT row_count / 7 FROM stats) * 0.5; -- Alert if < 50% of average
```

## Lineage: The Impact Analysis Tool

Lineage is a directed acyclic graph (DAG) of your data's journey. 
- **Upstream Lineage:** "My dashboard is wrong. Which table fed it?"
- **Downstream Lineage:** "I want to delete this column. Who will I break?"

**Practitioner Tip:** Use **OpenLineage** to capture this metadata automatically from Spark, Airflow, and Flink jobs.

## The "Silent Failure" Trap
The most dangerous data bug is the **"Distribution Shift."** If your ML model expects a value between 0 and 1, but a source system change starts sending values between 0 and 100, your pipeline won't crash, but your model's predictions will be nonsense. 
**Fix:** Monitor the **Mean** and **Standard Deviation** of critical columns.

## Further Reading
- [DataMeshArchitecture](DataMeshArchitecture) — Decentralized data ownership and observability.
- [DataQualityFrameworks](DataQualityFrameworks) — Comparing Soda, Great Expectations, and Monte Carlo.
- [DistributedTracing](DistributedTracing) — Monitoring the services that generate the data.
