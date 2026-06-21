---
status: active
date: 2026-05-15T00:00:00Z
summary: 'The interval tree data structure: how augmented BSTs answer overlap queries
  in O(log n), how it compares to segment trees, and where it is used in practice.'
tags:
- data-structures
- computer-science
- algorithms
- geometry
- efficiency
type: article
cluster: data-structures
canonical_id: 01J7KQTCCQ3H9K0M9E95ZCK3KL
related:
- DataStructuresHub
- FoundationalAlgorithmsForComputerScientists
- BalancedSearchTrees
title: 'Interval Tree Data Structure: Range and Overlap Queries'
---

# Interval Tree Data Structure: Range and Overlap Queries

An **interval tree** is an augmented [balanced search tree](BalancedSearchTrees) that stores intervals and efficiently answers the question *"which stored intervals overlap a given point or query interval?"* It is the standard data structure for managing a **dynamic** set of ranges — one that changes as intervals are inserted and deleted — and it appears everywhere from operating-system kernels to game engines and calendar software.

The core problem it solves: given $n$ intervals, find any (or all) that overlap a query, faster than the $O(n)$ scan a plain list would require. An interval tree answers a single overlap query in $O(\log n)$ time.

## The Key Idea: Augment a BST with a `max` Field

The most common implementation augments a self-balancing binary search tree (typically a **Red-Black Tree**). Each node $x$ stores:

- **Interval:** $[low[x], high[x]]$
- **Key:** $low[x]$ — the tree is ordered by the *start* of each interval.
- **Max:** the maximum endpoint ($high$) of any interval in the subtree rooted at $x$.

The `max` field is the trick that makes search efficient. Because it summarizes the whole subtree, a query can decide which branch *might* contain an overlap and prune the other entirely — turning a linear scan into a logarithmic descent. The `max` value is maintained during the same rotations the balanced tree already performs, so it costs nothing asymptotically.

### Complexity Analysis

| Operation | Complexity |
| :--- | :--- |
| **Space** | $O(n)$ |
| **Insertion / Deletion** | $O(\log n)$ |
| **Single overlap search** | $O(\log n)$ |
| **Report all $k$ overlaps** | $O(k \log n)$ |

## How Overlap Search Works

Two intervals $[a, b]$ and $[c, d]$ overlap if and only if $a \le d$ **and** $c \le b$. To find *some* interval overlapping a query $i$, descend from the root:

1. If the current node's interval overlaps $i$, return it.
2. Otherwise, if the left child exists and its `max` $\ge i.low$, an overlap (if any) must be on the left — go left.
3. Otherwise, go right.

The correctness rests on the `max` invariant: if the left subtree's maximum endpoint is still below $i.low$, no interval there can reach $i$, so it is safe to skip the entire left side.

```python
def interval_search(root, i):
    # i is the query interval [low, high]
    current = root
    while current is not None and not overlaps(current.interval, i):
        if current.left is not None and current.left.max >= i.low:
            current = current.left
        else:
            current = current.right
    return current  # an overlapping interval, or None
```

To report **all** overlapping intervals rather than just one, recurse into both children whenever their `max` permits, collecting every match — $O(k \log n)$ for $k$ results.

## Construction and Maintenance

- **Insertion:** Insert by `low` like an ordinary BST, rebalance, and update `max` on the path back to the root (each node's `max` is the larger of its own `high` and its children's `max`).
- **Deletion:** Remove as in the underlying balanced tree, then refresh `max` along the affected path during rebalancing rotations.
- **Bulk build:** If the interval set is static, sorting by `low` and building a balanced tree bottom-up gives an $O(n \log n)$ construction — though for static data a [segment tree](#interval-tree-vs-segment-tree) or a simple sorted array may be simpler.

## Interval Tree vs. Segment Tree

Both handle intervals, but they target different query shapes. Choosing the wrong one is a common mistake.

| Feature | Augmented Interval Tree | Segment Tree |
| :--- | :--- | :--- |
| **Best for** | Overlap queries over a *dynamic* set of intervals | Point-in-interval and range *aggregate* queries (min / max / sum) |
| **Space** | $O(n)$ — highly memory efficient | $O(n \log n)$, or $O(n)$ with coordinate compression |
| **Dynamic updates** | Native via BST rotations | Awkward; often needs rebuilding or lazy propagation |
| **Aggregates over a range** | Not its strength | Built for it |

A rough rule: if intervals are added and removed frequently and you ask "what overlaps this?", reach for an interval tree. If the set is mostly static and you want aggregate statistics over ranges, a segment tree (or Fenwick/BIT) often fits better. For *multidimensional* ranges (rectangles in 2-D+), neither suffices — that is the domain of **R-trees** and **k-d trees**.

## Real-World Applications

- **Virtual memory management:** The Linux kernel uses an interval tree (over its red-black tree) to track virtual memory areas (VMAs) and resolve overlaps quickly.
- **Collision detection:** Game engines find all bounding boxes intersecting a projectile's path or a moving object's sweep.
- **Calendar and scheduling:** Detecting conflicts — "which meetings overlap this time block?" — and double-booking checks.
- **Genomics:** Finding which genes or features overlap a queried region of a chromosome (tools like BEDTools rely on interval indexing).
- **Networking and security:** Matching an address against overlapping CIDR ranges or firewall rules.

## Common Pitfalls

- **Forgetting to update `max`.** A missed update on insertion, deletion, or rotation silently breaks pruning and produces wrong answers — not crashes — so test overlap correctness explicitly.
- **Using it for aggregates.** Interval trees answer *overlap*, not "sum of values over a range." Use a segment tree or BIT for that.
- **Half-open vs. closed intervals.** Decide whether intervals are $[a, b]$ or $[a, b)$ and apply the overlap test consistently, or off-by-one bugs at touching endpoints will appear.

## Frequently Asked Questions

**What is an interval tree used for?**
Efficiently finding which stored intervals overlap a given point or range, in a set that changes over time — used in memory management, collision detection, scheduling, and genomics.

**What is the time complexity of an interval tree?**
$O(\log n)$ for insertion, deletion, and a single overlap query; $O(k \log n)$ to report all $k$ overlapping intervals; $O(n)$ space.

**Interval tree vs. segment tree — which should I use?**
Use an interval tree for overlap queries over a dynamic interval set; use a segment tree for range-aggregate (min/max/sum) and point-in-interval queries on mostly static data.

**How does the `max` field make search fast?**
It records the largest endpoint in each subtree, letting a query prune an entire branch when that branch cannot reach the query — reducing a linear scan to a logarithmic descent.

## Related Reading

- [Balanced Search Trees](BalancedSearchTrees) — the red-black tree foundation interval trees augment
- [Foundational Algorithms for Computer Scientists](FoundationalAlgorithmsForComputerScientists) — broader algorithmic context
- [Data Structures Hub](DataStructuresHub) — index of related structures
