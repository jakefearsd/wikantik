---
cluster: distributed-systems
canonical_id: 01KQ0P44MDQ4BCTSQKVYX1NBFR
title: Batch Vs Streaming
type: article
tags:
- data-engineering
- batch
- streaming
- lambda-architecture
- kappa-architecture
summary: A technical comparison of batch and stream processing paradigms, detailing Lambda vs Kappa architectures and the challenges of achieving exactly-once semantics.
auto-generated: false
date: 2024-05-16
---
# Batch vs. Stream Processing: Architectural Patterns

The choice between batch and stream processing defines the latency and consistency guarantees of a data platform. While batch processing optimizes for throughput and historical completeness, stream processing optimizes for low-latency insight. Modern architectures attempt to unify these paradigms.

## 1. Batch Processing: The Throughput Specialist
Batch processing operates on high-latency, large-volume data blocks. It is characterized by bounded datasets where the "end" of the data is known.
*   **Strengths:** High efficiency via parallelization, reliable error recovery (re-running a job), and deep historical analysis.
*   **Weaknesses:** High latency (minutes to hours).

## 2. Stream Processing: The Latency Specialist
Stream processing operates on unbounded data streams, processing events as they arrive.
*   **Strengths:** Sub-second latency, real-time alerting, and continuous state updates.
*   **Weaknesses:** Complexity in handling late-arriving data and state management.

## 3. Distributed Architectures: Lambda vs. Kappa

### 3.1 Lambda Architecture
The Lambda architecture attempts to provide both low-latency views and highly accurate historical views by running two separate pipelines:
1.  **Batch Layer:** The "source of truth." It stores all raw data and periodically computes complex, accurate views (e.g., via Spark/MapReduce).
2.  **Speed Layer:** Processes recent data in real-time to provide low-latency views (e.g., via Flink/Storm). It compensates for the lag of the batch layer.
3.  **Serving Layer:** Merges results from both layers to answer queries.

**Criticism:** The main drawback is **logic duplication**. Developers must write and maintain the same transformation logic in two different systems (e.g., Java for the speed layer and SQL/Scala for the batch layer).

### 3.2 Kappa Architecture
Proposed by Jay Kreps, the Kappa architecture simplifies the system by removing the batch layer entirely. Everything is treated as a stream.
1.  **Stream Layer:** A single pipeline (e.g., Apache Flink or Kafka Streams) handles both real-time processing and historical re-processing.
2.  **Re-processing:** To "re-run" a batch job, the system simply re-plays historical data from the beginning of the stream (using a high-retention log like Kafka).

**Advantage:** Single code base for all data processing.

## 4. The "Exactly-Once" Semantics Challenge
Achieving exactly-once semantics (EOS) in distributed systems is difficult due to network failures and retries.

### 4.1 At-Least-Once vs. Exactly-Once
*   **At-Least-Once:** Events are guaranteed to be processed, but may be processed multiple times due to retries. Requires **idempotency** at the sink to avoid duplicate data.
*   **Exactly-Once:** Events are processed and reflected in the final state exactly once, even if failures occur.

### 4.2 Mechanisms for Exactly-Once
1.  **Checkpointing (Flink):** Periodic snapshots of the entire distributed state. On failure, the system rolls back to the last successful checkpoint.
2.  **Transactional Writes:** Using two-phase commit (2PC) between the stream processor and the sink (e.g., Kafka's transactional producer).
3.  **Idempotency:** Designing the processing logic such that $f(x) = f(f(x))$. For example, using `UPSERT` into a database based on a unique event ID rather than `INSERT`.

## 5. Watermarking and Windowing
In streaming, data often arrives out of order.
*   **Windowing:** Grouping events by time (Sliding, Tumbling, or Session windows).
*   **Watermarks:** A mechanism to signal that no more events with a timestamp earlier than $T$ are expected. It allows the system to close a window and emit results despite the unbounded nature of the stream.

## Summary: When to Use Which?
*   **Use Batch:** For financial reconciliation, historical reporting, and large-scale model training.
*   **Use Stream:** For fraud detection, real-time dashboards, and sensor monitoring.
*   **Use Kappa:** When you want to simplify operations and your stream processor can handle the historical volume.
