---
title: Data Structures
type: article
cluster: data-structures
status: active
date: '2026-04-25'
tags:
- data-structures
- arrays
- hashmaps
- trees
- complexity
summary: The data structures that show up in everyday code — arrays, hash
  tables, balanced trees, heaps, tries, graphs — with the cost models and the
  cases where each beats the alternatives.
related:
- BloomFilters
- TrieDataStructure
- BalancedSearchTrees
- GraphAlgorithmsDeepDive
- DatabaseIndexingStrategies
hubs:
- DataStructures Hub
---
# Data Structures

Data structure choice is "what shape gives my access pattern the right cost." A naive list is fine for 100 items; not for 10 million. A hash table is fast for lookup; useless for ordered scan. Trees mediate.

This page is the everyday-engineering catalogue. For deeper dives, see the per-structure pages.

## Arrays / Lists

The default. Contiguous memory; O(1) random access by index; O(n) insert / delete in the middle.

| Operation | Cost |
|---|---|
| `arr[i]` | O(1) |
| `arr.append(x)` | Amortised O(1) |
| `arr.insert(i, x)` | O(n) |
| `arr.remove(x)` | O(n) |
| Iteration | O(n), cache-friendly |

Hidden virtues: cache locality. Iterating an array is far faster than iterating a linked list of the same size because elements are sequential in memory. Modern CPUs prefetch sequential data; random pointer chasing kills throughput.

Use when: ordered iteration matters; size is bounded; random access by position needed; the sequence isn't constantly modified in the middle.

## Linked lists

Each node points to the next (and possibly previous). O(1) insert/delete given a pointer to the node; O(n) random access.

In modern code, you rarely want a linked list. The cache-locality penalty makes them slower than arrays for almost everything. Use only when:

- Insertions / deletions in the middle dominate, **and**
- You can hold direct pointers to the nodes you'll modify.

For most "linked list use cases," dynamic arrays (Python list, Java ArrayList, C++ vector) are faster.

## Hash tables / Hash maps / Dictionaries

The workhorse. O(1) average-case lookup, insert, delete by key. Unordered.

| Operation | Average | Worst case |
|---|---|---|
| `m[k]` | O(1) | O(n) |
| `m[k] = v` | O(1) | O(n) |
| `del m[k]` | O(1) | O(n) |
| Iteration | O(n) | O(n) |

Worst case is bad-hash collision; modern hash maps with universal hashing avoid this in practice.

Implementation specifics matter:

- **Open addressing** (linear/quadratic probing): better cache behaviour; sensitive to load factor; resize threshold around 0.5-0.75. Used in Python dict, Go map.
- **Chaining**: simpler; more memory; less cache-friendly. Used in Java HashMap (with chaining), C++ unordered_map.

For 99% of use cases, the language's standard hash map is the right choice.

Use hash maps for: lookup by key without ordering; sets (use as a map with dummy values); de-duplication; building indexes.

## Sorted maps / Trees

Balanced binary search trees (red-black tree, AVL tree, B-tree-flavoured): O(log n) lookup, insert, delete, with the ability to iterate in sorted order.

| Operation | Cost |
|---|---|
| Lookup, insert, delete | O(log n) |
| Range query [a, b] | O(log n + matches) |
| Iteration | O(n), in sorted order |

Languages: Java TreeMap, C++ std::map, Python `sortedcontainers.SortedDict`, Rust `BTreeMap`.

Use when: range queries matter; you need ordered iteration; you need both fast lookup and sorted access.

For massive datasets, B-trees (used in databases) outperform balanced binary trees because their fan-out matches disk / cache page sizes. See [BalancedSearchTrees], [DatabaseIndexingStrategies].

## Heaps / Priority queues

A binary heap supports O(log n) insert and O(log n) extract-min (or extract-max). O(1) peek.

| Operation | Cost |
|---|---|
| Insert | O(log n) |
| Peek | O(1) |
| Extract-min | O(log n) |
| Decrease-key | O(log n) (with bookkeeping) |

Used for: priority queues, top-k selection, event simulation, Dijkstra's algorithm, scheduling.

Languages: Python `heapq`, Java PriorityQueue, C++ std::priority_queue, std::make_heap.

Variants:

