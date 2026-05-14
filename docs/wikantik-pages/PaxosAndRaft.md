---
title: Paxos and Raft: Distributed Consensus
type: article
cluster: distributed-systems
status: published
date: '2026-05-10'
summary: A technical comparison of the two primary consensus algorithms used to achieve fault-tolerant state machine replication in distributed clusters.
tags:
- distributed-systems
- consensus
- paxos
- raft
- state-machine-replication
relations:
- {type: component_of, target_id: 01KQEKGD9XWDSFGH7TWHH63NZT} # Distributed Systems Hub
- {type: related_to, target_id: 01KS7P9Z8QYAS6P09AM61S5E2V} # Leader and Followers
- {type: related_to, target_id: 01KS7M5J8QYAS6P09AM61S5E2T} # WAL
canonical_id: 01KS6S8Z8QYAS6P09AM61S5E2O
---

# Paxos and Raft: The Engines of Consensus

Consensus is the core challenge of distributed systems: ensuring that a cluster of non-trusting or failing nodes can agree on a single value or a sequence of operations. This agreement is required for [Leader Election](LeaderAndFollowers) and **State Machine Replication (SMR)**.

Two algorithms dominate the field: **Paxos**, the mathematically flexible original, and **Raft**, the "understandable" successor.

## 1. Paxos: The Theoretical Powerhouse

Proposed by Leslie Lamport in 1989, Paxos is a family of protocols designed for total flexibility.

### Core Paxos Roles
*   **Proposer:** Receives client requests and attempts to convince the cluster to accept them.
*   **Acceptor:** Votes on proposals and acts as the durable memory of the cluster.
*   **Learner:** Learns the outcome of the vote and applies it to the state machine.

### Multi-Paxos and Optimization
Standard Paxos agrees on only one value. **Multi-Paxos** chains these agreements together to form a log. In 2026, Multi-Paxos is preferred for high-performance systems because it allows for:
*   **Out-of-Order Confirmation:** Unlike Raft, Multi-Paxos can confirm log index $N+1$ before $N$, allowing for massive pipelining.
*   **WAN Flexibility:** Variants like *Fast Paxos* can save network round-trips (RTTs) in global deployments by allowing any node to initiate a proposal.

## 2. Raft: Design for Understandability

Introduced by Ongaro and Ousterhout in 2014, Raft was designed specifically to be easier to implement correctly than Paxos.

### The Strong Leader
Raft centers all activity around a **Strong Leader**. 
1.  **Leader Election:** Nodes use randomized timeouts to elect a leader.
2.  **Log Replication:** The leader receives commands, appends them to its local [Write-Ahead Log (WAL)](WriteAheadLog), and replicates them to followers.
3.  **Safety:** Raft ensures that a node can only be elected leader if it contains all previously committed log entries.

### Head-of-Line Blocking
Because Raft enforces a strict sequential log order, a single slow follower or a lost packet can stall the entire pipeline. This makes Raft slightly less performant than Multi-Paxos at extreme scales.

## 3. Technical Comparison (2026 Standards)

| Feature | Multi-Paxos | Raft |
| :--- | :--- | :--- |
| **Philosophy** | Theoretical Flexibility | Understandable Integrity |
| **Throughput** | **Higher** (Pipelining / Out-of-order) | Moderate (Strict sequentiality) |
| **Recovery** | Predictable (Deterministic) | Variable (Randomized timeouts) |
| **WAN Usage** | **Preferred** (Fast Paxos variants) | Limited (Leader bottleneck) |
| **Impl. Risk** | Extreme ("Paxos Made Live") | Low (Mature libraries like `etcd`) |

## 4. Selection Framework: Which should you use?

*   **Choose Raft** if you are building metadata services, configuration stores (like `consul` or `etcd`), or standard business microservices. The performance difference is negligible for 95% of applications, and implemented safety is guaranteed.
*   **Choose Paxos** if you are building a **high-performance distributed database** (like Spanner, OceanBase, or TiDB) where Tail Latency in multi-region deployments is your primary competitive constraint.

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Central index.
*   [Majority Quorum](MajorityQuorum) — The voting mechanism used by both protocols.
*   [Leader and Followers](LeaderAndFollowers) — The operational pattern resulting from consensus.
*   [Generation Clock](GenerationClock) — Preventing "zombie" leaders in both protocols.
