---
title: Leader and Followers
type: article
cluster: distributed-systems
status: published
date: '2026-05-10'
summary: A coordination pattern where one node acts as a leader to manage state changes and replicate data to a set of follower nodes.
tags:
- distributed-systems
- coordination
- replication
- high-availability
relations:
- {type: component_of, target_id: 01KQEKGD9XWDSFGH7TWHH63NZT} # Distributed Systems Hub
- {type: related_to, target_id: 01KS7N9Z8QYAS6P09AM61S5E2U} # Majority Quorum
- {type: related_to, target_id: 01KS6S8Z8QYAS6P09AM61S5E2O} # Paxos/Raft
canonical_id: 01KS7P9Z8QYAS6P09AM61S5E2V
---

# Leader and Followers

The **Leader and Followers** pattern (also known as Master-Slave or Primary-Replica) is the primary method for maintaining consistency and scaling read throughput in distributed systems. It simplifies the coordination of concurrent updates by designating a single authoritative node—the **Leader**—to manage all writes to the system state.

## 1. Role Definitions

### The Leader
*   **Authoritative Writes:** Accepts all client write requests and determines the order of execution.
*   **Replication Coordination:** Serializes updates into a [Write-Ahead Log (WAL)](WriteAheadLog) and propagates them to all followers.
*   **State Management:** Tracks the health and replication progress of every follower in the cluster.

### The Followers
*   **Passive Updates:** Receive replication logs from the leader and apply them to their local state in the same order.
*   **Read Scaling:** Serve client read requests to offload work from the leader.
*   **Failover Readiness:** Remain in a consistent state, ready to be elected as the new leader if the current leader fails.

## 2. Replication Strategies

The mode of replication determines the balance between **Consistency** and **Latency**.

| Mode | Protocol | Advantage | Disadvantage |
| :--- | :--- | :--- | :--- |
| **Synchronous** | Leader waits for *all* followers to confirm write. | Strong consistency; no data loss. | Extremely high latency; one slow node blocks the cluster. |
| **Asynchronous** | Leader acknowledges client immediately after local log. | Lowest latency; high write throughput. | Risk of data loss if leader fails before logs propagate. |
| **Semi-Synchronous (Quorum)** | Leader waits for a **Majority** of followers. | Optimal balance of safety and performance. | Requires odd number of nodes; complex election logic. |

## 3. Handling Replication Lag

In distributed systems, followers often lag behind the leader due to network congestion or high load. This creates several "stale read" problems that must be addressed via consistency patterns:

*   **Read-Your-Writes Consistency:** Ensures that if a user updates a record, their subsequent read (even from a follower) will show that update. Implementation often involves tracking a version number or timestamp on the client.
*   **Monotonic Reads:** Ensures that if a user sees a specific state, they will never see an "older" state on a subsequent request (preventing "time travel" bugs).
*   **Fencing:** Using [Generation Clocks](GenerationClock) to ensure that stale messages from an old leader are ignored by followers after a failover.

## 4. Leader Election

A critical requirement of this pattern is a robust mechanism for picking a new leader when the current one fails. This is typically achieved through:
1.  **Consensus Algorithms:** Using **Raft** or **Paxos** to reach agreement on the new leader ID.
2.  **Heartbeats and Leases:** Nodes use [Heartbeats](HeartbeatPattern) to detect leader failure and [Leases](LeasePattern) to prevent multiple nodes from claiming leadership simultaneously.

## 5. Real-World Usage
*   **Relational Databases:** PostgreSQL and MySQL use this for high-availability clusters.
*   **Message Brokers:** Apache Kafka uses it for partition leadership.
*   **Container Orchestrators:** Kubernetes uses a leader for the control plane (via `etcd`).

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Distributed theory index.
*   [Majority Quorum](MajorityQuorum) — The math behind safe replication.
*   [Write-Ahead Log (WAL)](WriteAheadLog) — The mechanism for log propagation.
*   [Generation Clock](GenerationClock) — Preventing "zombie" leader split-brain.