- **Fibonacci heap** — better amortised bounds for decrease-key; rarely worth the constants in practice.
- **Pairing heap** — simpler; competitive with Fibonacci heap experimentally.
- **D-ary heap** — heap with d children per node; sometimes faster in practice.

Use a binary heap unless you have a specific reason to deviate.

## Stacks and queues

Stack: LIFO; push, pop both O(1). Backed by an array.
Queue: FIFO; enqueue, dequeue both O(1). Usually a deque or circular buffer.

Languages provide these directly. Stacks are heavily used in DFS, expression evaluation, recursion replacement. Queues in BFS, message processing.

Don't use a `LinkedList` to implement a stack or queue when the language has a deque. Cache-friendlier; faster.

## Sets

A set is a hash map with no values, or a sorted map with no values. Same complexity. Used for membership tests, deduplication, intersection / union / difference.

For approximate membership at huge scale, see [BloomFilters].

## Tries

Tree structures keyed on character sequences. Excellent for prefix queries.

See [TrieDataStructure].

## Graphs

A graph is a collection of nodes and edges. Representations:

- **Adjacency list**: dict of node → list of neighbours. Default for sparse graphs.
- **Adjacency matrix**: 2D array; O(1) edge test; O(V²) memory. For dense graphs.
- **Edge list**: list of (u, v) pairs. Compact; iteration-only.

See [GraphAlgorithmsDeepDive].

## Specialised structures worth knowing

### Disjoint Set / Union-Find

Maintains a partition of elements. Supports `union(x, y)` and `find(x)`. With path compression and union by rank: practically O(α(n)) per operation, where α is the inverse Ackermann function (basically constant).

Used for: connected components, Kruskal's MST, percolation, image segmentation.

### Skip list

Probabilistic alternative to balanced trees. O(log n) expected for lookup, insert, delete. Used in Redis sorted sets, some database indexes.

Simpler to implement than balanced trees; competitive performance.

### Bloom filter

Probabilistic membership test. False positives possible; false negatives impossible. Tiny memory.

See [BloomFilters].

### Suffix tree / suffix array

For substring queries on a text. O(query length) lookups regardless of text length. Used in genome alignment, log analysis.

### LRU cache

A hash map + doubly-linked list. O(1) get, put, eviction. Used for caches.

Most languages have library implementations (Python `functools.lru_cache`, Java LinkedHashMap, etc.).

### B-tree / B+-tree

Generalisation of balanced binary trees with high fan-out. Used in databases for on-disk indexes. See [DatabaseIndexingStrategies].

## Cost model: amortised vs worst-case

Average-case bounds (e.g., O(1) hash map insert) ignore that occasionally an insert is slow (rehash). For most application code this is fine; for real-time systems with strict latency bounds, worst-case matters.

Languages let you check or pre-allocate: Python `dict` doesn't expose this; Java ArrayList has `ensureCapacity`; you can resize-then-fill to avoid amortised costs at hot paths.

## Cache-conscious choices

For very performance-sensitive code:

- **Arrays of structs** vs **structs of arrays**: the latter is usually faster for SIMD / streaming.
- **Sorted data** in a flat array beats balanced trees for many access patterns when the data fits in cache.
- **Cache lines are 64 bytes**; structs that span cache lines suffer.
- **Linked structures (lists, trees) are cache-hostile**.

These optimisations matter in hot loops; don't matter in 99% of application code. Profile before micro-optimising.

## Pragmatic guidance

For most application code:

1. **Use the standard library's data structures.** They're well-tuned; don't roll your own.
2. **Default to hash maps for lookup.** Switch to sorted maps when you need ordering or range queries.
3. **Use arrays / dynamic arrays for sequences.** Linked lists are rarely the right choice.
4. **Reach for specialised structures (heap, trie, union-find) when the access pattern matches.**
5. **Measure before optimising.** Big-O matters; constants matter; cache effects matter; the easiest optimisation is usually using the right structure.

Knowing the catalogue is what lets you pick the right one. The catalogue is what this page tries to be.

## Further reading

- [BloomFilters] — probabilistic membership
- [TrieDataStructure] — prefix-keyed trees
- [BalancedSearchTrees] — sorted-map implementations
- [GraphAlgorithmsDeepDive] — algorithms over graph data
- [DatabaseIndexingStrategies] — data structures inside databases
