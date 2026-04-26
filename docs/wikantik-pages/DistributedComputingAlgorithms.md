---
title: Distributed Computing Algorithms
type: article
cluster: distributed-systems
status: active
date: '2026-04-25'
tags:
- distributed-systems
- consensus
- vector-clocks
- gossip
- crdt
summary: The core distributed-systems algorithms — clocks, gossip, quorum,
  CRDTs, anti-entropy — what each provides, where each fails, and how
  production systems compose them.
related:
- PaxosAndRaft
- ConsistentHashing
- ConcurrencyDistributed
- ByzantineFaultTolerance
- CrdtDataStructures
hubs:
- DistributedSystems Hub
---
# Distributed Computing Algorithms

Distributed systems are programmed by combining a small set of foundational algorithms. The same primitives — logical clocks, gossip, quorum reads/writes, replication — show up across Cassandra, Kafka, etcd, CockroachDB, S3 internals. Knowing them by name lets you read papers and source code; knowing why each one exists lets you design.

## Logical clocks

Wall-clock time across machines isn't reliable enough to order events. NTP gets you milliseconds; with clock skew, two events on different machines might appear out of order. Logical clocks provide order without reliance on wall time.

### Lamport timestamps

Each process maintains a counter. On any local event, increment. On send, attach the counter. On receive, set local counter to `max(local, received) + 1`.

Property: if event A causes event B (causally), then `lamport(A) < lamport(B)`. The reverse isn't true — concurrent events can have arbitrarily ordered Lamport timestamps.

Used for: total ordering of events when you don't need to detect concurrency.

### Vector clocks

Each process maintains a vector indexed by all processes. Increment own entry on each event. On send, attach. On receive, take element-wise max, then increment own entry.

Property: distinguishes "A happened before B" from "A and B are concurrent." Two timestamps are concurrent if neither is component-wise ≤ the other.

Used for: detecting concurrent updates (Dynamo, Riak), causal consistency systems.

Cost: vector size grows with the number of processes. For very large or dynamic process sets, dotted version vectors and similar refinements help.

### Hybrid Logical Clocks (HLC)

Combines wall clock with logical counter. Approximates wall-clock ordering when clocks are reasonably synchronised; falls back to logical when they diverge.

Used in CockroachDB, MongoDB. Practical compromise — get most of NTP's intuitive ordering with the safety of logical clocks under skew.

### TrueTime (Spanner)

Google has tightly-synchronised clocks (GPS + atomic) with bounded uncertainty intervals. They use this to provide external consistency — actual real-time ordering. Most systems can't afford the hardware to do this.

## Quorum systems

Replication with `N` replicas. Reads see at least `R` replicas; writes commit to at least `W` replicas. Consistency follows from `R + W > N` (the read quorum and write quorum overlap, so reads see at least one replica that has the latest write).

Common settings:

- `N=3, R=2, W=2` — typical Cassandra / DynamoDB. One replica failure doesn't stop the system; reads and writes are quorum-consistent.
- `N=3, R=1, W=3` — fast reads (any replica); slow writes (all). Used for read-heavy workloads where staleness matters.
- `N=5, R=3, W=3` — tolerates two failures; uses majority quorum (Raft-style).

Quorums are simpler than consensus algorithms but provide weaker guarantees (no linearizability without more machinery). For many workloads, "eventually consistent with quorum reads" is sufficient.

## Gossip

Each node periodically picks a random peer; exchanges some state with it. State propagates exponentially across the cluster.

Used for:

- **Membership** — who's in the cluster, who's healthy.
- **Failure detection** — gossiping heartbeats; nodes labelled dead after silence.
- **State dissemination** — propagating cluster-wide config or data.

Cassandra, Consul, Riak use gossip extensively.

Properties:

- **Eventually consistent** — every node eventually learns every state change.
- **Resilient** — no single point of failure; tolerates message loss, node failures.
- **Predictable propagation** — ~`O(log N)` rounds for full propagation.

Cost: noisy if poorly tuned (excessive bandwidth); slow to converge for very large clusters.

## Anti-entropy

When replicas drift apart, anti-entropy reconciles them. Periodic; checks replica states against each other; copies missing or newer data.

- **Read repair** — when a read finds replicas inconsistent, the coordinator fixes them inline.
- **Hinted handoff** — when a replica is unavailable, write to a peer; the peer hands off later.
- **Merkle-tree-based comparison** — compare hashes of segments rather than full data; only sync mismatches. Cassandra, Riak, DynamoDB do this.

