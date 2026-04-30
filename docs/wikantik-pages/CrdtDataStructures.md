---
canonical_id: 01KQ0P44P30XWDX3W1RY1DMB96
title: CRDT Data Structures
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: Conflict-free Replicated Data Types — data structures that converge automatically
  across distributed replicas without coordination. The math behind them, the practical
  variants, and where they fit in real systems.
tags:
- crdt
- distributed-systems
- replication
- consistency
related:
- EventualConsistency
- ByzantineFaultTolerance
hubs:
- DistributedSystemsHub
---
# CRDT Data Structures

Conflict-free Replicated Data Types (CRDTs) are data structures that converge to the same value across replicas, regardless of update order, without requiring coordination.

For systems with multiple writers and high availability requirements, CRDTs offer strong eventual consistency without the complexity of consensus.

## The problem CRDTs solve

In a distributed system with multiple replicas:
- Updates happen anywhere
- Network partitions exist
- We want availability (CAP's A)

Without coordination, concurrent updates conflict. How do replicas reconverge?

Options:
- Last-write-wins: simple but lossy
- Multi-value: surface conflicts to application
- Consensus before each write: slow, high coordination
- CRDT: automatic merge with mathematical guarantees

CRDTs trade some flexibility for automatic correctness.

## Strong eventual consistency

Eventual consistency: replicas eventually converge if updates stop.

Strong eventual consistency: replicas with the same set of updates have the same state — regardless of order.

CRDTs guarantee SEC by design.

## Two flavors

### State-based (CvRDTs)

Each replica has state. Replicas exchange full state. Merge function:
- Commutative: merge(A, B) = merge(B, A)
- Associative: merge(merge(A, B), C) = merge(A, merge(B, C))
- Idempotent: merge(A, A) = A

These properties define a join-semilattice.

### Operation-based (CmRDTs)

Replicas exchange operations. Operations must commute.

Requires reliable, exactly-once delivery (or operations must be idempotent).

State-based vs op-based: different constraints; choose based on network reliability and bandwidth.

## Common CRDTs

### G-Counter (grow-only counter)

State: vector of counts per replica.

Increment: replica i increments its slot.

Merge: max per slot.

Value: sum of slots.

Used for: counters that only increase (page views, likes).

### PN-Counter

Two G-counters: one for increments, one for decrements.

Value: positive sum - negative sum.

Used for: counters that can increase or decrease.

### G-Set

Add-only set.

Add: insert element.

Merge: union.

Used for: append-only collections.

### 2P-Set

Add and remove. Two G-Sets: added and removed.

Once removed, can't re-add.

### LWW-Element-Set

Last-write-wins set. Each element has add timestamp and remove timestamp.

Element is present if add timestamp > remove timestamp.

Used for: sets with eventual additions and removals.

### OR-Set (Observed-Remove Set)

More sophisticated. Each add gets a unique tag. Remove removes specific tags.

Removes only what was observed; concurrent adds are preserved.

### Lists / sequences

Hard to do well. Various designs:
- RGA (Replicated Growable Array)
- LSEQ
- Logoot
- Causal Trees / Yjs algorithm

Used for collaborative text editing.

### Maps

Replicated map structures, often using OR-Set semantics for keys + CRDTs for values.

### Counters with constraints

Bounded counters, monotonic-only counters — for specific domain semantics.

## Causal context

For correct merge, often need to track causality:
- Vector clocks
- Version vectors
- Dotted version vectors

These track which updates each replica has seen.

## Practical CRDT systems

### Yjs

JavaScript CRDT library. Used for real-time collaboration (Y.js, Tiptap, Liveblocks).

### Automerge

JSON-like CRDT. Used in collaborative apps.

### Riak

Distributed database with CRDT support (counters, sets, maps).

### Redis

CRDB feature in Redis Enterprise.

### Antidote

Distributed database designed around CRDTs.

### Operational Transformation (OT)

Alternative to CRDT for collaborative editing. Used historically (Google Docs).

CRDTs increasingly preferred for distributed scenarios.

## Use cases

### Collaborative editing

Multiple users editing same document. CRDTs for text, spreadsheets, drawings.

### Shopping carts

Distributed shopping cart. Add items concurrently; merge cleanly.

### Counters and metrics

Distributed counters across regions.

### Configuration

Distributed config that multiple admins update.

### Offline-first apps

Mobile apps that work offline; sync when online. CRDT structures merge cleanly.

### IoT / edge

Devices update locally; sync to cloud. CRDTs handle concurrent changes.

## Costs

### Memory overhead

CRDTs often store metadata per element (vector clocks, tombstones).

OR-Set: tombstones grow with deletions.

### Bandwidth

State-based: send full state. Large for big structures.

Op-based: send ops. Often smaller.

Delta-based CRDTs send only changes.

### Garbage collection

Tombstones, version vectors accumulate. Need cleanup mechanism.

Often requires occasional coordination (which defeats the purpose somewhat).

### Implementation complexity

Subtle bugs are common. Use libraries; don't roll your own for non-trivial structures.

## Limitations

### Non-trivial semantics

Some operations don't fit CRDT model:
- "Remove all elements" (need consensus on what "all" means)
- Conditional updates ("if X then Y")
- Read-modify-write across replicas

### Read consistency

CRDTs guarantee write convergence; reads can return any state up to date.

For "I just wrote X, so I should read X" semantics: same-replica reads or session guarantees.

### Bounded counters

Can't have a CRDT counter with strict upper bound (without coordination).

### Garbage collection challenges

Tombstones live forever in basic OR-Set. Real implementations need GC strategies.

## When to use CRDT

CRDT fits when:
- Multiple writers
- High availability required (no coordination on writes)
- Eventual consistency acceptable
- Can express semantics in CRDT-friendly way

CRDT doesn't fit when:
- Need strong consistency
- Operations require coordination
- Coordination cost is acceptable

## When NOT to use CRDT

If you have a single writer (master-replica), CRDTs are unnecessary.

If you need strong consistency (banking transactions, inventory), use consensus-based systems.

If your domain has natural conflicts that need user resolution: surface conflicts; don't auto-merge.

## Common failure patterns

### Wrong CRDT for the semantics

OR-Set when you wanted last-write-wins. Subtle differences matter.

### Memory growth

Forgetting that tombstones / version vectors grow. Production OOM eventually.

### Causal violations

Mixing CRDT with non-CRDT operations. Break SEC.

### Custom CRDTs

Designing your own CRDT is hard. Composition of standard CRDTs is safer.

### Pretending CRDT solves everything

CRDT is a tool, not a complete solution. Many distributed systems still need coordination.

## Theoretical foundations

CRDTs rely on:
- Lattice theory (join-semilattice)
- Causal histories
- Strong eventual consistency theorems

The math is well-developed. The engineering is the hard part.

## Practical advice

1. Identify if your problem genuinely needs multi-writer + high availability
2. If yes, look for existing CRDT library covering your data shape
3. If your data fits standard CRDTs (counters, sets, maps), use them
4. If complex (rich documents): use Yjs, Automerge
5. Avoid designing custom CRDTs unless you've read the literature carefully

## Further Reading

- [EventualConsistency](EventualConsistency) — Background
- [ByzantineFaultTolerance](ByzantineFaultTolerance) — Different consistency challenge
- [Distributed Systems Hub](DistributedSystemsHub) — Cluster index
