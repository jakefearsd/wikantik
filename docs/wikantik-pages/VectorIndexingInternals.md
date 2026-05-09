---
canonical_id: 01KQQ6XV7FNPEXCT6CSMZA8CRN
date: '2026-05-15'
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
- type: part-of
  target_id: 01KQEKGDAZH3G3X2J4VFM9MP88
- type: prerequisite-for
  target_id: 01KQEKGD6VT29FGWF8YE9TM671
- type: derived-from
  target_id: 01KQEKGD9BVAXF6X4HZYKF2513
summary: Deep dive into the mathematical mechanics of vector indexing (HNSW, IVF-PQ) and the trade-offs between memory efficiency and recall.
status: active
auto-generated: false
---

# Vector Indexing Internals: HNSW and IVF-PQ

Approximate Nearest Neighbor (ANN) search is the core engine of RAG systems. This article dissects the mathematical mechanics of the two dominant indexing strategies: **HNSW** (for performance) and **IVF-PQ** (for memory efficiency).

## 1. HNSW (Hierarchical Navigable Small World)
HNSW is a graph-based index. It organizes vectors into a hierarchy of layers where each layer is a "Small World" graph. Navigation starts at the sparse top layer and descends into denser layers to refine the search.

- **Pros**: Sub-millisecond latency, very high recall.
- **Cons**: High memory overhead (stores the full graph structure + raw vectors in RAM).

## 2. IVF-PQ (Inverted File with Product Quantization)
IVF-PQ is a hybrid approach that uses clustering (IVF) to narrow the search space and lossy compression (PQ) to reduce the memory footprint.

### Inverted File (IVF)
The vector space is partitioned into $N$ clusters (Voronoi cells) using $k$-means. 
- During search, only the most relevant $nprobe$ clusters are scanned.

### Product Quantization (PQ)
PQ reduces the size of vectors (e.g., 1536 dimensions) by 10x–64x.
1. **Split**: The vector is divided into $m$ sub-vectors.
2. **Quantize**: Each sub-vector is replaced by the index of its closest centroid from a pre-trained codebook (usually 256 centroids per sub-space).
3. **Distance Computation**: Distances are approximated using a pre-calculated lookup table, avoiding expensive floating-point math.

### Concrete Example: IVF-PQ with FAISS
```python
import faiss
import numpy as np

d = 128          # dimension
n_vectors = 100000
n_clusters = 100 # nlist (number of Voronoi cells)
m = 8            # number of sub-quantizers (each sub-vector is 128/8 = 16-dim)
nbits = 8        # each sub-vector is reduced to 8 bits (1 byte)

# Training data for centroids
train_data = np.random.random((2048, d)).astype('float32')
data = np.random.random((n_vectors, d)).astype('float32')

# Create the quantizer (using L2 distance)
quantizer = faiss.IndexFlatL2(d)

# Create the IVF-PQ index
index = faiss.IndexIVFPQ(quantizer, d, n_clusters, m, nbits)

# Train the index (k-means for centroids and PQ codebooks)
index.train(train_data)
index.add(data)

# Tuning Search
index.nprobe = 10 # search 10 clusters instead of 1

# Query
query = np.random.random((1, d)).astype('float32')
D, I = index.search(query, 5)
```

## 3. Comparison of Internals

| Feature | HNSW | IVF-PQ |
|---|---|---|
| **Mechanism** | Graph Traversal | Clustering + Quantization |
| **Memory usage** | High ($O(d \cdot N + edges)$) | Low ($O(m \cdot N)$) |
| **Accuracy (Recall)** | Very High | Moderate to High |
| **Latency** | Extremely Low | Low (increases with $nprobe$) |
| **Hardware** | Optimized for CPU | Optimized for GPU (massive parallelism) |

## 4. Distance Metrics
The choice of distance metric influences the internal search logic:
- **L2 (Euclidean)**: Measures straight-line distance.
- **Cosine Similarity**: Measures the angle between vectors (normalized dot product).
- **Inner Product**: Standard dot product. Used when vector magnitudes are significant.

## Summary of Technical implementation added
- Dissected the **HNSW** and **IVF-PQ** mechanics.
- Explained the **Product Quantization (PQ)** workflow (Split, Quantize, Lookup).
- Provided a concrete **FAISS implementation of IndexIVFPQ**.
- Detailed the role of **nprobe** in balancing search speed vs. accuracy.
- Compared memory and latency trade-offs.
