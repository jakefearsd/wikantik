---
canonical_id: 01KQ12YDS1BCMTAA32328JPVAD
title: Apache Kafka Fundamentals
type: article
cluster: data-systems
status: active
date: '2026-04-25'
tags:
- kafka
- messaging
- streaming
- partitions
- consumer-groups
summary: Kafka through the lens of "what you actually have to know to operate it" —
  partitions and offsets, consumer-group semantics, the durability/throughput knobs,
  and where teams reliably blow themselves up.
related:
- EventDrivenArchitecture
- BatchVsStreaming
- ApacheSparkFundamentals
- DistributedTracing
hubs:
- DataSystems Hub
---
# Apache Kafka Fundamentals

Kafka is the default backbone for streaming and event-driven architectures, and 90% of teams using it understand maybe 30% of how it actually works. The other 70% is what bites you in production.

This page is the operating concepts you need to design with Kafka and not have it surprise you in 12 months.

## The mental model

Kafka is, at its core, **a durable, partitioned, append-only log**.

- **Topic** — a logical stream. "orders," "user-events," "audit-log."
- **Partition** — a physical shard of a topic. Each partition is a single ordered log on disk.
- **Offset** — the position of a record within a partition. Monotonically increasing, never reused.
- **Producer** — writes records to a topic. Picks the partition (or lets Kafka pick by hash of key).
- **Consumer** — reads records from one or more partitions. Tracks its own offset.
- **Broker** — a server that hosts partitions.
- **Consumer group** — a set of consumer instances that share the work of consuming a topic; each partition is consumed by exactly one member of the group.

Once you understand "log + partitions + offsets + consumer groups," everything else is configuration.

## Partitioning is the most consequential decision

The number of partitions per topic determines:

- **Maximum parallelism for consumers.** A consumer group can have at most as many active consumers as partitions.
- **Ordering guarantees.** Order is preserved per partition, never across partitions. If two events for the same aggregate must be processed in order, they must land on the same partition (key by aggregate ID).
- **Storage and rebalancing cost.** More partitions = more files, more metadata, slower rebalances when group membership changes.

Default rule: partition count = 2–4× the maximum expected consumer instances. Hard to add later (re-keying breaks ordering for in-flight aggregates), so size up.

The partition key is the second decision. Pick a high-cardinality key with even distribution. Bad keys cause hot partitions where one consumer falls behind while others idle.

## Replication and durability

`replication.factor=3` is the standard. Each partition has one leader and two followers; producers write to the leader, which replicates to followers.

Three durability knobs:

- **`acks`** — what does the producer wait for?
  - `acks=0` — fire and forget. Loses messages on broker crash.
  - `acks=1` — leader writes locally, then acks. Loses messages if leader crashes before replicating.
  - `acks=all` — leader waits for all in-sync replicas. Safest. Slower.
- **`min.insync.replicas`** — how many replicas must be in sync before producers can write? Set to `replication.factor - 1` (e.g. 2 with RF=3). With `acks=all` and `min.insync.replicas=2`, you tolerate one broker failure with zero data loss.
- **`unclean.leader.election.enable`** — should an out-of-sync replica become leader if all in-sync replicas are gone? `false` for safety; `true` for availability over consistency. Default `false` is correct for most.

`acks=all`, `replication.factor=3`, `min.insync.replicas=2`, `unclean.leader.election.enable=false`. That's the durable-by-default setup. Anything looser is a deliberate trade-off you should be able to articulate.

## Consumer semantics

Consumer groups divide a topic's partitions among instances. Rebalancing happens when membership changes.

- **At-most-once** — commit offset before processing. If processing fails, message is lost.
- **At-least-once** — process, then commit. Crash between processing and commit replays the message. Default and what most code accidentally implements.
- **Exactly-once** — Kafka 0.11+ supports it via transactions and idempotent producers. Real, but only within the Kafka ecosystem; your downstream side effects (DB writes, external API calls) are still your problem.

In practice, **at-least-once + idempotent consumers** is what most production systems implement. Exactly-once requires the entire pipeline to participate; usually not worth the complexity.

## Offsets and consumer state

Each consumer group tracks its committed offset per partition in the special `__consumer_offsets` topic. Committing means "I've finished processing up to here; if I crash, replay from this point."

Critical mistake: auto-commit at fixed intervals. Default `enable.auto.commit=true` commits every 5 seconds regardless of whether processing actually completed. Crash mid-processing = silently skipped messages.

Fix: `enable.auto.commit=false`, commit explicitly after processing each message (or batch). Slightly more code, dramatically more correct.

## Consumer-group rebalancing

