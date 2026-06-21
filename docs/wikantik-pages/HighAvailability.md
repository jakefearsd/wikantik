---
status: active
date: '2026-05-15'
summary: An engineering deep-dive into High Availability (HA) architectures. Covers
  the mathematical formulation of 'nines', the critical distinction between RTO and
  RPO, and failover topologies.
tags:
- high-availability
- rto
- rpo
- resilience
- failover
- distributed-systems
type: article
relations:
- type: component_of
  target_id: 01KQEKG9XWDSFGH7TWHH63NZT
- type: extension_of
  target_id: 01KQ0P44PSDYPG5MY8RH2DM5GT
canonical_id: 01KRQFYP7Y1TEAX48C06A1Z3JR
cluster: distributed-systems
title: 'High Availability: RTO, RPO, and Resilience'
---

# High Availability (HA): Engineering for Resilience

**High Availability (HA)** is the characteristic of a system that aims to ensure an agreed level of operational performance (usually uptime) for a higher than normal period. It is the practical application of distributed redundancy to combat hardware failure, network partitions, and software bugs.

## 1. The Mathematics of "Nines"
Availability ($A$) is formally defined by Mean Time Between Failures (MTBF) and Mean Time To Repair (MTTR):

$$
A = \frac{MTBF}{MTBF + MTTR}
$$

Industry standards describe availability in "nines":
*   **Three Nines (99.9%)**: ~8.77 hours of downtime per year. Typical for internal tools.
*   **Four Nines (99.99%)**: ~52.6 minutes of downtime per year. Standard for commercial SaaS.
*   **Five Nines (99.999%)**: ~5.26 minutes of downtime per year. Required for telecom and critical financial infrastructure.

Achieving higher nines exponentially increases cost and architectural complexity. It requires moving from reactive recovery to proactive, active-active topologies.

## 2. RTO and RPO: The Dual Metrics of Disaster Recovery
When failures occur, they are measured against two distinct Service Level Objectives (SLOs):

*   **Recovery Time Objective (RTO)**: The maximum acceptable delay between the interruption of service and the restoration of service. (How long can we be down?)
*   **Recovery Point Objective (RPO)**: The maximum acceptable amount of data loss measured in time. (How much data can we lose?)

A system with synchronous replication might have an RPO of 0 (no data lost) but an RTO of 5 minutes (time taken for a leader election to complete).

## 3. Core HA Topologies

### A. Active-Passive (Cold/Warm Standby)
One primary node handles all traffic. A secondary node sits idle, receiving asynchronous replication.
*   **Pros**: Simple to implement, avoids split-brain scenarios.
*   **Cons**: Wasted compute resources. Failover is slow (high RTO) because the passive node must boot up or assume the leader role.

### B. Active-Active (Multi-Primary)
Multiple nodes handle traffic simultaneously. State is synchronized across all nodes, often using advanced conflict resolution like CRDTs.
*   **Pros**: Near-zero RTO. Traffic is load-balanced, utilizing all hardware.
*   **Cons**: Extremely complex. Requires conflict resolution for concurrent writes (violates strict linearizability).

## 4. Modern Resilience Patterns
*   **Cell-Based Architecture**: Partitioning the system into isolated "cells" to strictly bound the blast radius of a failure. (See [Cell-Based Architecture](CellBasedArchitecture)).
*   **Redundancy at Every Layer**: From N+1 power supplies in the data center to multi-AZ deployment and active-active global load balancing (e.g., Anycast IP routing).

---
**See Also:**
- [Cell-Based Architecture](CellBasedArchitecture)
- [Leader and Followers](LeaderAndFollowers)
- [CAP Theorem](CapTheorem)
