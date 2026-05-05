---
canonical_id: 01KQPQX5KYD1XWC8YKYWBQC809
date: 2026-05-03T00:00:00Z
cluster: distributed-systems
type: article
tags:
- distributed-systems
- consistency
- linearizability
- eventual-consistency
- replication
- cap-theorem
title: Consistency Models
relations:
- type: part-of
  target_id: 01KQEKGD9XWDSFGH7TWHH63NZT
- type: prerequisite-for
  target_id: 01KQ0P44VPKV01K0CMG0RNZ71A
summary: A deep dive into consistency models in distributed systems, ranging from
  strong models like Linearizability to weaker ones like Eventual Consistency. Explains
  the formal guarantees, performance trade-offs, and practical applications of each.
status: active
---

# Consistency Models

Consistency models define the contract between a distributed system and the applications that use it. They specify the rules for the visibility and ordering of read and write operations across multiple replicas.

## The Spectrum of Consistency

Consistency is not binary; it exists on a spectrum of guarantees. Stronger models are easier for developers to reason about but impose higher latency and lower availability.

### 1. Linearizability (Strong Consistency)
The gold standard of consistency. All operations appear to take effect instantaneously at some point between their invocation and completion. To an observer, the system behaves as if there were only a single copy of the data.
- **Used for**: Bank balances, identity management, distributed locks.
- **Mechanisms**: [[Paxos and Raft]], [[Two-Phase Commit Protocol]].

### 2. Sequential Consistency
A weaker form of strong consistency. It requires that all operations be seen by all processes in the same total order, and that this order is consistent with the order in which they were issued by each individual process. However, the order does not have to match real-time.
- **Used for**: Multi-processor cache coherence, some message queues.

### 3. Causal Consistency
Operations that are causally related must be seen by all processes in the same order. Concurrent operations (those with no causal relationship) may be seen in different orders on different nodes.
- **Used for**: Comment threads (where a reply must follow the original post), social media feeds.
- **Mechanisms**: [[Vector Clocks]].

### 4. Eventual Consistency
The weakest model. If no new updates are made to a data item, eventually all accesses to that item will return the last updated value. It provides no guarantees about the order of updates or when they will become visible.
- **Used for**: Social media "likes", DNS, CDNs, search indices.
- **See also**: [[EventualConsistency]].

### 5. Strong Eventual Consistency (SEC)
A refined version of eventual consistency where replicas that have received the same set of updates are guaranteed to be in the same state, without the need for manual conflict resolution.
- **Mechanisms**: [[CRDT Data Structures]].

## Choosing a Model (PACELC)

As described in the [[CAP Theorem]] and [[PACELC Theorem]], the choice of consistency model is a trade-off between:
1.  **Safety**: Do all readers see the same data?
2.  **Liveness**: How quickly does the system respond?
3.  **Partition Tolerance**: Can the system survive network failures?

For most modern distributed architectures, the trend is toward **Eventual Consistency** for high-scale read-heavy workloads, while reserving **Linearizability** for the small subset of operations that strictly require absolute correctness.
