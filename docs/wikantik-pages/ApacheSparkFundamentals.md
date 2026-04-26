---
title: Apache Spark Fundamentals
type: article
cluster: data-systems
status: active
date: '2026-04-25'
tags:
- apache-spark
- batch-processing
- data-engineering
- distributed-computing
summary: Spark in 2026 — what it's good at (large-scale batch + streaming with
  SQL), where it loses (small data, simple workloads), and the operational
  realities of running it.
related:
- BatchVsStreaming
- ApacheKafkaFundamentals
- DataLakehouse
- DistributedComputingAlgorithms
hubs:
- DataSystems Hub
---
# Apache Spark Fundamentals

Spark is the dominant general-purpose distributed compute engine. Started at Berkeley in 2009; replaced MapReduce as the standard batch engine; absorbed streaming (Structured Streaming), graph processing (GraphX), and ML (MLlib) along the way. By 2026, "Spark or alternative" is the question for any non-trivial data pipeline.

This page is the working concepts and operational realities.

## What Spark is

A distributed compute engine: parallelises operations on data spread across many machines. Three core APIs in modern Spark:

- **DataFrame / Dataset API** — typed, schema-aware, optimised. The default for most code.
- **Spark SQL** — write SQL; same query optimiser; same execution.
- **RDD** (Resilient Distributed Dataset) — older, lower-level. Still works; rarely the right choice in 2026.

Underneath: a driver process plans queries; worker (executor) processes execute on partitions of data; the driver coordinates.

## When Spark wins

- **Large-scale batch processing.** Tens of GB to TB+. SQL or DataFrame transformations over partitioned data.
- **ETL / ELT pipelines.** The dominant use case. Read from sources, transform, write to sinks.
- **Streaming with batch-like semantics.** Structured Streaming treats streams as continuously growing tables. Reasonable for moderate-throughput, not-strictly-real-time.
- **Multi-source joins.** Reading from S3, JDBC, Kafka, and joining across them is what Spark is shaped for.
- **Mixed batch + ML workflows.** Train models, score batches, write results.

## When Spark loses

- **Small data.** Tens of MB or even GB on a single machine. DuckDB, Polars, pandas usually win on developer experience and runtime.
- **Real-time low-latency.** Spark Structured Streaming is microbatch-based; Flink wins for true millisecond-latency streaming.
- **Simple SQL queries.** A managed warehouse (Snowflake, BigQuery) handles SQL queries with less ops.
- **Graph computation at scale.** GraphX is barely maintained; specialised graph DBs win.
- **Iterative algorithms.** Spark caches but the model is batch-oriented; native iterative engines (Flink) sometimes beat it.

The trend in 2026: Spark for large-scale data engineering; alternatives for narrower cases.

## DataFrame essentials

The DataFrame API is what you write in modern Spark:

```python
df = spark.read.parquet("s3://bucket/path/")

result = (df
    .filter(F.col("status") == "active")
    .groupBy("user_id")
    .agg(F.sum("amount").alias("total"))
    .filter(F.col("total") > 1000)
    .orderBy(F.desc("total"))
)

result.write.mode("overwrite").parquet("s3://bucket/output/")
```

Looks like SQL with method calls. Catalyst optimiser plans efficiently; Tungsten executes via codegen.

## Lazy evaluation

Spark transformations are lazy. `filter`, `groupBy`, `agg` build a plan; they don't execute. Actions (`write`, `count`, `collect`) trigger execution.

```python
df = spark.read.parquet(...)
df1 = df.filter(...)          # no work yet
df2 = df1.groupBy(...).agg(...)  # no work yet
df2.write.parquet(...)         # NOW Spark computes everything
```

The benefit: Catalyst sees the whole plan; can push predicates down, eliminate columns, reorder for efficiency.

The trap: `df.collect()` mid-pipeline forces execution; subsequent transformations re-execute from source unless cached. Be deliberate about action calls.

## Partitions and shuffles

Data is split into partitions; each partition processes on one executor.

Operations:

- **Narrow** transformations (`filter`, `select`, `withColumn`) — operate within partition. Cheap.
- **Wide** transformations (`groupBy`, `join`, `orderBy`) — require data movement across partitions. Expensive (a "shuffle").

Shuffle = serialise data, write to disk, re-read on different machine. The dominant cost in real pipelines.

Mitigations:

- **Pre-partition by join key**: data already in the right partitions; no shuffle.
- **Broadcast small tables**: replicate the small side to all executors instead of shuffling. Hint: `df.join(broadcast(small_df), ...)`.
- **Avoid unnecessary shuffles**: design queries to combine narrow operations between shuffles.

Reading the query plan (`df.explain()`) shows shuffles. If you see lots of `Exchange` nodes, you're shuffling more than necessary.

## File formats

Picking the storage format matters:

