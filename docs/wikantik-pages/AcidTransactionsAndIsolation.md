---
canonical_id: 01KQ0P44GYQVKPXXFRY7TTW69C
title: Acid Transactions And Isolation
type: article
tags:
- read
- transact
- lock
summary: This tutorial is not for the junior developer who merely needs to know that
  BEGIN TRANSACTION exists.
auto-generated: true
---
# ACID Transactions and Isolation

For those of us who spend our careers wrestling with the inherent chaos of shared state, the concept of database transactions is less a feature and more a fundamental necessity—a digital pact against entropy. When multiple processes attempt to read, write, and modify the same data concurrently, the resulting state can quickly devolve into a computational nightmare of lost updates, dirty reads, and logical inconsistencies.

This tutorial is not for the junior developer who merely needs to know that `BEGIN TRANSACTION` exists. This is for the architect, the researcher, and the systems engineer who needs to understand the *mechanisms* by which database systems enforce data integrity under extreme load, the theoretical trade-offs between consistency guarantees and throughput, and the subtle anomalies that even the most robust isolation levels can fail to prevent without careful application-level design.

We are dissecting the core pillars: **ACID**, the spectrum of **Isolation Levels**, and the sophisticated **Concurrency Control** algorithms that underpin modern database management systems (DBMS).

---

## I. The ACID Paradigm

The ACID properties—Atomicity, Consistency, Isolation, and Durability—are not merely a checklist; they represent a contract between the application developer and the database engine regarding the transactional boundary. To treat them as mere buzzwords is to fundamentally misunderstand the engineering challenge they solve.

### A. Atomicity ($\text{A}$)
Atomicity dictates that a transaction must be treated as a single, indivisible unit of work. Either *all* operations within the transaction commit successfully, or *none* of them do. If a failure occurs midway (power loss, network partition, explicit rollback), the system must revert to the state it held *before* the transaction began.

**Expert Consideration:** The implementation of atomicity relies heavily on Write-Ahead Logging (WAL) or transaction logs. The log records the *intent* to change data before the change is applied to the physical data files. This log is the ultimate source of truth for recovery, allowing the system to "redo" committed operations or "undo" incomplete ones.

### B. Consistency ($\text{C}$)
Consistency ensures that a transaction brings the database from one valid state to another valid state. This is the most abstract property because its enforcement is often *application-defined*. The database engine guarantees that the structural integrity (e.g., foreign key constraints, data type adherence) is maintained, but the *business logic* consistency (e.g., "the sum of debits must equal the sum of credits") must be enforced either by constraints or by the transaction logic itself.

**Expert Consideration:** When designing for consistency, one must model the invariants of the system. If an invariant is violated, the transaction *must* fail, regardless of whether the underlying SQL syntax is valid.

### C. Isolation ($\text{I}$)
Isolation is the property that guarantees that concurrent transactions execute as if they were running sequentially (serially). This is the property most frequently misunderstood and the most complex to implement efficiently. It dictates that the intermediate, uncommitted changes of one transaction must be invisible to all other concurrent transactions.

**Expert Consideration:** Isolation is a spectrum, not a binary switch. The choice of isolation level is a direct, quantifiable trade-off between **data correctness (consistency)** and **system throughput (concurrency)**. Higher isolation guarantees mean more aggressive locking or versioning, which inherently increases contention and reduces parallelism.

### D. Durability ($\text{D}$)
Durability guarantees that once a transaction has been successfully committed, its changes are permanent and will survive any subsequent system failure, including catastrophic hardware failure.

**Expert Consideration:** Durability is achieved by ensuring that the commit record, along with all necessary preceding changes, has been flushed from the volatile memory (RAM) buffers to non-volatile storage (disk/SSD) and, ideally, acknowledged by a quorum of replicas in a distributed setup.

---

## II. Anomalies in Shared State

To appreciate the solution (isolation levels), we must first rigorously define the problems that arise when isolation fails. These failures manifest as *anomalies*—observable deviations from the expected serial execution order.

