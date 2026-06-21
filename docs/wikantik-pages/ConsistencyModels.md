---
date: '2026-04-26'
status: active
summary: Formal analysis of consistency models, safety/liveness trade-offs, and implementation
  strategies in distributed state machines.
tags:
- consistency-models
- distributed-systems
- cap-theorem
- linearizability
- replication
type: article
auto-generated: false
cluster: distributed-systems
canonical_id: 01KQPQX5KYD1XWC8YKYWBQC809
title: Consistency Models
---

Consistency models define the safety contract between a distributed system and its observers, specifying the valid orderings of read and write operations across replicas.

## The Hierarchy of Consistency

Consistency is a spectrum of guarantees. Stronger models minimize developer cognitive load but increase latency and reduce availability.

### 1. Linearizability (Atomic Consistency)
Linearizability is a **Safety** property. It requires that every operation appears to take effect instantaneously at some point between its invocation and its response.
- **Formal Property:** For any history of operations $H$, there exists a sequential permutation $S$ that preserves the real-time ordering of non-overlapping operations.
- **Cost:** Requires a quorum or a leader. Impossible to maintain during a network partition while staying available (CAP theorem).

### 2. Sequential Consistency
Weaker than linearizability. It requires that all processes see the same order of operations, and that this order respects the program order of each individual process, but it does not require real-time synchronization.
- **Use Case:** CPU cache coherence (MESI protocol).

### 3. Causal Consistency
Only operations that are causally related must be seen in the same order. Concurrent operations (no "happened-before" relation) can be seen in different orders.
- **Tracking:** Implemented via **[VectorClocks](VectorClocks)**.
- **Benefit:** Available during partitions (AP system).

### 4. Eventual Consistency
A **Liveness** property. If writes stop, all replicas will eventually converge to the same state. It offers no guarantees on the intermediate state seen by readers.
- **Conflict Resolution:** Last-Write-Wins (LWW) or **[CrdtDataStructures](CrdtDataStructures)** (Strong Eventual Consistency).

## The PACELC Trade-off

The PACELC theorem extends CAP by considering the system's behavior during normal operation (Else):
- **P**artitioned $\rightarrow$ Choose **A**vailability or **C**onsistency.
- **E**lse (Normal) $\rightarrow$ Choose **L**atency or **C**onsistency.

| Model | PACELC Classification | Primary Mechanism |
|---|---|---|
| **Linearizable** | PC / EC | Paxos, Raft, 2PC |
| **Causal** | PA / EL | Vector Clocks |
| **Eventual** | PA / EL | Gossip, Anti-Entropy |

## Concrete Comparison: User Profile Update

| Model | Behavior | Failure Mode |
|---|---|---|
| **Strong** | User updates bio; refresh always shows new bio. | Update fails if 1 of 3 nodes is down. |
| **Eventual** | User updates bio; refresh may show old bio for 2 seconds. | Update succeeds as long as 1 node is up. |
| **Causal** | User replies to a post; reply never appears before the post. | High metadata overhead (vector clock size). |

## Implementation Strategy
1. **Default to Eventual Consistency** for non-critical paths (analytics, search, likes).
2. **Use Linearizability** for unique constraints (usernames, inventory count) and financial state.
3. **Leverage HLC (Hybrid Logical Clocks)** to provide approximate global ordering without the performance hit of a global lock.
