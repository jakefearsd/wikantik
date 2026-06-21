---
date: '2026-05-10'
summary: Central index for the distributed systems cluster — covering core theory
  (CAP, PACELC), consensus algorithms (Paxos, Raft), consistency models, replication
  strategies, and the engineering of fault-tolerant systems at scale.
cluster: distributed-systems
related:
- DataEngineeringHub
- NetworkingHub
- DataStructuresHub
- ConcurrencyDistributed
canonical_id: 01KQEKGD9XWDSFGH7TWHH63NZT
type: hub
title: Distributed Systems Hub
status: active
hubs:
- DataStructuresHub
- CloudPlatformsHub
- EngineeringDisciplineHub
tags:
- distributed-systems
- cloud-computing
- consensus
- consistency
- fault-tolerance
- hub
---
# Distributed Systems Hub

Distributed systems are collections of independent computers that appear to users as a single coherent system. This hub organizes Wikantik's content on the theoretical foundations and practical engineering patterns required to build resilient, scalable, and correct systems in an environment of network partitions and node failures.

## Foundations and Theory

The fundamental theorems and laws that govern distributed behavior.

- [CAP Theorem](CapTheorem) — The foundational trade-off between Consistency, Availability, and Partition Tolerance
- [PACELC Theorem](PacelcTheorem) — Expanding CAP to account for the Latency vs. Consistency trade-off
- [Lamport Clocks and Vector Clocks](LamportAndVectorClocks) — Logical time, partial ordering, and detecting causality
- [Hybrid Logical Clocks (HLC)](HybridLogicalClocks) — Bridging physical and logical time for cloud-native databases
- [Clock Synchronization](ClockSynchronization) — The challenges of physical time: NTP, PTP, and TrueTime

## Consensus and Replication

Agreeing on state across multiple non-trusting or failing nodes.

- [Majority Quorum](MajorityQuorum) — The mathematical foundation for consistency ($R + W > N$)
- [Write-Ahead Log (WAL)](WriteAheadLog) — Ensuring durability through sequential log appends
- [Leader and Followers](LeaderAndFollowers) — Managing state changes and replication lag
- [Paxos and Raft](PaxosAndRaft) — Deep dive into the two most influential consensus protocols
- [Generation Clock (Epoch)](GenerationClock) — Detecting "zombie" leaders and fencing stale requests
- [Byzantine Fault Tolerance](ByzantineFaultTolerance) — Reaching consensus in the presence of malicious failures

## Data Consistency and Scaling

Managing shared state across space and time.

- [Consistency Models](ConsistencyModels) — From Strict and Sequential to Eventual and Causal consistency
- [Database Sharding and Consistent Hashing](DatabaseSharding) — Horizontally partitioning data with minimal re-mapping cost
- [CQRS and Event Sourcing](CQRSAndEventSourcing) — High-throughput reactive state and audit-perfect history
- [CRDT Data Structures](CrdtDataStructures) — Conflict-free Replicated Data Types for mathematically sound state merging
- [Eventual Consistency](EventualConsistency) — Engineering for high availability and "last-writer-wins"

## Distribution Patterns

Practical architectures for scalable and reliable systems.

- [The Saga Pattern](SagaPattern) — Managing distributed transactions via local steps and compensation
- [Idempotent Receiver](IdempotentReceiver) — Guaranteeing safety under at-least-once delivery
- [Actor Model Programming](ActorModelProgramming) — Using isolated actors and message passing to simplify concurrency
- [Erlang Programming Language](ErlangProgrammingLanguage) — The canonical implementation of the actor model
- [LISP Programming Language](LispProgrammingLanguage) — The historical foundation for symbolic and neuro-symbolic AI

## Resilience and Reliability

- [Bulkhead Pattern](BulkheadPattern) — Partitioning resources to contain the blast radius of failures
- [Sidecar and Ambassador Patterns](SidecarPattern) — Offloading operational plumbing to helper containers
- [Phi Accrual Failure Detector](PhiAccrualFailureDetector) — Probabilistic liveness detection for jittery networks
- [Circuit Breaker Pattern](CircuitBreakerPattern) — Preventing cascading failures in distributed call chains
- [Graceful Degradation](GracefulDegradation) — Maintaining system utility when components fail

## Adjacent Hubs

- [Generative AI Hub](GenerativeAIHub) — Orchestrating agentic workforces at scale
- [Data Engineering Hub](DataEngineeringHub) — Building data pipelines on distributed substrates
- [Networking Hub](NetworkingHub) — The underlying communication layer