Consider a simple banking scenario: Account A has \$1000; Account B has \$1000. We want to transfer \$100 from A to B.

If two transfers happen concurrently (T1: A $\to$ B; T2: B $\to$ A), and isolation is weak, we might observe:

1.  **Dirty Read:** Transaction T1 reads the uncommitted balance of A from T2. T2 modifies A to \$500 but hasn't committed yet. T1 reads \$500, processes based on this false data, and commits. T2 then rolls back, reverting A to \$1000. T1 has based its logic on data that never officially existed.
2.  **Non-Repeatable Read:** Transaction T1 reads the balance of A (\$1000). Before T1 finishes, Transaction T2 commits an update to A (e.g., a deposit of \$200), changing A's balance to \$1200. When T1 reads A again to verify its calculations, it reads \$1200, contradicting its initial read of \$1000.
3.  **Phantom Read:** Transaction T1 executes a query, counting all users in a department (e.g., 10 users). Before T1 commits, Transaction T2 inserts a new user into that department. When T1 executes the *exact same query* again, it now counts 11 users. The "phantom" row appeared out of nowhere, violating the expectation that the dataset structure remains stable during the transaction's lifespan.

These three anomalies—Dirty, Non-Repeatable, and Phantom—form the theoretical basis for the standard isolation levels.

---

## III. Isolation Levels

Database systems typically offer a hierarchy of isolation levels. Understanding this spectrum is understanding the fundamental trade-off curve between safety and speed.

### A. Read Uncommitted (The Danger Zone)
**Guarantee:** None, beyond the basic ACID contract itself.
**Mechanism:** Reads the absolute latest data written to the buffer, regardless of whether the writing transaction has committed or rolled back.
**Anomalies Permitted:** Dirty Reads, Non-Repeatable Reads, Phantom Reads.
**Use Case:** Almost never appropriate for business logic. Perhaps only for high-volume, non-critical monitoring or preliminary data sampling where [eventual consistency](EventualConsistency) is acceptable.
**Expert Warning:** Using this level is akin to trusting a rumor whispered in a crowded room; it might be true for a moment, but it has no lasting commitment.

### B. Read Committed (The Industry Baseline)
**Guarantee:** A transaction will only read data that has been successfully committed by other transactions.
**Mechanism:** The DBMS typically uses mechanisms (like MVCC or immediate locking) to ensure that any read operation only sees committed snapshots.
**Anomalies Permitted:** Non-Repeatable Reads, Phantom Reads.
**Why it's common:** It offers a significant performance boost over higher levels because it avoids holding locks across the entire transaction duration. Most modern default configurations (e.g., PostgreSQL, Oracle) default to this level or something equivalent.
**The Limitation:** If T1 reads A (\$1000) and T2 commits an update to A (\$1200), T1 reading A again *will* see \$1200. This is the Non-Repeatable Read.

### C. Repeatable Read (The Default Trap)
**Guarantee:** If a transaction reads a specific row multiple times, it will always see the exact same data, provided that row hasn't been deleted.
**Mechanism:** This level typically requires the DBMS to place shared locks (or equivalent versioning markers) on all rows *read* by the transaction, preventing other transactions from modifying or deleting those specific rows until the reading transaction commits.
**Anomalies Permitted:** Phantom Reads.
**The Caveat (The Phantom Problem):** While it prevents reading the *same* row differently, it does *not* prevent the *insertion* of new rows that match the query criteria. If T1 queries `SELECT * FROM Users WHERE DeptID = 10`, and T2 inserts a new user in DeptID 10, T1's subsequent query will see the new row—a Phantom Read.
**MySQL/InnoDB Context:** MySQL's default `REPEATABLE READ` level is famous for its implementation using gap locks (or next-key locks), which are designed specifically to prevent phantom reads by locking the *gaps* between existing index entries, effectively simulating serializability for reads, but this behavior is highly implementation-specific and must be understood deeply.

