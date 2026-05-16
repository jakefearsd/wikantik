---
title: 'Mysticeti: Latency-Optimal DAG Consensus'
canonical_id: 01KRQEMDP68WGMAJPSXFDMPKQS
cluster: distributed-systems
relations:
- type: extension_of
  target_id: 01KS6S8Z8QYAS6P09AM61S5E2O
- type: component_of
  target_id: 01KQEKG9XWDSFGH7TWHH63NZT
- type: influenced_by
  target_id: 01KQ0P44MTAXE77VEN5ARES10A
type: article
tags:
- consensus
- bft
- dag
- mysticeti
- distributed-systems
- latency-optimization
summary: Technical deep-dive into Mysticeti, the state-of-the-art uncertified DAG
  consensus protocol. Details the theoretical 3-round latency bound and the shift
  from certification-heavy to uncertified DAG architectures in 2025.
status: active
date: '2026-05-15'
---

# Mysticeti: Latency-Optimal DAG Consensus

Mysticeti represents the 2025 "state-of-the-art" in Byzantine Fault Tolerant (BFT) consensus. It achieves the **theoretical minimum latency** for ordering transactions in a decentralized network, significantly improving upon previous DAG-based designs like Narwhal and Bullshark.

## 1. Why the Paradigm Shift: From Certified to Uncertified

Before 2024, DAG-based consensus relied on **Certified DAGs** (e.g., Bullshark). 
*   **The Problem**: In a certified DAG, each node's "block" requires a quorum of signatures (2f+1) from other nodes before it can be added to the graph. This "certification" step introduces 2 message delays per round.
*   **The Consequence**: Typical BFT DAGs required ~6 message delays to reach finality, leading to latencies of 2–3 seconds.

### The Mysticeti Solution: Uncertified DAGs
Mysticeti removes the certification requirement. Nodes simply sign their own blocks and share them. The "voting" happens implicitly through the references in the DAG structure.
*   **Result**: The number of round trips is cut in half. Mysticeti achieves finality in **3 message rounds**, bringing latency down to **~390ms–450ms** on global networks.

## 2. Theoretical Lower Bound: The 3-Round Proof

BFT theory proves that 3 message delays is the absolute lower bound for reaching consensus in an asynchronous environment with $f$ failures.
1.  **Round 1**: Propose (Node shares its block).
2.  **Round 2**: Reference (Other nodes include the block in their DAG).
3.  **Round 3**: Commit (The DAG structure becomes deep enough to guarantee ordering).

## 3. Architecture: Mysticeti-C and Mysticeti-FPC

Modern implementations (notably in the **Sui Network**) use a hybrid approach to maximize both throughput and latency:

*   **Mysticeti-C (Consensus)**: The core protocol for "shared objects" (e.g., a decentralized exchange where many users interact with the same state). It uses the full DAG ordering mechanism.
*   **Mysticeti-FPC (Fast Path + Consensus)**: A specialized path for "owned objects" (e.g., a simple asset transfer from A to B). If no conflict is detected, FPC bypasses the DAG ordering entirely, reaching finality in **~250ms**.

---
**External Deep Dive:**
- [Byzantine Fault Tolerance (Wikipedia)](https://en.wikipedia.org/wiki/Byzantine_fault_tolerance) — Foundations of adversarial consensus.
- [Directed Acyclic Graph (Wikipedia)](https://en.wikipedia.org/wiki/Directed_acyclic_graph) — Properties of the underlying data structure.
- [Consensus (Wikipedia)](https://en.wikipedia.org/wiki/Consensus_(computer_science)) — Broad history of Paxos, Raft, and BFT.

**See Also:**
- [Byzantine Fault Tolerance](ByzantineFaultTolerance)
- [Leader Election Algorithms](LeaderElectionAlgorithms)
- [Distributed Computing Evolution](DistributedComputingEvolution)
