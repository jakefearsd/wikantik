---
status: active
date: '2026-05-10'
summary: A logical clock pattern used to detect and ignore messages from "zombie"
  leaders or stale nodes during network partitions or GC pauses.
tags:
- distributed-systems
- coordination
- consistency
- fencing
- raft
- zookeeper
type: article
relations:
- type: component_of
  target_id: 01KQEKGD9XWDSFGH7TWHH63NZT
- type: related_to
  target_id: 01KS7P9Z8QYAS6P09AM61S5E2V
- type: related_to
  target_id: 01KS6S8Z8QYAS6P09AM61S5E2O
cluster: distributed-systems
canonical_id: 01KS7R8X8QYAS6P09AM61S5E2W
title: Generation Clock (Epoch)
---

# Generation Clock (Epoch)

The **Generation Clock** (also known as **Term**, **Epoch**, or **Generation Number**) is a critical logical clock pattern in distributed systems. It provides a mechanism to identify which node in a cluster is the *current* legitimate authority, preventing data corruption caused by "Zombie Leaders" (stale leaders that haven't yet realized they've been replaced).

## 1. The Problem: The "Zombie Leader"

In a distributed system, leadership is often managed via [Leases](LeasePattern). If a leader node experiences a long **Stop-the-World GC Pause** or a **Network Partition**, its lease may expire while it is "asleep."

1.  **Lease Expires:** The cluster detects the leader is missing and elects a **New Leader**.
2.  **The Zombie Wakes Up:** The **Old Leader** wakes up and *thinks* it still has a few seconds left on its lease (based on its own local clock).
3.  **Conflict:** Both nodes attempt to write to shared resources (e.g., storage or database), leading to split-brain corruption.

## 2. The Solution: Fencing Tokens

The Generation Clock solves this by attaching a **Fencing Token** to every request.

### The Protocol
1.  **Increment:** Every time a new leader is elected, a central coordinator (or consensus cluster) increments a monotonic counter—the **Generation Clock**.
2.  **Attach:** The current leader is given this value (e.g., `Term: 5`). The leader must include this number in every message it sends to followers or shared resources.
3.  **Validate:** The receiving resource (the "Fenced" component) tracks the highest generation number it has ever seen.
    *   If a request arrives with `Term: 5` and the resource is at `4`, it accepts the write and updates its state to `5`.
    *   If the "Zombie" arrives with `Term: 4`, the resource **rejects** the request because `4 < 5`.

## 3. Terminology in Industry

While the pattern is identical, different systems use unique names for the Generation Clock:

| System | Name | Usage |
| :--- | :--- | :--- |
| **Raft** | **Term** | Incremented for every election cycle. Used to ignore stale RPCs. |
| **ZooKeeper** | **Epoch** | Incremented whenever a new leader starts a session. |
| **Kafka** | **Controller Epoch** | Ensures only one controller node manages the cluster. |
| **Cassandra** | **Generation** | Stored in the Gossip state to detect node restarts. |

## 4. Why "Clock"?

It is called a **Logical Clock** because it measures **Causality**, not seconds. In distributed environments where physical clocks are unreliable (due to drift and skew), the Generation Clock provides a "Happens-Before" relationship: a message with a higher generation number is mathematically guaranteed to be more recent than one with a lower number.

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Theoretical foundations.
*   [Leader and Followers](LeaderAndFollowers) — The primary context for this pattern.
*   [Lease Pattern](LeasePattern) — The mechanism that triggers generation increments.
*   [Lamport Clocks](LamportClocks) — The mathematical ancestor of generation clocks.
