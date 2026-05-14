---
title: Heartbeat and Lease Patterns
type: article
cluster: distributed-systems
status: published
date: '2026-05-10'
summary: A set of complementary patterns used to detect node failures (Heartbeat) and manage resource ownership (Lease) in a decentralized environment.
tags:
- distributed-systems
- coordination
- failure-detection
- locks
relations:
- {type: component_of, target_id: 01KQEKGD9XWDSFGH7TWHH63NZT} # Distributed Systems Hub
- {type: related_to, target_id: 01KS7R8X8QYAS6P09AM61S5E2W} # Generation Clock
canonical_id: 01KS7S9Y9QYAS6P09AM61S5E2X
---

# Heartbeat and Lease Patterns

In distributed systems, the lack of a shared clock and a shared memory space makes it difficult to know if a remote node is functioning correctly. **Heartbeats** and **Leases** are two complementary patterns used to manage node presence and resource authority.

## 1. The Heartbeat Pattern (Liveness)

A **Heartbeat** is a periodic signal sent from one node to another (usually a leader or monitor) to indicate that it is still operational.

### The Mechanism
1.  **Interval:** A worker node sends a lightweight message every $T$ seconds.
2.  **Timeout:** If the monitor does not receive a heartbeat within $N \times T$ seconds (the threshold), it assumes the node has failed or been partitioned.
3.  **Action:** The monitor triggers a recovery process, such as reassigning the worker's tasks to another node.

### Weakness: The False Positive
Heartbeats are prone to false positives caused by network jitter or heavy CPU load. Modern systems use the [Phi Accrual Failure Detector](PhiAccrualFailureDetector) to provide a probabilistic suspicion level instead of a binary Up/Down status.

## 2. The Lease Pattern (Authority)

A **Lease** is a time-bound grant of authority over a shared resource. It is essentially a "lock with an expiration date."

### The Problem: The Crash Deadlock
Traditional locks are dangerous in distributed systems. If a node acquires a lock on a database row and then crashes, the resource remains locked forever.

### The Solution
1.  **Contract:** The lock manager grants a lease for a fixed duration (e.g., 60 seconds).
2.  **Holder Maintenance:** The lease holder must explicitly **renew** the lease before it expires.
3.  **Automatic Release:** If the holder crashes, it fails to renew. Once the TTL (Time to Live) expires, the lock manager can safely grant the resource to another node.

## 3. Comparison: Heartbeat vs. Lease

| Feature | Heartbeat | Lease |
| :--- | :--- | :--- |
| **Primary Goal** | Failure detection. | Resource coordination (Mutual Exclusion). |
| **Mechanism** | "I am alive" signal. | "I have the right to act" contract. |
| **Authority** | No rights granted. | Exclusive rights granted for a window. |
| **Direction** | Node $\to$ Monitor. | Client $\leftrightarrow$ Lock Manager. |

## 4. Integration: The Robust Distributed Lock

Most production systems (etcd, ZooKeeper) combine these patterns:
1.  **Acquisition:** A node requests a **Lease** on a resource.
2.  **Renewal:** The node uses a background **Heartbeat** thread to keep the lease alive.
3.  **Protection:** The node provides a [Generation Clock](GenerationClock) (Fencing Token) when acting on the resource to ensure that if the heartbeat fails and the lease expires, its stale actions are rejected.

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Pattern catalog.
*   [Phi Accrual Failure Detector](PhiAccrualFailureDetector) — Advanced heartbeat analysis.
*   [Generation Clock (Epoch)](GenerationClock) — Fencing expired lease holders.
