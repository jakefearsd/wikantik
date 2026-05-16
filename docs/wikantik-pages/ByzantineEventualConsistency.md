---
title: Byzantine Eventual Consistency and Advanced CRDTs
canonical_id: 01KRQEMDQPKA7F565WKYXQAKRM
cluster: distributed-systems
relations:
- type: extension_of
  target_id: 01KQ0P44P30XWDX3W1RY1DMB96
- type: component_of
  target_id: 01KQEKG9XWDSFGH7TWHH63NZT
- type: alternative_to
  target_id: 01KS6S8Z8QYAS6P09AM61S5E2O
type: article
tags:
- crdt
- eventual-consistency
- byzantine-fault-tolerance
- automerge
- json-crdt
- peer-to-peer
summary: Technical deep-dive into Byzantine Eventual Consistency (BEC) and JSON-CRDT
  breakthroughs. Details the 2025 solutions for 'move' operations in collaborative
  JSON and decentralized access control.
status: active
date: '2026-05-15'
---

# Byzantine Eventual Consistency and Advanced CRDTs

By 2026, Conflict-free Replicated Data Types (CRDTs) have moved beyond simple counters to support complex, hierarchical data structures in **adversarial, peer-to-peer environments.**

## 1. JSON-CRDT Breakthroughs (2025)

The "Holy Grail" of collaborative software was the ability to **Move** a folder or a subtree without causing data duplication or cycles.

### A. The "Move" Operation
Landmark research in 2024-2025 (implemented in **Automerge**) solved this using a **Cycle-Detection Algorithm**.
*   **The Conflict**: If User A moves Folder 1 into Folder 2, and User B moves Folder 2 into Folder 1 concurrently, a naive system creates a cycle, and the data vanishes from the tree.
*   **The 2026 Solution**: CRDTs now use deterministic "Winning Rules" combined with temporal metadata to ensure a single, consistent parent for every node, regardless of move order.

### B. json-joy and RGA
The **json-joy** specification emerged as the 2025 standard for high-performance JSON-CRDTs. It uses a **Replicated Growable Array (RGA)** algorithm to handle nested strings and arrays with minimal "tombstone" overhead (the metadata leftover from deleted items).

## 2. Byzantine Eventual Consistency (BEC)

BEC is the consistency model for systems that must converge even when some nodes are **malicious (Byzantine)**.

### A. Local-First Access Control
Traditional ACLs require a central server. In BEC, the **ACL itself is a CRDT**.
*   **The Challenge**: Equivocation (a malicious node telling User A they have access, but telling User B they don't).
*   **The Solution**: BEC uses **Merkle DAGs** and **Threshold Signatures** to ensure that once a permission is granted/revoked, honest nodes eventually converge on that state, rendering the malicious node's lie irrelevant.

---
**External Deep Dive:**
- [CRDT (Wikipedia)](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type) — Detailed look at commutative and convergent replication.
- [Eventual Consistency (Wikipedia)](https://en.wikipedia.org/wiki/Eventual_consistency) — Theoretical foundations of relaxed consistency.
- [Merkle Tree (Wikipedia)](https://en.wikipedia.org/wiki/Merkle_tree) — Foundations of cryptographic verification in BEC.

**See Also:**
- [CRDT Data Structures](CrdtDataStructures) — Foundations.
- [Byzantine Fault Tolerance](ByzantineFaultTolerance) — The adversarial model.
- [Eventual Consistency](EventualConsistency) — The baseline guarantee.
