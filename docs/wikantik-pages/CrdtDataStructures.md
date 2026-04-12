---
title: Crdt Data Structures
type: article
tags:
- state
- merg
- crdt
summary: Conflict-Free Replicated Data Types (CRDTs) Welcome.
auto-generated: true
---
# Conflict-Free Replicated Data Types (CRDTs)

Welcome. If you’ve spent any significant amount of time wrestling with distributed state management—the kind of problem that makes you question the very nature of time and consensus—you know that the simple act of updating a counter across three geographically separated nodes is anything but trivial. You’ve likely wrestled with Paxos, Raft, or perhaps the sheer, beautiful terror of [eventual consistency](EventualConsistency).

This tutorial assumes you are already intimately familiar with distributed consensus algorithms, [vector clocks](VectorClocks), causal dependencies, and the fundamental limitations imposed by the [CAP theorem](CapTheorem). We are not here to explain *what* a distributed system is, nor are we going to waste time explaining that network partitions are, in fact, a persistent feature of reality.

We are here to dissect Conflict-Free Replicated Data Types (CRDTs)—a sophisticated class of [data structures](DataStructures) designed not merely to *tolerate* conflicts, but to *guarantee* their resolution deterministically, regardless of the network topology, message ordering, or sheer incompetence of the participating replicas.

Consider this less a tutorial and more a highly detailed, somewhat cynical, but ultimately necessary deep-dive into the mathematical machinery that allows us to treat distributed state convergence as a solvable, predictable problem.

***

## 1. State Divergence in Optimistic Replication

Before we can appreciate the elegance of CRDTs, we must fully appreciate the monstrosity they tame.

In traditional, strongly consistent distributed systems (those adhering strictly to linearizability), updates are serialized through a single, authoritative point of coordination (the leader, the quorum, etc.). This is safe, predictable, and, frankly, often too slow for modern user-facing applications that demand high availability (A) even when the network is shaky (P).

When we pivot to **optimistic replication**—the paradigm that allows replicas to operate independently, assuming the network *will* eventually heal—we embrace the possibility of divergence.

### 1.1 The Failure of Simple Merging

Imagine a simple key-value store replicated across three nodes: $R_A, R_B, R_C$.

