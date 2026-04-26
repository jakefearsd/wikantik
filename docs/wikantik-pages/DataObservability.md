---
title: Data Observability
type: article
cluster: data-systems
status: active
date: '2026-04-25'
tags:
- data-observability
- data-quality
- monitoring
- lineage
- pipeline-monitoring
summary: Data observability beyond "is the pipeline running" — freshness, volume,
  schema, lineage, distribution drift, and the alerts that catch quality
  regressions before downstream consumers do.
related:
- DataMeshArchitecture
- DataGovernance
- DistributedTracing
- DataLakehouse
hubs:
- DataSystems Hub
---
# Data Observability

Software observability is "is my service running correctly." Data observability is "is my data correct." The first has a 20-year-mature toolchain (metrics, traces, logs); the second has a more recent and less consensus toolchain.

The questions are different but the pattern is the same: instrument the system; surface anomalies before users do.

## The five dimensions

The widely-cited framing (Monte Carlo's "five pillars," similar in Bigeye, Soda):

1. **Freshness** — when did the data last update? Is that current enough?
2. **Volume** — did the expected number of rows arrive?
3. **Schema** — did fields change unexpectedly?
4. **Distribution** — did values look normal? Mean, percentiles, null rates, distinct values.
5. **Lineage** — what produced this; what depends on it?

Each dimension has detection mechanisms. The art is picking thresholds that catch real issues without noise.

## Freshness

```sql
-- Pipeline check: when did this table last update?
SELECT MAX(updated_at) FROM critical_table;
-- If older than threshold, alert.
```

Per-table freshness SLOs. Critical tables: SLO of 1 hour staleness. Daily reports: SLO of 24 hours. Quarterly summaries: SLO of 30 days.

When freshness fails: pipeline broke, upstream data didn't arrive, scheduling glitch, downstream consumer using cached old version.

Detection: easy. Alerting: critical. Most data outages start as "the pipeline silently stopped 3 days ago."

## Volume

```sql
-- Did we get a normal number of rows?
SELECT COUNT(*) FROM yesterdays_partition;
-- Compare to expected; alert if outside band.
```

Expected band: typically `mean ± k × stddev` from the last N days, with seasonal adjustment if your data has weekly / monthly patterns.

When volume fails: upstream system had an outage, ingestion silently dropped events, source moved to a new endpoint, deduplication suddenly aggressive.

The trickier question: what's "expected"? For mature pipelines, look at trailing 28 days. For new pipelines, no history; tighten thresholds over weeks as you learn.

## Schema

Detect unexpected changes:

- Columns added without notice.
- Columns removed.
- Type changes (string → int, float → decimal).
- Null rate spikes.

Tools: Great Expectations / Soda / dbt tests / Bigeye / Monte Carlo / Acceldata catch these. dbt tests are the lightest weight option — assertions live alongside SQL transformations.

When schema fails: producer changed schema without communicating; backfill migration didn't update some rows; new system writing to the table.

The damage from undetected schema changes can be silent for weeks — downstream code reads the field, gets nulls or wrong types, produces subtle bugs that look like "data quality dropped."

## Distribution

Statistical properties of values:

- Mean, median, percentiles.
- Null rate.
- Distinct count.
- Min / max.
- Specific value frequencies.

Drift: today's distribution differs from yesterday's by more than a threshold.

Tools: same as schema. Most data observability platforms ship statistical drift detection out of the box.

When distribution fails:

- Pricing changed; a column that was rarely zero is now often zero.
- Bug in a transformation; rounding differences shift all values slightly.
- Upstream data source quality dropped.
- Genuine business shift (Black Friday traffic).

The challenge: distinguishing genuine business shifts from data bugs. Manual review of flagged drifts is part of the discipline.

## Lineage

A graph of "this column is derived from those columns from those tables." When something breaks, lineage tells you what's affected downstream.

Tools: DataHub, Atlan, Collibra, OpenLineage, Atlas. Most modern data warehouses (Snowflake, BigQuery) and orchestrators (dbt, Airflow) emit lineage automatically; the observability tool consumes it.

Lineage is most valuable during incidents: "Production dashboard is wrong → traces back to a transformation → traces back to an upstream data source → which broke at 9 am."

Without lineage, the same investigation is grep-through-pipelines for hours.

## Where to alert

For each dimension, three potential places to catch issues:

- **Source** — at ingestion time. Expensive (more data); catches earliest.
- **Pipeline mid-stream** — between transformations. Cheaper; can miss issues that pass through transformations.
- **Consumer-facing tables** — at the boundary where users / dashboards consume. Cheapest; catches latest. Risk: by the time you catch it, the bad data is already in reports.

Hybrid approach is common: lightweight checks at source (freshness, volume), comprehensive checks at consumer boundaries (schema, distribution).

## The alert calibration problem

Naive thresholds produce noise. "Volume off by 5%" might be normal weekly variation. Tuning matters.

Approaches:

- **Statistical bounds** based on historical variance. `mean ± 3σ` excluding seasonal effects.
- **Anomaly detection** — Prophet, ARIMA, simple ML models that learn seasonality and predict expected values.
- **Per-segment thresholds** — different SLOs per partition (tenant, region, table type).
- **Severity tiers** — info-level for small drifts; warning for medium; pager for large.

Worth investing time in alert tuning. Three iterations of "noise → silence → catch real issues" is typical.

## Tools

| Tool | Strengths | Best for |
|---|---|---|
| **Monte Carlo** | Mature; auto-detection; lineage | Enterprise SaaS preferred |
| **Bigeye** | Strong on metrics; configurable rules | Mid-market; analytics-heavy |
| **Acceldata** | Pipeline-focused; integrates with orchestration | Pipeline-heavy orgs |
| **Soda** | Open core; SQL-based assertions | Self-hosted, dbt-centric |
| **Great Expectations** | Open source; thorough; declarative | Smaller scale, self-managed |
| **dbt tests** | Built into dbt; cheap to add | Most dbt-using teams should have these |
| **Custom on Postgres / DuckDB** | Total control; cheap | Smaller data; specific needs |
| **OpenLineage + Marquez** | Open lineage standard | Lineage-specific; multi-tool environments |

For a typical team in 2026: dbt tests for the basics; a SaaS observability tool (Monte Carlo, Bigeye, Soda Cloud) for advanced detection and lineage.

## Failures specific to data observability

**False positives erode trust.** A noisy alert is silenced; the next real one is missed. Calibrate aggressively.

**Coverage gaps.** Tables not monitored (often the new ones); fields not checked (often the JSON blob ones). Audit periodically; require monitoring for production tables.

**Stale rules.** Threshold set when volume was 1k rows/day; volume now is 1M rows/day; rule is meaningless. Review rules quarterly.

**Missing lineage = missing impact analysis.** When something breaks, you don't know what's affected. Invest in lineage from day one.

**Detection without remediation.** "We know it's broken" is necessary but not sufficient. Pipelines need owners; alerts need on-call.

## A starter setup

For a team setting up data observability from zero:

1. **Enable dbt tests** on every transformation. Free assertion baseline.
2. **Add freshness and volume checks** for the top 10 critical tables.
3. **Capture lineage** via OpenLineage or your tool's native support.
4. **Wire alerts to Slack / PagerDuty** with severity tiers.
5. **Define data-quality SLOs** for top tables and report monthly.
6. **Review false-positive / false-negative rate** at 30 days; tune.

Two weeks of work; data quality catches most of the problems before consumers do.

## Further reading

- [DataMeshArchitecture] — data observability across distributed ownership
- [DataGovernance] — broader data discipline
- [DistributedTracing] — software observability for comparison
- [DataLakehouse] — observability for the typical substrate
