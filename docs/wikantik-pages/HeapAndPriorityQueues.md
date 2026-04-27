---
canonical_id: 01KQ0P44QW0ZSA2E6MXS9KXBC2
title: Heaps and Priority Queues
type: article
cluster: data-structures
status: active
date: '2026-04-26'
summary: Heap data structures and priority queues — binary heaps, Fibonacci heaps,
  pairing heaps — and how they enable algorithms from Dijkstra to event simulation
  to scheduling.
tags:
- heap
- priority-queue
- data-structures
- algorithms
related:
- BalancedSearchTrees
- HashTableDesign
hubs:
- Data Structures Hub
---
# Heaps and Priority Queues

A priority queue maintains a collection of elements, each with a priority. The defining operations: insert an element, and extract the highest-priority element.

The most common implementation is the heap. Heaps power Dijkstra's algorithm, event-driven simulation, scheduling, and many ranking systems.

## The priority queue interface

Standard operations:
- **insert(value, priority)**: add element
- **extractMin()** (or extractMax): remove and return highest-priority
- **peek()**: look at highest-priority without removing
- **decreaseKey()** (sometimes): change an element's priority

Optional:
- **remove(element)**: extract a specific element
- **merge(other)**: combine two priority queues

## Binary heap

The standard implementation. Complete binary tree with the heap property:
- **Min-heap**: parent ≤ children
- **Max-heap**: parent ≥ children

### Array representation

Stored in an array. Parent at i, children at 2i+1 and 2i+2.

No pointers. Cache-friendly. Classic.

### Operations

- **Insert**: add at end; bubble up. O(log n).
- **Extract**: take root; move last to root; bubble down. O(log n).
- **Peek**: O(1).
- **Build heap from array**: O(n) (not O(n log n)).
- **Decrease key**: requires knowing position; O(log n).

### When to use

Default priority queue. Simple, fast, cache-friendly.

Java's PriorityQueue, Python's heapq, C++'s std::priority_queue are binary heaps.

## D-ary heap

Like binary heap but each node has d children.

Tradeoff:
- Larger d: shallower tree, faster extracts (less bubble up)
- Smaller d: faster decrease-key

For Dijkstra, d = 4 or 8 is sometimes optimal.

## Binomial heap

Collection of binomial trees. Supports merge in O(log n).

### Operations

- Insert: O(log n) worst, O(1) amortized
- Extract: O(log n)
- Merge: O(log n)
- Decrease key: O(log n)

Useful when frequent merging is needed.

## Fibonacci heap

Theoretical workhorse. Supports decrease-key in amortized O(1).

### Operations

- Insert: O(1) amortized
- Decrease key: O(1) amortized
- Extract: O(log n) amortized

Fibonacci heaps make Dijkstra theoretically O(m + n log n) instead of O((m + n) log n).

In practice: high constant factors. Binary heaps usually win for typical inputs.

## Pairing heap

Simpler than Fibonacci; competitive in practice.

- Insert: O(1)
- Extract: O(log n) amortized
- Decrease key: O(log log n) amortized (open question)

Often the fastest in practice for graph algorithms.

## Specialized heaps

### Indexed priority queue

Maps elements to their position in the heap. Enables decrease-key.

Used in Dijkstra implementations.

### Bounded priority queue

Fixed-size; eviction on overflow.

Used for top-K queries.

### Soft heap

Allows incorrect results; faster bounds. Niche use.

## Common applications

### Dijkstra's algorithm

Find shortest paths from source. Priority queue holds frontier.

With binary heap: O((m + n) log n).
With Fibonacci heap: O(m + n log n).

### Prim's MST algorithm

Similar structure to Dijkstra.

### Heapsort

Sort by inserting all elements then extracting all.

O(n log n) worst case. In-place. Not stable.

Less common than introsort or quicksort in practice.

### Event-driven simulation

Events have scheduled times. Priority queue extracts next event.

Discrete-event simulation, networking simulators, game engines.

### Scheduling

Tasks have priorities. Scheduler extracts highest priority.

OS process schedulers, thread pools, job queues.

### Top-K queries

Find K largest/smallest. Use heap of size K.

For K=1: just track max. For K small: heap is efficient.

For very large K, sort might be better.

### Median maintenance

Two heaps: max-heap for lower half, min-heap for upper half. Median is at the boundary.

### A* search

Like Dijkstra but with heuristic. Priority = cost so far + heuristic.

### Huffman coding

Build optimal prefix code by repeatedly merging two least-frequent nodes.

## Design considerations

### Min vs max

Min-heap and max-heap have identical operations on negated priorities.

Some libraries support both; some only one.

### Stability

Equal-priority elements: which extracts first?

Not stable by default. Add insertion-order tiebreaker if needed.

### Mutability

If priorities change, you need decrease-key. Not all heaps support efficiently.

### Concurrency

Concurrent heaps are tricky. Lock-free implementations exist but complex.

## Common failure patterns

### Wrong direction (min vs max)

Subtle bug; results look almost right.

### Not handling stability

Equal-priority elements come out in surprising order.

### Mutating elements after insert

If priority depends on mutable state, heap invariant breaks.

### Heap when sorted array would do

If you're extracting all elements, sort + iterate may be simpler.

### Implementing your own

Standard library implementations are well-tested. Use them.

### Performance on small N

For small heaps, sorted array is faster (cache effects).

## Performance characteristics

For binary heap with N elements:
- Insert: ~log₂ N comparisons
- Extract: ~log₂ N comparisons + 1 swap

For N = 1M: ~20 comparisons per operation. Fast.

Modern CPUs: ~50 nanoseconds per operation.

## Implementation tips

For high-performance:
- Inline everything
- Avoid pointer indirection
- Cache priority adjacent to value
- Consider d-ary for shallow tree

For correctness:
- Test the heap property explicitly
- Random testing with verification
- Benchmark realistic workloads

## Standard library guidance

- **Python**: heapq (min-heap; min-only operations)
- **Java**: PriorityQueue (min-heap by default; comparator-based)
- **C++**: std::priority_queue (max-heap by default)
- **Rust**: std::collections::BinaryHeap (max-heap)

Most are binary heaps. For specialized needs (decrease-key, merge), check available libraries or implement.

## When priority queue isn't right

If you need:
- Range queries → use balanced BST
- Sorted iteration → sort once
- Just maximum (and elements never become irrelevant) → track running max
- O(1) extract regardless of size → consider buckets if priorities are bounded integers

## Further Reading

- [BalancedSearchTrees](BalancedSearchTrees) — Different access pattern
- [HashTableDesign](HashTableDesign) — Different access pattern
- [Data Structures Hub](Data+Structures+Hub) — Cluster index
