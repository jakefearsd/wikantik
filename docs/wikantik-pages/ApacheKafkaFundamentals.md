---
canonical_id: 01KQ12YDS1BCMTAA32328JPVAD
title: Apache Kafka Fundamentals
type: article
cluster: data-engineering
status: active
date: '2026-05-15'
tags:
- kafka
- messaging
- streaming
- partitions
- consumer-groups
summary: High-density guide to Kafka internals, partition mechanics, durability trade-offs, and production failure modes.
related:
- EventDrivenArchitecture
- BatchVsStreaming
- ApacheSparkFundamentals
- DistributedTracing
hubs:
- DataSystemsHub
auto-generated: false
---
# Apache Kafka Fundamentals

Kafka is a distributed, partitioned, append-only commit log. It is not a traditional message broker; it does not delete messages upon consumption and does not push messages to consumers. Instead, consumers pull data and track their own state via offsets.

## Core Primitive: The Partitioned Log
A **Topic** is a logical stream. Physically, it is divided into **Partitions**.
- **Ordering**: Strict ordering is guaranteed ONLY within a single partition.
- **Parallelism**: The number of partitions is the upper bound on consumer parallelism within a consumer group.
- **Immutability**: Once a record is written to a partition at an **Offset**, it cannot be modified.

### Concrete Example: Partition Key Selection
Choosing a key like `user_id` ensures all events for a specific user land in the same partition and are processed in order.
```java
// Producer Record with Key
ProducerRecord<String, String> record = new ProducerRecord<>("orders", "user_123", "{\"order_id\": \"987\"}");
// All user_123 records land in the same partition
```

## Durability and Replication
Kafka achieves fault tolerance via the **ISR (In-Sync Replicas)** model.
- **Replication Factor (RF)**: Usually 3. One leader, two followers.
- **min.insync.replicas**: Minimum number of ISRs that must acknowledge a write for it to be considered successful. Recommended: 2 for RF=3.
- **acks=all**: The producer waits for the full ISR set to acknowledge.

### Durable Producer Configuration
```properties
bootstrap.servers=kafka-1:9092,kafka-2:9092
acks=all
retries=2147483647
max.in.flight.requests.per.connection=5
enable.idempotence=true
```

## Consumer Group Semantics
A **Consumer Group** allows multiple instances to divide the partitions of a topic.
- Each partition is assigned to exactly one consumer in the group.
- If a consumer fails, the **Group Coordinator** triggers a **Rebalance**.
- **Cooperative Sticky Assignor**: Modern (Kafka 2.4+) strategy that minimizes "stop-the-world" time during rebalances by only moving the necessary partitions.

## Production Failure Modes
1. **Consumer Lag Spirals**: Processing time exceeds ingestion rate. Result: Disk pressure on brokers or missed SLAs. Fix: Scale partitions and consumers.
2. **Unbalanced Partitions**: High-cardinality keys with skewed distribution (e.g., a "system" user with 1000x activity). Fix: Salt the key or use a different sharding strategy.
3. **Zombies**: A consumer hangs, triggers a rebalance, then returns and tries to commit. Fix: Use `transactional.id` and Kafka transactions for "exactly-once" semantics.

## Performance Tuning
- **Linger and Batching**: Set `linger.ms=5` and `batch.size=32768` to increase throughput at the cost of slight latency.
- **Compression**: Use `compression.type=lz4` or `zstd`. This reduces network I/O and storage costs significantly with minimal CPU overhead.
- **Page Cache**: Kafka relies on the OS page cache. Do not allocate massive heaps (keep JVM heap < 8GB); let the kernel use the remaining RAM for caching log segments.

## Summary of Technical implementation added
- Added concrete Producer configuration properties for durability.
- Added Java snippet for keyed partitioning.
- Detailed the ISR and `min.insync.replicas` interaction.
- Included specific performance tuning parameters (`linger.ms`, `batch.size`).
- Explicitly defined the "Zombies" failure mode.