### D. Serializable (The Gold Standard of Safety)
**Guarantee:** The execution of concurrent transactions yields the exact same result as if they had been executed one after another, serially.
**Mechanism:** This is the strongest guarantee. To achieve this, the DBMS must enforce strict control over all reads and writes, often requiring range locks or predicate locks that cover the *potential* set of data that could be read or written.
**Anomalies Permitted:** None (theoretically).
**The Cost:** Performance degradation. Because the system must simulate serial execution, it must hold locks (or maintain complex version chains) for the entire duration of the transaction, drastically increasing the probability of deadlocks and reducing overall concurrency.

---

## IV. Implementation Mechanics

The difference between these levels is not theoretical; it is implemented via concrete concurrency control mechanisms. For an expert audience, understanding the trade-offs between these mechanisms is paramount.

### A. Pessimistic Concurrency Control (Locking)
Pessimistic concurrency assumes that conflicts *will* happen, so it prevents them preemptively by acquiring locks.

#### 1. Two-Phase Locking (2PL)
2PL is the classic, textbook approach. It mandates that every transaction must proceed through two distinct phases:

*   **Growing Phase:** The transaction can acquire any necessary locks (Shared $\text{S}$ locks for reading, Exclusive $\text{X}$ locks for writing). It cannot release any locks during this phase.
*   **Shrinking Phase:** The transaction can release locks, but it cannot acquire any new locks.

**The Problem with Pure 2PL:** Strict 2PL (where all locks are held until commit) guarantees serializability but is notoriously prone to **deadlocks**. Deadlocks occur when two or more transactions are each waiting for a resource held by another transaction in a circular dependency (e.g., T1 waits for L2 held by T2, while T2 waits for L1 held by T1). The DBMS must implement a deadlock detection mechanism (e.g., wait-for graphs) and abort one of the transactions (the "victim") to break the cycle.

#### 2. Lock Granularity
The efficiency of locking hinges on granularity:
*   **Coarse-Grained Locking (Table Level):** Simple, but terrible for concurrency. If one transaction needs one row, it locks the entire table.
*   **Fine-Grained Locking (Row Level):** Ideal for concurrency, but complex to manage and can lead to excessive lock overhead.
*   **Predicate/Range Locking:** The most advanced form, required for true serializability. Instead of locking a specific row, the system locks the *condition* (the predicate) that defined the set of rows. This is what prevents phantom reads.

### B. Optimistic Concurrency Control (Versioning)
Optimistic control assumes that conflicts are rare. Instead of locking resources upfront, transactions proceed assuming no conflict will occur. Conflicts are only detected and resolved *at commit time*.

**Mechanism:** This usually involves version numbers, timestamps, or checksums attached to records.

**The Process:**
1.  Read the data along with its current version identifier ($V_{read}$).
2.  Perform all necessary local computations.
3.  At commit, the system checks: Is the current committed version ($V_{current}$) still equal to $V_{read}$?
    *   If $V_{current} = V_{read}$: Success. The transaction commits, and the version number is atomically incremented ($V_{new} = V_{read} + 1$).
    *   If $V_{current} \neq V_{read}$: Failure. Another transaction modified the data in the interim. The current transaction must abort and retry the entire operation.

**Trade-off:** Excellent throughput when contention is low. Catastrophic failure (high abort rate) when contention is high, as the system enters a retry loop.

### C. Multi-Version Concurrency Control (MVCC)
MVCC is the dominant paradigm in modern, high-throughput databases (PostgreSQL, CockroachDB, modern MySQL/InnoDB). It is a sophisticated hybrid that attempts to give the *read* operations the benefits of optimistic control while providing the *write* operations the safety of locking.

**Core Concept:** Instead of overwriting data in place, MVCC creates and maintains multiple versions of a data item. When a transaction reads data, it is given a "snapshot" of the database as it existed at the moment the transaction started (or at a specified point in time).

