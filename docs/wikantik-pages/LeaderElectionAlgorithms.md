---
canonical_id: 01KQ0P44RQ9XTZT2ERA1ZCVXWY
title: Leader Election Algorithms
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: Technical analysis of distributed consensus (Raft, Paxos) and leader election mechanisms for coordination and write-serialization.
auto-generated: false
tags:
- leader-election
- consensus
- raft
- paxos
- distributed-systems
related:
- GossipProtocol
- MessageQueuePatterns
---

Leader election ensures that exactly one node in a cluster holds the authority to coordinate operations (e.g., write-serialization, task scheduling). Failure to maintain a unique leader results in **Split-Brain**, where multiple nodes claim authority, leading to state divergence and data corruption.

## Why Leaders Matter

1.  **Write Serialization:** Single-master systems (PostgreSQL, MySQL) require a leader to order transactions.
2.  **Coordination:** Schedulers (Kubernetes, Nomad) need one source of truth for task placement.
3.  **Efficiency:** It is faster to delegate to a leader than to run a full consensus round (Paxos/Raft) for every single read or write.

## Consensus Algorithms: The Modern Standard

Modern systems use consensus-based election to prevent split-brain via a **Quorum ($N/2 + 1$)**.

### 1. Raft
Raft is designed for understandability and implements a strict leader hierarchy.
- **Election Timeout:** A follower becomes a **Candidate** if it hasn't heard a leader heartbeat.
- **Term:** A monotonically increasing counter. A candidate with a higher term wins.
- **Log Matching:** A candidate cannot be elected unless its log is as up-to-date as the majority.

### 2. Paxos
The foundation of distributed consensus. Mathematically exhaustive but complex to implement.
- **Proposer/Acceptor:** Nodes negotiate a proposal number.
- **Zab (Zookeeper):** A Paxos variant optimized for high-throughput broadcast.

## Simple Election Patterns (Non-Consensus)

| Algorithm | Mechanism | Pros | Cons |
|---|---|---|---|
| **Bully** | Highest Node ID wins. | Simple. | Flapping if highest ID is unstable. |
| **Ring** | Nodes pass election tokens in a circle. | Deterministic. | High latency in large rings. |
| **Leases** | Leader holds a TTL-based lock in a store (etcd/Consul). | Easy to integrate. | Dependency on the lock store. |

## Guarding Against Split-Brain: Fencing

When a leader is suspected dead and a new one is elected, the old leader might still be alive (e.g., during a long GC pause). **Fencing** prevents the zombie leader from causing harm.

- **Fencing Tokens:** Every election increments a token. The backend (Storage/DB) only accepts writes from the highest known token.
- **STONITH (Shoot The Other Node In The Head):** Physically power cycling the suspected failed node.

## Comparison: Leader Election vs. Gossip

| Metric | Leader Election | [Gossip Protocol](GossipProtocol) |
|---|---|---|
| **Consistency** | Strong (Linearizable) | Eventual |
| **Coordination** | High (Stop-the-world) | Low (Peer-to-peer) |
| **Network Cost** | $O(N^2)$ during election | $O(N)$ constant |
| **Use Case** | Shared State, Transactions | Membership, Metrics |

## Implementation Strategy
- **Don't implement Raft from scratch.** Use proven libraries (etcd `raft`, Hashicorp `raft`).
- **Use external coordinators** (etcd, Zookeeper) for application-level leadership rather than building it into every microservice.
- **Set aggressive heartbeats but conservative election timeouts** to minimize flapping.
