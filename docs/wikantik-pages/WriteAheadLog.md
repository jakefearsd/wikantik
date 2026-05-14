---
title: Write-Ahead Log (WAL)
type: article
cluster: distributed-systems
status: published
date: '2026-05-10'
summary: A fundamental durability pattern that ensures all state changes are persisted to a sequential log before being applied to the data store.
tags:
- distributed-systems
- durability
- storage-engine
- paxos
- raft
relations:
- {type: component_of, target_id: 01KQEKGD9XWDSFGH7TWHH63NZT} # Distributed Systems Hub
- {type: related_to, target_id: 01KS6S8Z8QYAS6P09AM61S5E2O} # Raft/Consensus
canonical_id: 01KS7M5J8QYAS6P09AM61S5E2T
---

# Write-Ahead Log (WAL)

The **Write-Ahead Log (WAL)** is a foundational pattern for providing durability and atomicity in databases and distributed systems. It resolves the conflict between the need for immediate data persistence and the high latency of random-access disk I/O by enforcing a "Log First, Act Later" protocol.

## 1. The Core Protocol

The WAL pattern mandates that every intended change to the system state must be written to a durable, append-only log file before the change is applied to the main data structures (e.g., B-Trees, SSTables, or In-Memory state).

### The Write Sequence
1.  **Request:** A client sends a write request.
2.  **Append:** The system serializes the request into a **Log Record** and appends it to the end of the current log file.
3.  **Synchronize:** The system executes an `fsync` or `force` call to ensure the OS buffers are flushed to physical media.
4.  **Acknowledge:** Once the log is durable, the system acknowledges success to the client.
5.  **Apply:** The change is asynchronously applied to the main data store (the "checkpointed" state).

## 2. Implementation Mechanics

### Sequential vs. Random I/O
The primary performance advantage of WAL is that it converts non-contiguous updates to the data store (which require slow random seeks) into **sequential appends**. Sequential I/O is significantly faster on both spinning disks (HDDs) and flash storage (SSDs).

### Group Commits
To maximize throughput, high-performance systems use **Group Commits**. Instead of calling `fsync` for every transaction, the system batches multiple pending log records into a single disk sync operation, amortizing the latency across many clients.

### Log Record Structure
A robust WAL entry typically includes:
*   **LSN (Log Sequence Number):** A monotonically increasing identifier used for ordering and recovery.
*   **Checksum (CRC):** To detect data corruption during disk read.
*   **Operation Type:** (e.g., `INSERT`, `UPDATE`, `DELETE`).
*   **Redo Data:** The "new" value required to reconstruct the state during recovery.

## 3. Recovery and Checkpointing

Because the WAL grows with every write, it cannot be kept indefinitely. Systems manage this via **Checkpointing**.

1.  **Flush State:** The system periodically flushes all pending in-memory changes to the main data files.
2.  **Record Mark:** It writes a "Checkpoint" record to the WAL, noting the last LSN that is now "safe" in the main data files.
3.  **Truncate:** All log segments older than the checkpoint are deleted or archived.

### Crash Recovery Algorithm
If a system crashes and restarts, it enters the **Recovery Phase**:
*   **Redo:** Starting from the last checkpoint, it replays all subsequent WAL records to restore the data store to the state it was in at the moment of the crash.
*   **Integrity:** Because the log was flushed before the client was acknowledged, no "confirmed" data is ever lost.

## 4. Distributed WAL: The Basis of Consensus

In distributed clusters (using **Paxos** or **Raft**), the WAL is not merely local; it is a **Replicated Log**.

*   **Quorum Replication:** A log entry is only considered "committed" (and thus "durable") once it has been successfully appended and synced on a **majority** of nodes in the cluster.
*   **State Machine Replication:** Once a command is durably replicated in the log, every node applies it to their local state machine in the same order, guaranteeing consistency across the cluster.

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Central index of distributed patterns.
*   [Majority Quorum](MajorityQuorum) — How majority overlap ensures consistency.
*   [Consistent Core](ConsistentCore) — Using WAL-based clusters to manage metadata.
