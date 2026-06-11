---
title: Majority Quorum
type: article
cluster: distributed-systems
status: published
date: '2026-05-10'
summary: A mathematical pattern that ensures consistency and fault tolerance by requiring overlapping majorities for read and write operations.
tags:
- distributed-systems
- consistency
- fault-tolerance
- mathematics
relations:
- {type: component_of, target_id: 01KQEKGD9XWDSFGH7TWHH63NZT} # Distributed Systems Hub
- {type: related_to, target_id: 01KS7M5J8QYAS6P09AM61S5E2T} # WAL
canonical_id: 01KS7N9Z8QYAS6P09AM61S5E2U
---

# Majority Quorum

The **Majority Quorum** pattern is the mathematical foundation for strong consistency and high availability in distributed clusters. It ensures that a system can tolerate node failures without losing data or allowing conflicting updates, provided a majority of the cluster remains operational.

## 1. The Quorum Inequality

A distributed system consists of $N$total nodes. To maintain strong consistency (Linearizability), the number of nodes required for a successful write ($W$) and the number of nodes polled for a read ($R$) must satisfy the following inequality:$$R + W > N$$### The Pigeonhole Principle
The logic relies on the **Pigeonhole Principle**: if the sets of nodes used for reading and writing overlap by at least one node, that overlapping node acts as the "witness" that carries the most recent state.

## 2. Mathematical Proof

We can prove that$R + W > N$guarantees that every read will see the latest write.

1.  Let$S$be the set of all$N$nodes.
2.  Let$V_w$be the set of$W$nodes that acknowledge the latest write.
3.  Let$V_r$be the set of$R$nodes polled for a read.
4.  The number of nodes that **did not** participate in the write is$N - W$.
5.  If the read set$V_r$were to contain *no* nodes from$V_w$, then$V_r$must be a subset of the non-write set:$V_r \subseteq (S \setminus V_w)$.
6.  This is only possible if the size of the read set is less than or equal to the size of the non-write set:$R \le N - W$.
7.  Rearranging gives$R + W \le N$.
8.  By contradiction, if **$R + W > N$**, there must be at least one node$n$that is in both sets:$n \in (V_w \cap V_r)$.

## 3. Common Quorum Configurations

The selection of$R$and$W$values allows architects to tune the system for specific workload profiles:

| Strategy | Configuration | Strength | Weakness |
| :--- | :--- | :--- | :--- |
| **Strict Majority** |$W = \lfloor N/2 \rfloor + 1$<br>$R = \lfloor N/2 \rfloor + 1$| Balanced. Tolerate$\approx 50\%$failures. | High coordination overhead. |
| **Write-Heavy** |$W = N$<br>$R = 1$| Extremely fast reads (1 node). | A single node failure blocks all writes. |
| **Read-Heavy** |$W = 1$<br>$R = N$| Extremely fast writes (1 node). | A single node failure blocks all reads. |

## 4. Fault Tolerance Calculation

For a cluster of size$N$using strict majority ($W = R = \lfloor N/2 \rfloor + 1$), the number of nodes that can fail ($f$) while maintaining availability is:

$$
f = \lfloor \frac{N-1}{2} \rfloor
$$

| Cluster Size ($N$) | Max Failures ($f$) | Majority Required || :--- | :--- | :--- |
| 3 | 1 | 2 |
| 5 | 2 | 3 |
| 7 | 3 | 4 |

**Architectural Note:** Distributed clusters almost always use **odd numbers** of nodes. Increasing from 3 to 4 nodes does not increase the fault tolerance (both can only survive 1 failure), but it increases the number of nodes that must be coordinated for a majority (from 2 to 3), actually decreasing performance.

## 5. Usage in Industry
*   **Cassandra / Dynamo:** Allows per-request configuration of$R$and$W$ (e.g., `QUORUM`, `LOCAL_QUORUM`, `ONE`).
*   **Raft / Paxos:** Mandates a strict majority for every log entry to ensure safe leader transitions and durability.

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Theoretical foundations.
*   [Write-Ahead Log (WAL)](WriteAheadLog) — Ensuring the majority write is durable.
*   [Leader Election Algorithms](LeaderElectionAlgorithms) — Picking the node that coordinates quorums.
