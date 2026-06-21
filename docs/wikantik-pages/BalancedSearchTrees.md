---
related:
- HashTableDesign
- HeapAndPriorityQueues
summary: Balanced search trees — AVL, red-black, B-trees, splay trees — and their
  practical roles in databases, language libraries, and systems programming.
tags:
- balanced-trees
- data-structures
- algorithms
- avl
- red-black
cluster: data-structures
title: Balanced Search Trees
date: '2026-04-26'
hubs:
- DataStructuresHub
type: article
status: active
canonical_id: 01KQ0P44MCJA2WTGJB4NK967T6
---
# Balanced Search Trees

Binary search trees give O(log n) lookup, insert, delete — when balanced. Without rebalancing, a BST can degenerate to a linked list (O(n)).

Balanced search trees maintain logarithmic height through rebalancing operations. They're the workhorses of databases, language libraries, and ordered-data structures.

## Why balance matters

A BST built by inserting sorted data degenerates:

```
1
 \
  2
   \
    3
     \
      4
```

All operations become O(n). Rebalancing keeps height O(log n).

## AVL trees

The first balanced BST (1962). Strict balance: heights of subtrees differ by at most 1.

### Operations

- Insert: standard BST insert; rebalance up the path
- Delete: standard BST delete; rebalance up the path
- Lookup: O(log n)

Rebalancing uses rotations: single (LL, RR) or double (LR, RL).

### Properties

- Height: ≤ 1.44 × log₂(n)
- Stricter balance than red-black
- More rotations on insert/delete
- Faster lookups than red-black (slightly shorter)

### Use cases

- Read-heavy workloads (frequent lookups)
- Memory databases

## Red-black trees

More relaxed balance. Each node is red or black; properties enforce approximate balance.

### Properties

- Root is black
- Red nodes can't have red children
- All paths from root to null have same number of black nodes

These rules guarantee height ≤ 2 × log₂(n+1).

### Operations

Same as AVL: insert, delete, lookup. Rebalancing uses rotations and color flips.

Generally fewer rotations than AVL on writes.

### Use cases

- Java's TreeMap, TreeSet
- C++'s std::map, std::set
- Linux kernel (process scheduling, mmap)

The default ordered-map implementation in many standard libraries.

## B-trees

Multi-way trees. Each node holds k keys and has k+1 children.

Designed for disk-based storage; adapted for in-memory use too.

### Properties

- All leaves at same depth
- Each internal node has between m/2 and m children
- m typically 100-1000 for disk-based; smaller for memory

### Operations

- Lookup: traverse, comparing keys at each node
- Insert: insert at leaf; split if too full; propagate up
- Delete: more complex; merge or borrow from siblings

### B+ trees

All data in leaves; internal nodes have only keys. Leaves linked for efficient range scans.

Standard for relational databases (MySQL, PostgreSQL indices).

### Use cases

- Database indices
- Filesystems (ext4, NTFS, btrfs)
- Key-value stores (LevelDB-derived)

## Splay trees

Self-adjusting tree. Recently accessed elements move to root.

### Properties

- No explicit balance
- Amortized O(log n) operations
- Specific access patterns can be O(log n) per operation

### Use cases

Niche. Useful when access patterns are skewed (some keys much hotter).

Less common in production than AVL or red-black.

## Treaps

Randomized BST. Each node has a key and a random priority. Tree is BST on keys, heap on priorities.

### Properties

- Expected O(log n) operations
- Simple implementation
- Good for concurrent operations (less coordination)

## Skip lists

Probabilistic alternative. Multiple levels of linked lists; higher levels are "express lanes."

### Properties

- Expected O(log n) operations
- Simpler than balanced BSTs
- Good for concurrent access

### Use cases

- Redis (sorted sets)
- LevelDB memtable
- Some concurrent data structures

## Choice in practice

For most language standard libraries: red-black trees.

For database indices on disk: B+ trees.

For sorted sets with frequent updates: skip lists or red-black.

Rarely should you implement these yourself. Use the standard library.

## Comparison

| | AVL | Red-Black | B-tree | Skip List |
|---|---|---|---|---|
| Lookup | Fast | Slightly slower | Slow per node, few nodes | Fast |
| Insert | Slower | Fast | Fast | Fast |
| Memory | Compact | Compact | Compact | More overhead |
| Cache friendly | Poor | Poor | Excellent | Poor |
| Concurrent | Hard | Hard | Hard | Easier |

## Key insight: cache locality

Modern CPUs make cache miss expensive. B-trees with high fan-out are cache-friendly: more comparisons per cache line.

Binary trees have poor cache behavior. This is why B-trees beat binary trees for in-memory ordered data too, despite higher complexity.

For pure performance on modern hardware, B-tree variants often beat AVL/red-black even in memory.

## Operations performance

For all balanced BSTs:
- **Lookup**: O(log n)
- **Insert**: O(log n)
- **Delete**: O(log n)
- **Min/max**: O(log n)
- **Range query**: O(log n + k) where k is range size
- **In-order traversal**: O(n)

These are usually 2-3x slower than hash tables for individual lookups, but support ordered operations hash tables can't.

## When to use a balanced BST vs hash table

### Hash table wins

- Order doesn't matter
- Maximum lookup speed needed
- No range queries

### Balanced BST wins

- Order matters
- Range queries
- Predecessor/successor queries
- Worst-case bounds matter (hash tables have O(n) worst case)
- Iterator stability across modifications

## Common operations

### Range queries

"Find all values in [a, b]"

In balanced BST: O(log n + k). Easy.

In hash table: O(n). Painful.

### Predecessor / successor

"Largest value less than x"

In balanced BST: O(log n).

In hash table: O(n).

### Order statistics

"What's the k-th smallest value?"

With augmentation (size info per node): O(log n).

## Common failure patterns

### Implementing your own

Bugs are subtle. Use the standard library.

### Hash table when ordering matters

Realize too late that you need ordered iteration.

### Balanced BST when hash table sufficient

Slower than hash table for simple lookups.

### Ignoring cache effects

Choosing for asymptotic complexity when constant factors dominate at your scale.

### Using the wrong balanced BST variant

Almost always the standard library default is fine.

## Practical advice

For application code:
- Use the standard library's ordered map (TreeMap, std::map, BTreeMap in Rust)
- Don't worry about which balanced tree it uses
- Benchmark if performance matters

For systems / database code:
- B-tree variants are usually right
- Concurrency considerations matter
- Profile actual workloads

For learning:
- Implement red-black or AVL once for understanding
- Then use the standard library

## Further Reading

- [HashTableDesign](HashTableDesign) — Alternative for unordered data
- [HeapAndPriorityQueues](HeapAndPriorityQueues) — Different tree structure
- [Interval Trees](IntervalTrees) — Augmenting a balanced BST with a `max` field for O(log n) overlap queries
- [Data Structures Hub](DataStructuresHub) — Cluster index