When a consumer joins or leaves the group, partitions are reassigned. During rebalance, all consumers in the group stop processing.

- **Eager rebalancing** (older default) — everyone stops, partitions reassigned, everyone resumes. "Stop the world" for the topic.
- **Cooperative rebalancing** (`partition.assignment.strategy=CooperativeStickyAssignor`) — only the moving partitions stop. Much smoother for large consumer groups.

Use cooperative rebalancing in any modern setup. The default in older Kafka versions is eager; switching is a one-line config and a real improvement.

## Retention and compaction

Topics retain data based on `retention.ms` (default 7 days) or `retention.bytes`. After that, segments are deleted from disk.

Two retention modes:

- **Time/size based** — typical. "Keep last 30 days of orders." Suits event streams.
- **Log compaction** — keep only the latest record per key. Suits "current state" topics. Old records are deleted; the topic becomes a key→latest-value store. Used for change data capture, materialised views.

Compaction has subtle behaviours: tombstones (records with null values) signal deletions; the cleaner runs periodically and isn't instant. For a "is this user still active" topic with compaction, expect the deletion to take minutes-to-hours to physically remove from old segments.

## The KRaft transition

Kafka traditionally used ZooKeeper for cluster metadata. As of Kafka 3.x, ZooKeeper is being replaced by KRaft (Kafka's own Raft). 4.x removes ZooKeeper entirely.

KRaft is simpler operationally (one fewer system), faster metadata operations, and supports more partitions per cluster. For new deployments, use KRaft. For existing ZooKeeper deployments, plan the migration; it's not difficult but it's not trivial.

## Performance tuning that matters

- **Batch size and `linger.ms`.** Producers batch records before sending. Bigger batches = better throughput, higher latency. `linger.ms=5–10` is a reasonable starting point for most workloads.
- **Compression.** `compression.type=lz4` or `snappy`. Compresses on producer, decompresses on consumer. Often 3–5× throughput improvement; CPU cost negligible.
- **`fetch.min.bytes` on consumer.** Don't fetch tiny batches; wait for a few KB to accumulate. Cuts broker load.
- **Filesystem and disk.** Kafka loves sequential writes. Use SSDs for low latency; HDDs for throughput-only workloads. XFS over ext4 for high partition counts.
- **Page cache.** Kafka relies heavily on Linux page cache. Don't tune `swappiness` aggressively; don't allocate too much heap (Kafka should run with 6–8 GB heap, even on 64 GB nodes — let the kernel cache the rest).

## Failure modes seen in the wild

- **Unbalanced partitions.** One partition's key is hot; one consumer is overwhelmed. Re-key the topic or split the hot key.
- **Consumer lag spirals.** Consumers can't keep up; lag grows; rebalance times grow because catching up is slow. Watch consumer lag per group per topic; alert at thresholds; scale consumers before it spirals.
- **Producer back-pressure.** When brokers are slow, producer buffers fill. `max.block.ms` controls how long the producer waits before throwing. Tune to the latency you can afford.
- **Throwaway records.** Producer's `acks=0` plus a broker crash equals silently lost messages. Don't ship `acks=0` to anything that matters.
- **Schema-incompatible reads.** Consumer code expects schema v2; topic has v1 records. Wrong reading. Schema registry with compatibility checks (Confluent, Apicurio) prevents this.
- **Underprovisioned ZooKeeper / KRaft controller.** Metadata operations slow → topic creation slow → producer registration slow → cascading slowness. Treat the controller tier as production-critical.

## Observability

- **Per-broker metrics**: under-replicated partitions, request rate, network throughput, disk usage, log-append rate.
- **Per-topic metrics**: messages-in/sec, bytes-in/sec, replication lag.
- **Per-consumer-group metrics**: lag (records behind, time behind).

Tools: Confluent Control Center (commercial), Kafka Manager (open source), Burrow (consumer lag), Strimzi metrics (Kubernetes). Most observability backends have Kafka exporters.

Lag dashboards are non-negotiable. The single most useful chart is "consumer lag, in records and seconds, per consumer group, over time." Alerting threshold depends on workload.

## When Kafka is overkill

A small system with < 10k events/day doesn't need Kafka. RabbitMQ, Redis Streams, or even a database-backed queue (a `jobs` table with a `picked_up_at` column) is simpler.

Kafka shines at high volume, persistent retention, and multiple consumer groups reading the same data. Below that scale, the operational cost is real and the alternatives are simpler.

## Further reading

- [EventDrivenArchitecture] — Kafka as the substrate
- [BatchVsStreaming] — when streaming is the right answer
- [ApacheSparkFundamentals] — common downstream consumer
- [DistributedTracing] — getting trace context through Kafka