Without anti-entropy, replicas drift permanently after any write loss. Don't skip.

## Consensus

Strong agreement on a value among replicas in the presence of failures. Paxos, Raft, Zab are the proven algorithms.

When you need consensus:

- **Linearizable storage.** Reads see all earlier writes. etcd, Spanner, CockroachDB.
- **Distributed locks** with strong correctness.
- **Leader election** with single-leader guarantee.
- **Replicated state machines.**

When you don't:

- Eventually-consistent stores (Dynamo, Cassandra in default mode).
- Coordination via gossip.
- Leadership where "approximately one leader" is acceptable.

Consensus is expensive: each operation requires majority round-trips. Reaching for it when eventual consistency would do is a common over-engineering.

See [PaxosAndRaft] for the algorithms.

## Two-phase commit (2PC)

Coordinator asks all participants "can you commit"; if all say yes, tells them to commit; if any say no, tells them to abort.

Limitations:

- **Blocking on coordinator failure.** Participants don't know whether coordinator was about to commit or abort.
- **Synchronous.** Slow under wide-area latencies.

Use in single-datacenter, tight-latency environments. Don't use across services in microservice architectures — saga (compensating transactions) wins.

Three-phase commit (3PC) tries to eliminate blocking but assumes synchronous networks; rarely used in practice because the assumption fails.

## CRDTs (Conflict-free Replicated Data Types)

Data structures designed so that concurrent updates can be merged automatically without a coordinator.

- **G-Counter** (grow-only counter) — each replica has its own counter; total is the sum. Merge by taking element-wise max.
- **PN-Counter** (positive-negative counter) — two G-Counters, one for increments, one for decrements. Merge each.
- **OR-Set** (observed-remove set) — adds a unique tag to each element; concurrent add/remove resolved by tag set difference.
- **CRDTs for sequences, maps, JSON** — used in collaborative editors (Yjs, Automerge), distributed databases (Riak data types).

Property: any two replicas, given the same set of updates, end up at the same state regardless of order or duplicates. No locking; no coordinator.

Cost: state grows over time without garbage collection; some types are storage-heavy.

See [CrdtDataStructures] for depth.

## Failure detection

How do you tell when a node is dead? Three approaches:

- **Heartbeats** — node sends periodic "I'm alive." Missed heartbeats trigger suspicion. Tunable per network.
- **Phi-accrual** — a continuous suspicion score, not binary alive/dead. Used in Cassandra. Adapts to network conditions.
- **Gossip-based** — distribute heartbeats via gossip. Scales better than all-pairs heartbeats.

The CAP-theorem corollary: in an asynchronous network, you cannot reliably distinguish "dead" from "very slow." All failure detection is heuristic; live with false positives.

## Consistent hashing

A way to map keys to nodes such that adding or removing a node only moves `1/N` of the keys, not all of them.

Hash both keys and nodes onto a ring (e.g., a 32-bit space). A key is owned by the next node clockwise on the ring.

Used in Memcached, Cassandra, DynamoDB, web caching layers. Standard technique for partitioning.

Variants:

- **Virtual nodes** — each physical node maps to multiple positions on the ring; reduces hot spots.
- **Jump consistent hash** — newer; faster than ring-based; less flexible for weighted nodes.

See [ConsistentHashing].

## Vector / counting Bloom filters in distributed contexts

For "does any node have this key" without asking all of them: each node maintains a Bloom filter of its keys; share filters via gossip; query the union.

Used in some distributed caches and for routing in P2P systems.

## A composition example: Cassandra's design

Cassandra combines most of the above:

- **Consistent hashing** for partitioning keys to nodes.
- **Gossip** for membership and metadata.
- **Quorum reads/writes** with tunable `R` and `W`.
- **Vector clocks** (and later, more refined timestamps) for conflict detection.
- **Merkle-tree anti-entropy** for repair.
- **Hinted handoff** for transient failures.
- **No consensus** for operational data — eventually consistent.

Each component does one job; the composition produces a high-availability eventually-consistent distributed database. Reading the Cassandra source is one of the better ways to see distributed-systems algorithms in production.

## Further reading

- [PaxosAndRaft] — consensus algorithms
- [ConsistentHashing] — partitioning details
- [ConcurrencyDistributed] — broader concurrency context
- [ByzantineFaultTolerance] — when nodes might lie
- [CrdtDataStructures] — CRDTs in depth