**How it Works (Simplified):**
1.  **Versioning:** Every row version is tagged with transaction IDs (TIDs) or timestamps: `(Data, Start_TxID, End_TxID)`.
2.  **Reading:** A transaction $T_X$ starting at time $T_{start}$ only reads versions where $T_{start} \le \text{Start\_TxID}$ and $\text{End\_TxID} > T_{start}$. It effectively ignores any versions that were created *after* it started or any versions that were committed *after* it started but before it read the data.
3.  **Writing:** A writing transaction $T_Y$ creates a *new* version of the row, marking it with its own TID and setting the `End_TxID` of the *previous* version to $T_Y$'s start time.

**MVCC and Isolation Levels:**
*   **Read Committed:** Reads the latest *committed* version visible to the current transaction.
*   **Repeatable Read:** Reads the version that was committed *at the start* of the transaction, effectively locking the snapshot view for the duration.
*   **Serializable:** Requires the most complex management, often involving predicate locking *on top of* the MVCC structure to prevent phantom reads that might slip through the versioning mechanism.

---

## V. Advanced Topics

To satisfy the requirements of researching new techniques, we must move beyond the standard textbook definitions and examine the theoretical boundaries and advanced implementations.

### A. Snapshot Isolation (SI)
Snapshot Isolation is perhaps the most frequently misunderstood concept in modern database theory, as it is often confused with, yet distinct from, Serializable isolation.

**Definition:** SI guarantees that every transaction sees a consistent snapshot of the database taken at the moment the transaction began. It prevents all anomalies (Dirty, Non-Repeatable, Phantom) *relative to the snapshot*.

**The Key Difference from Serializable:**
Serializable isolation guarantees that the *outcome* is equivalent to serial execution. Snapshot Isolation guarantees that the *view* is consistent with a single point in time.

**The Anomaly SI *Can* Allow: Write Skew**
The critical weakness of SI is that it does not prevent **Write Skew**.

**Write Skew Example:**
Assume two constraints:
1.  The total number of employees in Department A must be $\ge 1$.
2.  The total number of employees in Department B must be $\ge 1$.

T1 (Employee Transfer): Moves an employee from A to B. T1 reads: A count=10, B count=10. T1 calculates: New A count=9, New B count=11. T1 commits.
T2 (Employee Transfer): Moves an employee from B to A. T2 reads: A count=10, B count=10. T2 calculates: New A count=11, New B count=9. T2 commits.

If T1 and T2 run concurrently under Snapshot Isolation:
1. T1 reads (A=10, B=10).
2. T2 reads (A=10, B=10).
3. T1 writes (A=9, B=11).
4. T2 writes (A=11, B=9).

The final state (A=11, B=9) is valid *locally* for both transactions, but the *combined* state violates the business invariant (A $\ge 1$ AND B $\ge 1$) if the initial state was (A=1, B=1). The system allowed the state where A=0 and B=0 to be bypassed, violating the global invariant, even though no single transaction violated its own local constraints.

**Mitigation:** To prevent Write Skew, one must either:
1.  Elevate the transaction to `SERIALIZABLE` isolation (which will detect the conflict via predicate locking).
2.  Explicitly model the invariant check using a single, encompassing transaction that reads *both* required values before writing *either* value.

### B. Distributed Transactions and Two-Phase Commit (2PC)
When a single logical transaction spans multiple, independent database nodes (e.g., updating inventory in Node A and updating accounting ledger in Node B), the concept of ACID must be extended across network boundaries. This is where the **Two-Phase Commit (2PC)** protocol comes into play.

**The Role of the Coordinator:** A designated coordinator node manages the transaction lifecycle.

**Phase 1: Prepare (Voting)**
1.  The Coordinator sends a `PREPARE` message to all participating Resource Managers (RMs).
2.  Each RM executes the transaction locally, writes all necessary changes to its local durable log (WAL), and ensures it *can* commit.
3.  If successful, the RM replies `VOTE_COMMIT`. If any RM fails, it replies `VOTE_ABORT`.

