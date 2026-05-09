---
canonical_id: 01KQEKGD79GMF630STAZT8DEX5
title: Apache Spark Fundamentals
type: article
cluster: data-engineering
status: active
date: '2026-05-15'
tags:
- apache-spark
- batch-processing
- data-engineering
- distributed-computing
- data-skew
summary: Technical guide to Spark internals, Catalyst optimization, shuffle mechanics, and strategies for handling data skew (salting).
related:
- BatchVsStreaming
- ApacheKafkaFundamentals
- DataLakehouse
- DistributedComputingAlgorithms
hubs:
- DataSystemsHub
auto-generated: false
---
# Apache Spark Fundamentals

Spark is a distributed compute engine that parallelizes operations over data partitions. It uses an abstraction called the **DataFrame** (built on top of RDDs) which allows for declarative query optimization.

## The Execution Model: Catalyst and Tungsten
Spark does not execute code as written. It passes transformations through the **Catalyst Optimizer**:
1. **Logical Plan**: A tree representation of the computation.
2. **Physical Plan**: Selection of the best strategy (e.g., Broadcast Hash Join vs. Sort Merge Join).
3. **Whole-Stage Code Generation (Tungsten)**: Generates optimized JVM bytecode to collapse multiple operators into a single function, reducing function call overhead and improving CPU cache locality.

## Partitions and the Shuffle Problem
- **Partitions**: The unit of parallelism. Data is divided into chunks (typically 128MB).
- **Narrow Transformations**: Operations like `map`, `filter`, or `select` that happen within a partition. Low cost.
- **Wide Transformations (Shuffles)**: Operations like `groupBy`, `join`, or `distinct` that require moving data across the network to new partitions. This is the primary performance bottleneck.

## Concrete Example: Handling Data Skew with Salting
Data skew occurs when one partition has significantly more records than others (e.g., joining orders on a popular `product_id`). This leads to a single executor OOMing while others sit idle.

**The Salting Strategy**:
1. Add a random "salt" to the join key on the skewed side.
2. Replicate the non-skewed side to match the salt range.

```python
from pyspark.sql import functions as F
import random

# Skewed Table: orders (key: product_id)
# Non-Skewed Table: products (key: product_id)

SALT_RANGE = 10

# 1. Salt the skewed side
skewed_df = orders.withColumn("salt", (F.rand() * SALT_RANGE).cast("int"))
skewed_df = skewed_df.withColumn("salted_key", F.concat(F.col("product_id"), F.lit("_"), F.col("salt")))

# 2. Replicate the non-skewed side
# Create a dataframe with numbers 0 to SALT_RANGE-1
salt_df = spark.range(SALT_RANGE).withColumnRenamed("id", "salt")

# Explode the products table so every product exists for every salt
replicated_products = products.crossJoin(salt_df)
replicated_products = replicated_products.withColumn("salted_key", 
    F.concat(F.col("product_id"), F.lit("_"), F.col("salt")))

# 3. Join on the salted key
result = skewed_df.join(replicated_products, "salted_key")
```

## Performance Tuning Checklist
- **Broadcast Joins**: Use `F.broadcast(small_df)` for tables under ~100MB to avoid shuffles.
- **Adaptive Query Execution (AQE)**: Ensure `spark.sql.adaptive.enabled=true`. It dynamically coalesces partitions and optimizes join strategies at runtime.
- **Shuffle Partition Tuning**: Set `spark.sql.shuffle.partitions` to 2-3x the number of cores, or let AQE handle it via `spark.sql.adaptive.coalescePartitions.enabled`.
- **Serialization**: Use Kryo serialization (`spark.serializer=org.apache.spark.serializer.KryoSerializer`) for faster data movement.

## Memory Management
Spark splits executor memory into:
- **Storage Memory**: For cached data (`.cache()`, `.persist()`).
- **Execution Memory**: For shuffles, joins, and aggregations.
- **User Memory**: For user-defined objects and data structures.
- **Reserved Memory**: Fixed overhead (300MB).

If you see `ExecutorLost` or `OOM` errors, check the **Spark UI Storage Tab** to see if cached data is starving the execution memory.

## Summary of Technical implementation added
- Added internal details on Catalyst and Tungsten.
- Detailed the Shuffle mechanic.
- Provided a full Python (PySpark) example of the **Salting** pattern to fix data skew.
- Included specific configuration keys for performance tuning (`AQE`, `Kryo`).
- Defined the memory layout components.
