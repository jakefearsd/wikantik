---
canonical_id: 01KQ12YDW4P2B6GRRJY43T5TTB
title: Paxos and Raft
type: article
cluster: distributed-systems
status: active
date: '2026-05-24'
tags:
- consensus
- paxos
- raft
- distributed-systems
- replication
summary: Technical comparison of Paxos and Raft consensus protocols, their implementation trade-offs in modern databases (CockroachDB, etcd), and failure mode analysis.
auto-generated: false
---
# Paxos and Raft

Consensus algorithms allow a distributed system to agree on a single value or a sequence of operations despite node failures or network partitions. **Paxos** (the foundation) and **Raft** (the ergonomic standard) are the two protocols powering almost all linearizable systems in production.

## Paxos: The Theoretical Root

Proposed by Leslie Lamport, Paxos is a family of protocols. "Basic Paxos" only agrees on a single value; "Multi-Paxos" extends this to a sequence of values (a log).

- **Role Flexibility:** Paxos does not require a strict leader. Any node can be a Proposer.
- **Complexity:** The protocol is famously difficult to implement correctly because the specification leaves gaps for membership changes and log compaction.
- **Production Use:** Google's **Chubby** and **Spanner** are the primary users of refined Multi-Paxos variants.

## Raft: Designed for Implementation

Raft was designed as an alternative to Paxos with "understandability" as a primary goal. It decomposes consensus into three sub-problems: **Leader Election**, **Log Replication**, and **Safety**.

- **Strong Leader:** All writes flow through a single leader. This simplifies the state machine but creates a potential bottleneck.
- **Log Continuity:** Raft enforces that logs have no "gaps," making recovery significantly simpler than in Paxos.
- **Production Use:** **etcd** (Kubernetes), **CockroachDB**, **Consul**, and **TiKV**.

## Comparison Table

| Feature | Paxos (Multi-Paxos) | Raft |
|---|---|---|
| **Leader Requirement** | Weak/Optional (can have multiple proposers) | Strong (Strict single leader) |
| **Log Structure** | Can have gaps | Must be contiguous |
| **Understandability** | Low (Academic/Hard) | High (Designed for engineers) |
| **Membership Changes** | Often handled as a separate, custom layer | Integrated (Joint Consensus) |
| **Failover Latency** | Can be very low with leases | Dependent on heartbeat/timeout settings |

## Implementation Detail: Raft Log Replication

In Raft, the leader appends a client command to its log and sends `AppendEntries` RPCs to followers. A command is **committed** only after it is replicated to a majority of nodes.

```go
// Simplified Raft AppendEntries logic (Go-ish)
func (l *Leader) Replicate(command Command) {
    l.log.Append(Entry{Term: l.currentTerm, Data: command})
    
    successCount := 1 // Count self
    for _, follower := range l.peers {
        go func(f *Peer) {
            if f.CallAppendEntries(l.log.Last()) {
                atomic.AddInt32(&successCount, 1)
            }
        }(follower)
    }
    
    // Wait for majority before responding to client
    for int(atomic.LoadInt32(&successCount)) <= len(l.peers)/2 {
        time.Sleep(1 * time.Millisecond)
    }
    l.commitIndex = l.log.LastIndex()
}
```

## Critical Failure Modes

### 1. Split Brain (Partitioning)
In a 5-node cluster, if 2 nodes are partitioned from the other 3, the group of 2 cannot reach a majority and will stop accepting writes. The group of 3 remains operational. This is the **CP (Consistency/Partition-tolerance)** behavior from the CAP theorem.

### 2. Leader Flapping
If the network is flaky, the cluster might spend all its time electing new leaders without ever committing a log entry. 
**Mitigation:** Use randomized election timeouts (e.g., 150ms to 300ms) to prevent multiple nodes from starting elections simultaneously.

### 3. Log Divergence
If a leader crashes after sending `AppendEntries` to only one node, the next leader must force all followers to match its log. Raft handles this by finding the last common index and overwriting everything after it on the followers.

## Further Reading
- [[ConsistentHashing]] — How to partition data once consensus is achieved.
- [[CapTheorem]] — The theoretical bounds of distributed systems.
- [[ByzantineFaultTolerance]] — What happens when nodes are malicious, not just failing.
