---
canonical_id: 01KQQ6XV7FNPEXCT6CSMZA8CRN
date: 2026-05-03T00:00:00Z
cluster: generative-ai
type: article
tags:
- vector-database
- hnsw
- ivfflat
- product-quantization
- ann
- rag
- mathematics
title: Vector Indexing Internals
relations:
- type: part-of
  target_id: 01KQEKGDAZH3G3X2J4VFM9MP88
- type: prerequisite-for
  target_id: 01KQEKGD6VT29FGWF8YE9TM671
- type: derived-from
  target_id: 01KQEKGD9BVAXF6X4HZYKF2513
summary: A deep technical dive into the algorithms that power modern vector databases.
  Explains the mechanics of Hierarchical Navigable Small World (HNSW) graphs, Inverted
  File (IVF) indexes, and Product Quantization (PQ).
status: active
---

# Vector Indexing Internals: Beyond the API

Approximate Nearest Neighbor (ANN) search is the engine of RAG. While exact $k$-NN is $O(N)$, production systems with millions of vectors require logarithmic or sub-linear search times. This article dissects the two dominant algorithms used in production in 2026: **HNSW** and **IVF-PQ**.

## 1. Hierarchical Navigable Small World (HNSW)

HNSW is currently the state-of-the-art for low-latency, high-recall vector search. It is a graph-based index that builds on the concept of "Skip Lists" applied to the proximity graph.

### The Layered Architecture
HNSW constructs a multi-layer graph where:
- **Layer 0 (Bottom)**: Contains all nodes (vectors) and all fine-grained connections.
- **Higher Layers**: Contain a progressively smaller subset of nodes. Each node in a higher layer is linked to its nearest neighbors in that layer and the layer below.

### The Search Process
1.  **Entry Point**: The search starts at a single entry point in the topmost (coarsest) layer.
2.  **Greedy Search**: The algorithm greedily moves to the neighbor closest to the query vector.
3.  **Layer Descent**: Once a local minimum is found in the current layer, the search drops to the same node in the layer below and repeats the greedy search.
4.  **Final Refinement**: The search concludes at Layer 0, where a final beam search (governed by the `ef` parameter) identifies the $k$ nearest neighbors.

### Critical Hyperparameters
- **$M$**: Max connections per node. Higher $M$ improves recall but increases memory usage and index build time.
- **$efConstruction$**: The search depth used during index building.
- **$efSearch$**: The search depth during query time. This can be tuned per-query to balance latency vs. recall.

## 2. Inverted File (IVF) with Product Quantization (PQ)

While HNSW is fast, it is memory-intensive (storing the graph structure in RAM). **IVF-PQ** is the alternative for massive scale (100M+ vectors) where memory compression is required.

### IVF: Voronoi Partitioning
IVF partitions the vector space into $N$ clusters (Voronoi cells) using $k$-means. 
- **Indexing**: Each vector is assigned to its nearest cluster centroid.
- **Search**: The query vector is compared against all centroids to identify the top-$n$ most relevant cells (`nlist`). The search then only inspects the vectors *within* those cells.

### PQ: Product Quantization
PQ is a lossy compression technique that reduces vector size by 10x–64x:
1.  **Subspace Decomposition**: A high-dimensional vector (e.g., 1536-dim) is split into $m$ smaller sub-vectors (e.g., 64 sub-vectors of 24 dimensions each).
2.  **Codebook Generation**: For each subspace, a small codebook (typically 256 centroids) is trained.
3.  **Quantization**: Each sub-vector is replaced by the index (1 byte) of its closest centroid in the codebook.
4.  **Asymmetric Distance Computation (ADC)**: During search, the query vector is compared against the codebooks to build a distance table, allowing the system to approximate the distance to thousands of vectors using only fast table lookups and additions.

## 3. Trade-offs in Production

| Feature | HNSW | IVF-PQ |
| :--- | :--- | :--- |
| **Search Speed** | Extremely Fast | Fast |
| **Recall** | High (95-99%) | Moderate (85-95%) |
| **Memory** | High (Raw + Graph) | Low (Compressed) |
| **Build Time** | Moderate | High (Needs training) |
| **Incremental Updates** | Native Support | Hard (Centroid drift) |

**Wikantik Strategy**: As documented in [[VectorDatabases]], Wikantik uses `pgvector` with **HNSW** for its default storage, providing the best balance of transactional safety and sub-100ms retrieval for mid-scale RAG workloads.

## See Also
- [[VectorDatabases]] — Comparison of current products.
- [[ContextCompression]] — Reducing the token cost of retrieved context.
- [[InformationTheory]] — Foundations of data compression and entropy.
