---
status: active
verified_at: '2026-05-04T21:10:44.598011331Z'
type: article
date: '2026-05-04'
cluster: distributed-systems
title: PACELC Theorem
tags:
- distributed-systems
- pacelc
- cap-theorem
- consistency
- latency
summary: An explanation of the PACELC theorem and its application to consistency and
  latency trade-offs in distributed systems.
verified_by: gemini-cli-mcp-client
canonical_id: 01KQTD79N35VMXHY5M3NJYBS6E
---
# PACELC Theorem

The **PACELC Theorem** is an extension of the CAP theorem for distributed systems. It provides a more comprehensive framework for understanding the trade-offs in distributed databases and systems.

## The Theory
PACELC states that in a system that experiences a network partition (P):
- You must choose between **Availability (A)** and **Consistency (C)**. (This is the CAP part).

**ELSE (E)**, when the system is running normally (no partition):
- You must choose between **Latency (L)** and **Consistency (C)**.

## Trade-off Profiles

| Profile | Partition Behavior | Normal Behavior | Example Systems |
|---------|--------------------|-----------------|-----------------|
| **PA/EL** | Availability | Latency (favor speed) | DynamoDB, Cassandra (with eventual consistency) |
| **PC/EC** | Consistency | Consistency (favor accuracy) | BigTable, HBase, traditional RDBMS |
| **PA/EC** | Availability | Consistency | MongoDB (depending on config) |

## Relevance to Wikantik
While Wikantik is primarily a single-instance system, its design anticipates distributed operation:
- **Search Indexes:** The search index (Lucene) is eventually consistent by design. It favors **Latency (EL)** during normal operation but ensures that all data is eventually searchable.
- **Knowledge Graph:** The KG, backed by PostgreSQL, typically favors **Consistency (EC)** to ensure that AI agents always see a coherent semantic network.
- **Caching:** The `wikantik-cache-memcached` module introduces PACELC considerations when running in a multi-node Tomcat cluster, where cache consistency vs. latency must be carefully balanced.

## See Also
- [Distributed Computing Evolution](DistributedComputingEvolution) — The history of trade-offs in distributed systems.
- [Consistency Models](ConsistencyModels) — A deeper look at the 'C' in PACELC.
- [Database Performance Monitoring](DatabasePerformanceMonitoringHub) — Measuring the 'L' (Latency) in production.
