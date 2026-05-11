---
summary: Central index for the distributed systems cluster — covering core theory
  (CAP, PACELC), consensus algorithms (Paxos, Raft), consistency models, replication
  strategies, and the engineering of fault-tolerant systems at scale.
date: '2026-04-29'
cluster: distributed-systems
related:
- DataEngineeringHub
- NetworkingHub
- DataStructuresHub
canonical_id: 01KQEKGD9XWDSFGH7TWHH63NZT
type: hub
title: Distributed Systems Hub
tags:
- distributed-systems
- cloud-computing
- consensus
- consistency
- fault-tolerance
- hub
status: active
hubs:
- DataStructuresHub
- CloudPlatformsHub
---
# Distributed Systems Hub

Distributed systems are collections of independent computers that appear to users as a single coherent system. This hub organizes Wikantik's content on the theoretical foundations and practical engineering patterns required to build resilient, scalable, and correct systems in an environment of network partitions and node failures.

## Foundations and Theory

The fundamental theorems and laws that govern distributed behavior.

- [Distributed Computing Evolution](DistributedComputingEvolution) — The historical arc from mainframes to global cloud-native architectures
- [CAP Theorem](CapTheorem) — The foundational trade-off between Consistency, Availability, and Partition Tolerance
- [PACELC Theorem](PacelcTheorem) — Expanding CAP to account for the Latency vs. Consistency trade-off during normal operation
- [Lamport Clocks](LamportClocks) — Logical time and the partial ordering of events in distributed systems
- [Vector Clocks](VectorClocks) — Detecting causality and conflicts in concurrent operations
- [Clock Synchronization](ClockSynchronization) — The challenges of physical time: NTP, PTP, and TrueTime

## Consensus and Coordination

Agreeing on state across multiple non-trusting or failing nodes.

- [Paxos and Raft](PaxosAndRaft) — Deep dive into the two most influential consensus protocols
- [Leader Election Algorithms](LeaderElectionAlgorithms) — Strategies for picking a single "coordinator" node in a cluster
- [Byzantine Fault Tolerance](ByzantineFaultTolerance) — Reaching consensus in the presence of malicious or arbitrary failures
- [Two-Phase Commit Protocol](TwoPhaseCommitProtocol) — Atomic transactions across multiple distributed resources
- [Gossip Protocol](GossipProtocol) — Epidemic-style information dissemination for discovery and health checking

## Data Consistency and Replication

Managing shared state across space and time.

- [Consistency Models](ConsistencyModels) — From Strict and Sequential to Eventual and Causal consistency
- [Eventual Consistency](EventualConsistency) — Engineering for high availability and the "last-writer-wins" paradigm
- [CRDT Data Structures](CrdtDataStructures) — Conflict-free Replicated Data Types for mathematically sound state merging
- [Read Replicas and Replication](ReadReplicasAndReplication) — Scaling read throughput and improving disaster recovery
- [Consistent Hashing](ConsistentHashing) — Minimizing re-sharding when nodes join or leave a cluster

## Distribution Patterns

Practical architectures for scalable systems.

- [Distributed Computing Algorithms](DistributedComputingAlgorithms) — Specialized algorithms for sharding, partitioning, and load balancing
- [Kent Beck's Distributed Patterns](KentBeckDistributedPatterns) — Evolutionary architecture, the 3X Framework, and fractal design principles
- [Concurrency in Distributed Systems](ConcurrencyDistributed) — Managing parallel execution and race conditions at scale
- [Actor Model Programming](ActorModelProgramming) — Using isolated actors and message passing to simplify concurrency
- [Erlang Programming Language](ErlangProgrammingLanguage) — The canonical implementation of the actor model for fault-tolerant systems
- [LISP Programming Language](LispProgrammingLanguage) — The historical foundation for symbolic AI and the evolution of memory architectures
- [Event Sourcing](EventSourcing) — Representing state as a sequence of immutable events
- [Database Sharding](DatabaseSharding) — Horizontal partitioning of data across multiple database instances

## Resilience and Reliability

- [Graceful Degradation](GracefulDegradation) — Maintaining system utility when components fail
- [Circuit Breaker Pattern](CircuitBreakerPattern) — Preventing cascading failures in distributed call chains
- [Load Balancing Strategies](LoadBalancingStrategies) — Distributing work effectively across a cluster

## Adjacent Hubs

- [Data Engineering Hub](DataEngineeringHub) — Building data pipelines on distributed substrates
- [Networking Hub](NetworkingHub) — The underlying communication layer
- [Data Structures Hub](DataStructuresHub) — The local structures that serve as nodes in the larger system
