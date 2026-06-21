---
auto-generated: false
type: article
status: active
cluster: databases
date: '2026-04-26'
title: Two-Phase Commit Protocol
tags:
- distributed-transactions
- 2pc
- atomic-commit
- databases
summary: 'Two-Phase Commit (2PC): protocol phases, coordinator failure and the blocking
  problem, comparison with 3PC and Raft, and modern alternatives (Saga, Outbox).'
related:
- ReadReplicasAndReplication
- IdempotencyPatterns
- LeaderElectionAlgorithms
- MessageQueuePatterns
canonical_id: 01KQ0P44Y3JQQV1VH2ZC2Y19VM
---

The Two-Phase Commit (2PC) protocol is an atomic commit protocol for distributed systems, ensuring that all participants either commit or rollback a transaction in unison.

## The Protocol Phases

### Phase 1: Voting (Prepare)
1.  The **Coordinator** sends a `PREPARE` message to all participants.
2.  Each **Participant** executes the local transaction, acquires necessary locks, and writes a "Prepare" record to its Write-Ahead Log (WAL).
3.  Participants respond with `VOTE_COMMIT` or `VOTE_ABORT`.

### Phase 2: Completion (Commit/Abort)
1.  If **all** participants voted `COMMIT`, the Coordinator writes a "Global Commit" record and sends `COMMIT` to all nodes.
2.  If **any** participant voted `ABORT` (or timed out), the Coordinator sends `ROLLBACK` to all nodes.
3.  Participants release locks and acknowledge completion.

## Failure Modes and the "Blocking" Problem

The primary weakness of 2PC is that it is a **blocking protocol**.
- **Coordinator Failure:** If the coordinator crashes after Phase 1 but before sending Phase 2 decisions, participants remain in the "Prepared" state, holding locks indefinitely.
- **Recovery:** Participants cannot unilaterally decide to abort or commit because they don't know the status of other nodes. This requires manual intervention or a specialized recovery manager.

## Comparison: 2PC vs. 3PC vs. Consensus

| Metric | 2PC | 3PC (Three-Phase Commit) | Paxos / Raft |
|---|---|---|---|
| **Atomicity** | Guaranteed | Guaranteed (mostly) | Guaranteed |
| **Blocking** | Yes (on Coord failure) | No (uses Pre-Commit) | No |
| **Network Rounds** | 2 | 3 | 3+ |
| **Partition Tolerance** | Poor | Poor | High (Quorum-based) |
| **Typical Use** | XA Transactions, Java EE | Academic / Specialized | Distributed DBs (Spanner, Cockroach) |

## Implementation Risks
- **Long-Lived Locks:** 2PC holds database locks for the duration of two network round-trips. In high-latency environments, this causes massive contention.
- **Performance:** Latency is determined by the slowest participant ($L_{total} = \max(L_i)$).
- **Split-Brain:** In its standard form, 2PC does not handle network partitions well; if the coordinator is partitioned from the majority, the system stalls.

## Modern Alternatives
Due to the blocking nature of 2PC, modern distributed architectures prefer:
1. **[SagaPattern](SagaPattern):** Replaces atomicity with eventual consistency and compensating transactions.
2. **[OutboxPattern](OutboxPattern):** Atomically links a local DB write with a message emission to a queue.
3. **Deterministic Execution:** (e.g., FaunaDB, Calvin) Pre-orders transactions to eliminate the need for an interactive commit protocol.