1.  $R_A$ reads $K=5$.
2.  $R_B$ reads $K=5$.
3.  $R_A$ increments $K$ to 6 and sends the update.
4.  $R_B$ increments $K$ to 6 and sends the update.
5.  $R_C$ reads $K=5$ (because it hasn't seen the updates yet).
6.  $R_C$ increments $K$ to 6 and sends the update.

If we simply apply the updates sequentially based on arrival time (Last Write Wins, LWW), the result is arbitrary and depends entirely on the network jitter. If $R_A$'s update arrives last, the value is 6. If $R_B$'s update arrives last, the value is 6. But what if $R_A$ and $R_B$ both incremented based on stale reads? The final state is non-deterministic without a global clock, which, as we know, is a myth.

The core problem is that standard data structures (like simple integers or strings) do not possess an inherent, mathematically defined *merge function* that is guaranteed to be commutative, associative, and idempotent when applied to divergent states.

### 1.2 Convergence by Design

CRDTs solve this by fundamentally changing the contract. Instead of treating the data structure as a black box that requires external consensus, a CRDT *is* the consensus mechanism.

A CRDT is a data structure that, when updated independently on multiple replicas, guarantees that the resulting state, when merged using a specific, defined merge function ($\sqcup$), will converge to the same final state on all replicas, regardless of the order in which the updates or states are received.

Mathematically, this means the merge operation must satisfy the properties of a **Join Semilattice**.

***

## 2. Semilattices and Join Operations

To truly understand CRDTs, one must step away from the application layer and into the lattice theory. This is where the rigor—and the necessary headache—begins.

### 2.1 Defining the Lattice Structure

A set $S$ equipped with a binary operation $\sqcup$ (the join operation) forms a **join semilattice** if the operation $\sqcup$ is:

1.  **Commutative:** $a \sqcup b = b \sqcup a$. (The order of merging doesn't matter.)
2.  **Associative:** $(a \sqcup b) \sqcup c = a \sqcup (b \sqcup c)$. (Grouping doesn't matter.)
3.  **Idempotent:** $a \sqcup a = a$. (Applying the merge operation multiple times with the same inputs yields no change.)

These three properties are the bedrock. They ensure that if $R_A$ merges its state with $R_B$'s state, and then $R_C$ merges its state with the result, the final state is identical to if $R_C$ had merged with $R_A$ first, and then the result merged with $R_B$'s state.

### 2.2 State-Based vs. Operation-Based CRDTs

The literature generally divides CRDTs into two primary families, which dictate *how* the merge operation is executed. Understanding this distinction is crucial for selecting the right tool for the job.

#### A. State-Based CRDTs (CvRDTs)

In a CvRDT, the replica does not exchange the *operations* (e.g., "increment by 1"). Instead, it periodically exchanges its *entire local state* (or a delta of the state).

*   **Mechanism:** The merge function $\text{Merge}(S_A, S_B)$ is applied directly to the two states $S_A$ and $S_B$ to produce the converged state $S_{final}$.
*   **Convergence:** Convergence is achieved by repeatedly applying the join operation ($\sqcup$) until the state stabilizes.
*   **Tradeoff:** Simplicity of implementation (just merge the states). However, the bandwidth cost can be prohibitive. If the state grows large (e.g., a massive document history), transmitting the entire state becomes infeasible.

#### B. Operation-Based CRDTs (CmRDTs)

In a CmRDT, the replica only sends the *operation* (the delta) that occurred locally (e.g., $\text{Increment}(+1)$).

*   **Mechanism:** The merge function is applied to the incoming operation $\text{Op}$ against the local state $S$: $S' = \text{Apply}(\text{Op}, S)$.
*   **Convergence:** Convergence relies on the guarantee that the set of operations applied is commutative and associative.
*   **Tradeoff:** Extremely bandwidth efficient, as only small deltas are sent. However, they are highly sensitive to message delivery guarantees. If an operation is lost, the state *will not* converge correctly unless the protocol layer handles guaranteed delivery (e.g., using reliable messaging queues).

> **Expert Insight:** Many modern, robust systems utilize a hybrid approach. They might use CmRDTs for rapid, low-latency updates (e.g., typing in a chat), but fall back to transmitting state snapshots (CvRDT semantics) periodically or upon detecting significant divergence to ensure eventual consistency.

***

## 3. Canonical CRDT Implementations

The true mastery of CRDTs comes from understanding *why* certain structures require specific mathematical scaffolding. Let's examine the most common types.

### 3.1 Counters

A simple counter is the textbook example of failure. If $R_A$ increments by 1 and $R_B$ increments by 1, a naive merge might result in 1 or 2, depending on which update arrives last. We need a structure where the merge operation is inherently additive and commutative.

#### Positive-Negative (PN) Counters

PN Counters are the canonical solution. Instead of storing a single integer $N$, the counter stores two separate, independently managed structures: a **Positive Counter** ($P$) and a **Negative Counter** ($N$).

1.  **State Representation:** The state is a map of replica IDs to counts: $S = \{ (R_1: p_1, n_1), (R_2: p_2, n_2), \dots \}$.
2.  **Increment Operation:** To increment by $k$ on replica $R_i$, we update $p_i \leftarrow p_i + k$.
3.  **Decrement Operation:** To decrement by $k$ on replica $R_i$, we update $n_i \leftarrow n_i + k$.
4.  **Merge Operation ($\sqcup$):** The final value $V$ is calculated by summing all positive contributions and subtracting all negative contributions:
    $$V = \sum_{i} p_i - \sum_{i} n_i$$

**Why this works:** The merge operation is simply the summation of the components. Since addition of integers is inherently commutative and associative, the final sum is guaranteed to be the same regardless of the order in which the partial sums arrive.

**Edge Case Consideration (The "G-Counter"):** A simpler version, the Grow-Only Counter (G-Counter), only allows increments (i.e., $N$ is always zero). Its state is simply a map of replica IDs to positive counts. This is the simplest form of CRDT, relying only on the commutative property of addition.

### 3.2 Sets

Sets are notoriously difficult because deletion is inherently destructive. If $R_A$ deletes element $X$, and $R_B$ concurrently adds $X$, which operation "wins"? If we use LWW, the result is arbitrary.

#### Observed-Remove Sets (OR-Sets)

OR-Sets are the standard solution, combining the principles of Grow-Only Sets (G-Sets) and tracking deletions explicitly.

1.  **State Representation:** The state must track two distinct sets of elements:
    *   $A$: The set of elements ever *added* (the "Added Set").
    *   $R$: The set of elements that have been *removed* (the "Removed Set").
2.  **Adding an Element $x$:** Add $x$ to $A$.
3.  **Removing an Element $x$:** Add $x$ to $R$.
4.  **Membership Test:** An element $x$ is considered present in the set if and only if $x \in A$ **AND** $x \notin R$.
5.  **Merge Operation ($\sqcup$):** The merge operation is simply the union of the corresponding components:
    $$A_{final} = A_A \cup A_B$$
    $$R_{final} = R_A \cup R_B$$

**The Power of OR-Sets:** Because the merge operation is a simple set union ($\cup$), and set union is idempotent, commutative, and associative, the resulting state is guaranteed to be consistent. The removal operation is treated as an *addition* to the removal set, which is perfectly safe under the lattice structure.

#### Two-Phase Sets (2P-Sets)

For scenarios requiring more fine-grained control (e.g., tracking *who* removed an item), 2P-Sets are used. They track additions and removals separately, often associating metadata (like the replica ID and timestamp) with the removal marker itself, allowing for more complex conflict resolution policies beyond simple existence checks.

### 3.3 Registers and Maps

A simple key-value register $K \to V$ is the most problematic structure because the assignment operation ($K \leftarrow V$) is inherently destructive. If $R_A$ sets $K=10$ and $R_B$ sets $K=20$, which value survives?

#### Last Write Wins (LWW) with Vector Clocks

While LWW is often discouraged in favor of mathematical guarantees, when dealing with registers, the most practical CRDT approach often involves augmenting the value with metadata that *enforces* a total ordering, effectively turning the conflict resolution into a deterministic rule.

The standard technique is to attach a **Vector Clock** or a **Hybrid Logical Clock (HLC)** to the value.

1.  **State Representation:** The value stored is not just $V$, but a tuple: $(V, \text{Clock})$.
2.  **Write Operation:** When writing $V'$, the replica updates its clock $C'$ and stores $(V', C')$.
3.  **Merge Operation ($\sqcup$):** When merging $(V_A, C_A)$ and $(V_B, C_B)$:
    *   If $C_A$ causally precedes $C_B$ (i.e., $C_A \prec C_B$), the result is $(V_B, C_B)$.
    *   If $C_B$ causally precedes $C_A$ (i.e., $C_B \prec C_A$), the result is $(V_A, C_A)$.
    *   If $C_A$ and $C_B$ are concurrent (neither precedes the other), a tie-breaker must be invoked (e.g., lexicographical comparison of the clock vectors, or simply choosing the value from the replica with the highest ID).

**The Caveat:** This approach *reintroduces* a dependency on ordering, but it makes that dependency explicit and mathematically traceable via the clock mechanism, satisfying the convergence requirement deterministically.

***

## 4. CRDT Sequences

If counters are simple arithmetic and sets are set theory, CRDT Sequences (or Text CRDTs) are where the complexity truly escalates. They must handle insertion, deletion, and reordering while maintaining a globally consistent document structure.

The challenge here is that the position of an element is not intrinsic to the element itself; it is defined by its relationship to its neighbors.

### 4.1 The Need for Positional Metadata

To solve this, CRDT sequences cannot simply store the characters. They must store *positional metadata* alongside the characters. The most famous and robust approach is based on **RGA (Replicated Growable Array)** or its modern descendants, such as those used in Yjs or Automerge.

The core idea is to assign a unique, totally ordered identifier (a "positional coordinate") to every character inserted.

### 4.2 Positional Coordinates and Unique Identifiers

Instead of using indices (which are inherently local and meaningless across replicas), we use a coordinate system that guarantees total ordering across all replicas.

A common technique involves generating identifiers that are pairs of numbers, often structured as:
$$\text{ID} = (\text{SiteID}, \text{SequenceNumber})$$

However, to handle insertions *between* existing elements, a more sophisticated coordinate system is required, often involving fractional or geometric representations.

Consider a coordinate system based on two dimensions, $D_1$ and $D_2$, where the position of a character $C$ is defined by $(x_C, y_C)$.

*   **Insertion:** When inserting $C$ between $C_{left}$ and $C_{right}$, the new coordinate $(x_C, y_C)$ must be chosen such that it falls strictly between the coordinates of the neighbors in the total ordering defined by the system.
*   **Merging:** When merging two sequences, $S_A$ and $S_B$, the merge operation must merge the underlying coordinate sets while respecting the total order.

### 4.3 The Merge Logic for Sequences

The merge logic is complex because it must reconcile two potentially different total orderings of the same set of characters.

1.  **Character Reconciliation:** The set of characters present must be reconciled using the OR-Set logic (i.e., if a character was deleted on one side, it must be deleted on the other).
2.  **Positional Reconciliation:** For every character that exists on both sides, its final position must be determined by merging the coordinate sets. If $C$ has coordinates $(x_A, y_A)$ in $S_A$ and $(x_B, y_B)$ in $S_B$, the final coordinate $(x_{final}, y_{final})$ must be the one that maintains the total order relative to all other characters in the merged set.

This process is computationally intensive. It moves the complexity from the *application logic* (what the user sees) into the *data structure's internal representation* (the coordinates).

> **Expert Note on Performance:** The overhead of maintaining these total orderings is significant. While mathematically sound, the constant need to generate, store, and compare complex positional identifiers means that sequence CRDTs often have higher memory overhead and slower merge times compared to simple key-value stores. This is the necessary tax paid for perfect convergence in rich text editing.

***

## 5. Advanced Considerations

For researchers pushing the boundaries, the discussion cannot stop at the canonical examples. We must address the practical, often messy, realities of deployment.

### 5.1 Causal Ordering vs. Total Ordering

This distinction is critical and often misunderstood.

*   **Causal Ordering:** A happens before B if A *must* happen before B (e.g., reading a document before editing it). This is tracked perfectly by Vector Clocks. CRDTs generally respect causality.
*   **Total Ordering:** A total ordering implies that for any two events A and B, we can definitively say $A < B$ or $B < A$.

CRDTs, by definition, aim for convergence, which implies a form of total ordering on the *final state*. However, the *updates* themselves are often only causally ordered. The CRDT machinery is what bridges the gap, imposing a total order on the *result* by ensuring that the merge operation is deterministic, even if the input stream was only partially ordered.

### 5.2 Network Overhead and Bandwidth Cost Analysis

The choice between CvRDTs and CmRDTs is fundamentally a cost-benefit analysis involving bandwidth vs. latency.

| Feature | Operation-Based (CmRDT) | State-Based (CvRDT) |
| :--- | :--- | :--- |
| **Data Transmitted** | Small, atomic operations ($\Delta$) | Full state snapshot ($S$) or large delta |
| **Bandwidth Cost** | Low (Excellent for high-frequency updates) | High (Scales with state size) |
| **Latency Profile** | Low (Updates propagate quickly) | Variable (Depends on snapshot frequency) |
| **Dependency** | Requires reliable message delivery | Tolerates message loss (if snapshots are sent) |
| **Complexity** | High (Requires robust message queuing) | Moderate (Simple merge logic) |

**Research Direction:** Research into *delta encoding* for CvRDTs is ongoing. Instead of sending the entire state $S$, one aims to send $\Delta S = S_{new} \setminus S_{old}$, but this requires the replica to maintain a perfect, versioned history of its own state, which adds complexity.

### 5.3 Handling Tombstones and Garbage Collection

In systems like OR-Sets, elements are marked as deleted (tombstoned). If the system relies purely on state merging, these tombstones accumulate forever, leading to unbounded state growth—a classic memory leak in distributed systems.

**The Garbage Collection Problem:** How do you safely remove a tombstone?

1.  **Time-Based Expiration:** The simplest approach. If a tombstone is older than $T$ seconds, it is assumed to be garbage. This is brittle; what if the system clock drifts?
2.  **Version-Based Expiration:** The system must track the *version* at which the tombstone was created. If a subsequent, higher-version write overwrites the entire structure, the tombstone is implicitly invalidated. This requires the CRDT state itself to be versioned by a consensus mechanism, which defeats the purpose of pure optimistic replication.
3.  **The "Garbage Collection CRDT":** Some advanced models propose a specialized CRDT that tracks the *liveness* of the data. When a replica knows that all other replicas have acknowledged a state version *after* the tombstone was created, it can safely prune the tombstone. This requires a mechanism akin to "acknowledgment receipt" which pushes the system slightly back toward requiring some form of coordination.

### 5.4 CRDTs vs. Consensus Protocols

It is vital for the expert researcher to understand that CRDTs are **not** a replacement for consensus protocols like Paxos or Raft; they are a *complement* or an *alternative* for specific use cases.

*   **Consensus Protocols (Raft/Paxos):** Guarantee **Strong Consistency** (Linearizability) by forcing all writes through a single, agreed-upon sequence. They are excellent for metadata, configuration, or financial ledgers where absolute, immediate truth is paramount.
*   **CRDTs:** Guarantee **Eventual Consistency** by allowing writes to proceed independently. They are excellent for collaborative, high-availability data (chat messages, document editing, shopping carts).

**The Hybrid Model:** The most sophisticated systems use both. For example, a system might use Raft to agree on the *schema* and *metadata* (e.g., "The document must be a CRDT Sequence"), but use CRDTs internally to manage the *content* of the document itself.

***

## 6. Summary and Conclusion

We have traversed the mathematical landscape from semilattices to positional coordinates. If one were to distill the essence of CRDTs into a single, actionable takeaway for a researcher:

**CRDTs are not just data structures; they are formal proofs of convergence.** They encode the rules of merging directly into the data type itself, transforming the chaotic problem of distributed state management into a predictable, mathematically bounded operation.

The evolution of CRDTs reflects the evolution of distributed computing itself: a move away from the idealized, synchronous world of academic theory (where global clocks exist) toward the messy, asynchronous reality of the internet (where partitions are the norm).

### Final Thoughts

1.  **Don't Assume Simplicity:** Never assume that because a data type seems simple (like a map), its distributed counterpart will be simple. The complexity is always hidden in the merge function.
2.  **Know Your Cost:** Always quantify the cost. Is the bandwidth cost of a CvRDT state snapshot worth the simplicity of its merge logic compared to the operational complexity of a CmRDT?
3.  **The Edge Cases are the Research:** The most active areas of research lie in optimizing the garbage collection of tombstones, developing more efficient positional coordinate systems for sequences, and creating formal proofs that bound the memory growth of complex, multi-layered CRDT compositions.

Mastering CRDTs requires fluency in distributed systems theory, lattice theory, and advanced data structure design. It is a deep, rewarding, and occasionally maddening subject. Now, go forth and build systems that *actually* work when the network inevitably decides to be difficult.
