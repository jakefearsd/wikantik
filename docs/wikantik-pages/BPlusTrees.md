---
canonical_id: 01KQQ6YQR91SVAT7J8ZMADXXKT
date: 2026-05-03T00:00:00Z
cluster: data-structures
type: article
tags:
- data-structures
- b-plus-tree
- databases
- indexing
- storage-engines
- algorithms
title: B+ Trees
relations:
- type: part-of
  target_id: 01KQEKGD9BVAXF6X4HZYKF2513
- type: prerequisite-for
  target_id: 01KQ0P44PEJG4KBKH84YFQP91Z
summary: A deep dive into the B+ Tree data structure, the backbone of modern relational
  database indexing. Explains the structural differences from B-Trees, the advantages
  for on-disk storage, and the mechanics of rebalancing via splits and merges.
status: active
---

# B+ Trees: The Engine of Relational Storage

The B+ Tree is a self-balancing tree data structure that maintains sorted data and allows for efficient insertion, deletion, and search. It is the dominant indexing structure for almost all modern relational databases (PostgreSQL, MySQL, SQL Server) and file systems (NTFS, XFS).

## 1. Structural Properties

A B+ Tree differs from a standard B-Tree in several critical ways:
1.  **Data Only at Leaves**: Internal nodes only store keys (which act as routers); the actual data (or pointers to data rows) is stored exclusively in the leaf nodes.
2.  **Linked Leaves**: Leaf nodes are linked together in a doubly-linked list. This allows for extremely efficient **range scans** (e.g., `SELECT * FROM users WHERE age > 21`).
3.  **Higher Fan-out**: Because internal nodes don't store data, they can fit more keys per page. This results in a much higher fan-out and a shallower tree (often only 3 or 4 levels deep for millions of records).

## 2. Operations

### Search
Search is a simple traversal from root to leaf. At each internal node, the algorithm compares the search key against the router keys to determine which child pointer to follow. The complexity is $O(\log_f N)$, where $f$ is the fan-out.

### Insertion (Splitting)
1.  Find the correct leaf node.
2.  If the leaf has space, insert the key in order.
3.  If the leaf is full, it **splits** into two nodes. The median key is "pushed up" to the parent node.
4.  This split can propagate all the way to the root, which is how the tree grows in height.

### Deletion (Merging/Redistribution)
1.  Find the leaf node and remove the key.
2.  If the node falls below the minimum occupancy (typically 50%), it attempts to **borrow** a key from a sibling.
3.  If siblings are also at minimum, the node **merges** with a sibling. This "pulls down" a key from the parent, potentially causing a recursive merge.

## 3. Why B+ Trees Win on Disk

The primary constraint of traditional databases is the **I/O Bottleneck**.
- **Block Access**: Disks (and SSDs) read data in blocks (pages), typically 4KB or 8KB.
- **Cache Locality**: By making the node size equal to the disk page size, a B+ Tree ensures that every "hop" in the tree corresponds to exactly one disk I/O.
- **Predictable Latency**: Because the tree is perfectly balanced, every search takes exactly the same number of I/Os.

## 4. B+ Trees vs. Vector Indices (HNSW)

| Feature | B+ Tree | HNSW |
| :--- | :--- | :--- |
| **Data Type** | Scalars (Integers, Strings) | High-dimensional Vectors |
| **Search Type** | Exact / Range | Approximate Nearest Neighbor |
| **Complexity** | $O(\log N)$ | $O(\log N)$ (Approximate) |
| **Storage** | Optimized for Disk | Optimized for RAM |

In a modern [Vector Database](VectorDatabases) like `pgvector`, B+ Trees are used to index the **metadata** (like `user_id` or `timestamp`), while HNSW handles the **semantic** search over the embeddings.

## See Also
- [[DataStructuresHub]] — Other fundamental structures.
- [[VectorIndexingInternals]] — The modern alternative for AI.
- [[DatabaseIndexingStrategies]] — How B+ Trees are used in SQL.
