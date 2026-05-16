---
cluster: agentic-ai
canonical_id: 01KQ0P44Q3Y083P3R11WCRW3XR
title: Embeddings Vector DB
type: article
tags:
- generative-ai
- vector-databases
- hnsw
- pgvector
- indexing
status: active
date: '2026-04-24'
summary: Technical comparison of vector database indexing strategies (HNSW vs IVF) and implementation guide for pgvector and dedicated vector stores.
auto-generated: false
---
# Vector Databases: Indexing for Speed

Vector databases solve the "Nearest Neighbor" problem in high-dimensional space. While a brute-force search is $O(N)$, production systems use Approximate Nearest Neighbor (ANN) algorithms to achieve sub-millisecond latency.

## 1. Indexing Strategies: HNSW vs. IVF

Choosing an index determines the trade-off between memory, search speed, and accuracy (recall).

### HNSW (Hierarchical Navigable Small World)
- **Mechanism:** Builds a multi-layered graph where the top layer has few connections (long jumps) and bottom layers have many (local refinement).
- **Pros:** Fast search, high recall, no training phase required.
- **Cons:** High memory consumption (stores the full graph); slow index builds.
- **Best For:** Real-time applications where accuracy is paramount.

### IVF (Inverted File Index)
- **Mechanism:** Partitions the vector space into clusters (Voronoi cells). The search only explores the closest clusters.
- **Pros:** Low memory overhead; faster index builds than HNSW.
- **Cons:** Requires a "training" phase to define cluster centroids; lower recall if the dataset shifts.
- **Best For:** Massive datasets (billions of vectors) where memory is the bottleneck.

## 2. pgvector: Vector Search in Postgres

`pgvector` is the standard for adding vector search to existing relational databases. It supports two main index types:

### Concrete Example: pgvector HNSW Index
```sql
-- 1. Enable the extension
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Create a table with a vector column (1536 dimensions)
CREATE TABLE documents (
    id serial PRIMARY KEY,
    content text,
    embedding vector(1536)
);

-- 3. Create an HNSW index
-- m: max connections per node (default 16)
-- ef_construction: dynamic candidate list size (default 64)
CREATE INDEX ON documents USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- 4. Perform a similarity search
SELECT content FROM documents 
ORDER BY embedding <=> '[0.1, 0.2, ...]' 
LIMIT 5;
```
*Note: `<=>` is the cosine distance operator in pgvector.*

## 3. Dedicated Vector Stores vs. SQL Extensions

| Feature | pgvector (Postgres) | Pinecone / Qdrant |
|---|---|---|
| **Data Consistency** | High (ACID compliant) | Variable (Eventual consistency) |
| **Complexity** | Low (Single DB) | High (Separate service) |
| **Hybrid Search** | Native (Join with SQL) | Requires complex orchestration |
| **Scale** | Millions of vectors | Billions of vectors |

## 4. Optimization: Product Quantization (PQ)

In dedicated stores like Qdrant or Milvus, you can apply **Product Quantization** during indexing to further reduce memory.

**Concrete Configuration (Qdrant):**
```yaml
indexing_threshold: 20000
optimizers_config:
  memmap_threshold: 10000
quantization_config:
  scalar:
    type: int8
    quantile: 0.99
    always_ram: true
```
This configuration converts vectors to `int8` once the collection reaches 20,000 vectors, reducing RAM usage by 4x.

## 5. Reciprocal Rank Fusion (RRF)

For the best search results, combine vector search with keyword search (BM25) using RRF.

**Algorithm:**$$\text{Score}(d) = \sum_{r \in R} \frac{1}{k + \text{rank}(d, r)}$$Where$k$is a constant (typically 60) and$R$ is the set of rankings from different search methods. This ensures that a document appearing in the top 10 for *both* keyword and vector search is boosted to the absolute top.