**Phase 2: Commit/Abort (Decision)**
1.  **If ALL RMs voted COMMIT:** The Coordinator sends a global `COMMIT` message. Each RM makes the changes permanent and releases resources.
2.  **If ANY RM voted ABORT (or timed out):** The Coordinator sends a global `ABORT` message. Each RM rolls back its local changes using its log.

**Expert Caveat: The Blocking Problem:**
2PC is notoriously brittle. If the Coordinator fails *after* sending the `PREPARE` message but *before* sending the final `COMMIT`/`ABORT` decision, the participating RMs are left in an **indefinite prepared state**. They have locked resources and cannot unilaterally commit or abort because they do not know the global decision. This blocking state is the primary reason why many modern distributed systems are moving away from strict 2PC towards consensus algorithms like Paxos or Raft, which offer stronger guarantees regarding coordinator failure.

### C. The Role of Causality and Temporal Consistency
For researchers building novel systems, the concept of *causality* is paramount. A transaction must not only be isolated but must also respect the temporal order of events.

In advanced systems, especially those leveraging [event sourcing](EventSourcing) or CRDTs (Conflict-free Replicated Data Types), the focus shifts from *locking* to *ordering*. Instead of asking, "What is the state now?" (which implies a single point in time), the system asks, "What is the state after applying this sequence of events?"

This requires mechanisms that can process operations idempotently and deterministically, regardless of the order in which they arrive at different replicas. This moves the problem from traditional ACID transaction management into the realm of distributed consensus and functional [data structures](DataStructures).

---

## VI. Choosing the Right Tool for the Job

The ultimate takeaway for an expert is that there is no single "best" isolation level. The optimal choice is dictated by a rigorous analysis of the application's invariants, the expected contention profile, and the acceptable latency budget.

| Scenario | Required Guarantee | Recommended Level/Mechanism | Trade-off Accepted |
| :--- | :--- | :--- | :--- |
| **Simple Reads/Reporting** | Visibility of committed data. | Read Committed (MVCC) | Potential for Non-Repeatable Reads. |
| **Inventory Check (Read-Modify-Write)** | Must see the same data twice. | Repeatable Read (MVCC with Gap Locking) | Potential for Phantom Reads (if not using predicate locks). |
| **Financial Transfer (Critical Path)** | Absolute data integrity, no anomalies. | Serializable (or explicit application-level locking) | High risk of deadlocks and reduced throughput. |
| **High-Volume, Low-Conflict Writes** | High throughput, eventual consistency acceptable. | Snapshot Isolation (with application-level Write Skew checks) | Risk of Write Skew anomalies. |
| **Multi-Node Update** | Global atomic commitment. | Two-Phase Commit (2PC) or Consensus Protocol (Raft/Paxos) | Blocking risk if the coordinator fails. |

### Final Considerations for Research

When researching new techniques, keep these points front-of-mind:

1.  **The Cost of Serializability:** Always quantify the performance hit of moving from `READ COMMITTED` to `SERIALIZABLE`. If the application can tolerate the *risk* of a Write Skew anomaly (and can detect it via application logic), Snapshot Isolation is vastly superior to the performance penalty of full serializability.
2.  **Locking vs. Versioning:** Understand that locking mechanisms (Pessimistic) are excellent for *preventing* conflicts by blocking access, while versioning mechanisms (MVCC/Optimistic) are excellent for *detecting* conflicts and allowing high concurrency by allowing reads to proceed unimpeded.
3.  **The Boundary of the Database:** True system design requires acknowledging that the database is only one component. The application layer must be designed to handle the failure modes inherent in the chosen isolation level.

In conclusion, ACID transactions provide the necessary scaffolding for reliable computation. Isolation levels are the knobs that tune the tension between theoretical perfection (Serializability) and practical performance (Read Committed). Mastering this domain means understanding not just *what* the levels are, but *why* they fail, and *how* to architect around those failure modes when the theoretical guarantees are too costly to enforce in the real world.