| Format | Strengths | Use when |
|---|---|---|
| **Parquet** | Columnar; compressed; predicate push-down | Default for batch analytics; what most pipelines use |
| **ORC** | Columnar; compressed; richer statistics | Hive-heavy environments |
| **Delta Lake / Iceberg / Hudi** | Parquet + ACID transactions + time travel | Modern lakehouse; the way new pipelines are built |
| **Avro** | Row-oriented; schema-evolved | Streaming / event payloads |
| **JSON / CSV** | Simple; verbose; uncompressed | Source of truth from external systems; convert to Parquet ASAP |

In 2026, lakehouse formats (Iceberg, Delta) are increasingly the default for new pipelines. They give you Parquet's storage benefits plus ACID writes and time travel.

## Lakehouse era

Spark + Iceberg / Delta / Hudi lets you treat object storage as a database with transactional guarantees. The pattern most data platforms adopt in 2026.

```python
spark.read.format("iceberg").load("catalog.namespace.table")
df.writeTo("catalog.namespace.table").append()
```

Time travel:

```python
spark.read.format("iceberg")
    .option("snapshot-id", "12345")
    .load("table")
```

Compaction, schema evolution, partition evolution — handled by the table format.

For new data platforms: Spark + Iceberg on object storage is the standard recipe.

## Streaming with Structured Streaming

```python
events = (spark.readStream
    .format("kafka")
    .option("kafka.bootstrap.servers", "...")
    .option("subscribe", "orders")
    .load())

aggregated = (events
    .selectExpr("CAST(value AS STRING) as json")
    .select(F.from_json("json", schema).alias("data"))
    .select("data.*")
    .groupBy(F.window("event_time", "1 minute"), "user_id")
    .agg(F.count("*").alias("order_count")))

query = (aggregated.writeStream
    .format("delta")
    .option("checkpointLocation", "...")
    .start("output/path"))
```

Microbatch processing — events accumulate for a microbatch (default ~100ms-1s), processed in one pass, output written. State is checkpointed.

For most "near real-time" workloads (seconds latency), Structured Streaming works. For sub-second strict-real-time, Flink is better.

## Operational realities

### Cluster sizing

A common mistake: too many small executors or too few big ones. Rules of thumb:

- **5 cores per executor** is a sweet spot (more cores per executor → garbage collection issues).
- **JVM heap 4-8GB per executor** typical; more for memory-heavy operations.
- **Total cores = data size / partitions × concurrent stages**, with some slack.

For specific workloads, profile and adjust.

### Configuration tuning

`spark.sql.shuffle.partitions` (default 200) controls shuffle parallelism. Too low = bottleneck; too high = overhead. For TB-scale data, 1000-4000.

Adaptive Query Execution (AQE, on by default in modern Spark) handles a lot of this automatically.

### Memory issues

OOM is the most common Spark failure. Causes:

- Skewed data: one partition has 10× the data of others; one executor OOMs.
- `collect()` on large DataFrames: pulls everything to driver.
- Cartesian joins: explode data size.
- Unnecessary caching: holds memory forever.

Diagnostics: check Spark UI; look for skewed partition sizes, executor OOM events, GC time.

### Skew

Skew is the silent killer. One partition is much larger than the others; one executor takes ages while others sit idle.

Mitigations:

- **Salted joins**: add randomness to the join key for hot keys; aggregate later.
- **Broadcast small side**: avoid the shuffle entirely.
- **Filter outliers**: process them separately.
- **AQE** in modern Spark: handles many cases automatically.

### Cost on managed services (Databricks, EMR)

Spark managed clusters can burn money. Watch:

- **Idle clusters** — auto-terminate.
- **Over-provisioning** — start small; scale.
- **Spot / preemptible** for workloads that tolerate interruption.

Most teams' Spark cost is 30-50% over what's necessary. Audit periodically.

## Spark vs alternatives

| Alternative | When it wins |
|---|---|
| **DuckDB** | Small-to-medium data, single machine, SQL-heavy |
| **Polars** | Single machine, Python ergonomics, parallel CPU |
| **Pandas** | Smallest data, exploratory, prototyping |
| **Snowflake / BigQuery** | SQL workloads, no need to operate compute |
| **Flink** | True streaming, sub-second latency |
| **Dask** | Python-native, parallel, smaller scale |
| **Trino / Presto** | Federated SQL; query data without moving |

Spark is rarely the best at any specific task. It's broadly good across many tasks; the integration value is real for mixed workloads.

For a new project: don't default to Spark. Pick the simplest tool that handles your scale; Spark when you outgrow simpler tools.

## A pragmatic stack

For a typical data engineering team in 2026:

- **Spark on Kubernetes or managed (Databricks / EMR).**
- **Iceberg as the table format on S3 / GCS / ADLS.**
- **dbt for SQL transformations.**
- **Airflow / Dagster / Prefect for orchestration.**
- **Lakehouse for the unified data substrate.**

This stack handles batch and streaming, gives you ACID guarantees, scales reasonably, has mature ecosystem. The "modern data stack" of mid-2020s.

## Further reading

- [BatchVsStreaming] — choosing approaches
- [ApacheKafkaFundamentals] — usual upstream for Spark Streaming
- [DataLakehouse] — substrate Spark increasingly writes to
- [DistributedComputingAlgorithms] — concepts behind Spark internals
