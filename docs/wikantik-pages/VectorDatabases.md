---
canonical_id: 01KQ12YDXBDYP70FJB6WPM01GX
title: Vector Databases
type: article
cluster: agentic-ai
status: active
date: '2026-05-24'
tags:
- vector-database
- embedding
- ann
- hnsw
- retrieval
- pgvector
summary: Production guide to vector databases (pgvector, Qdrant, Pinecone), covering indexing algorithms (HNSW vs. IVF-PQ) and the "Retrieval vs. Search" trade-offs.
auto-generated: false
---
# Vector Databases

A vector database is a specialized storage engine optimized for **Approximate Nearest Neighbor (ANN)** search in high-dimensional spaces. While traditional databases index scalars (strings, ints), vector databases index embeddings (arrays of floats) to enable semantic retrieval.

## The Indexing Algorithms

The choice of index is the single most important performance decision.

| Algorithm | Build Time | Memory Usage | Latency | When to use |
|---|---|---|---|---|
| **HNSW** | High | High | Low | Most production use cases < 10M vectors. |
| **IVF-PQ** | Medium | Low | Medium | Very large scale (> 100M vectors) where RAM is the bottleneck. |
| **Flat** | Zero | Moderate | Very High | Small datasets (< 10k) where 100% recall is mandatory. |

### HNSW (Hierarchical Navigable Small World)
HNSW builds a multi-layered graph where the top layers contain long-distance links and the bottom layers contain short-distance links. Searching follows the graph from top to bottom, finding the "Small World" neighborhood of the query vector.

**Key Parameter:** `ef_construction` (Higher = better recall, slower indexing) and `m` (Max connections per node).

## Production Stack: pgvector (Postgres)

For 90% of applications, **pgvector** is the correct choice because it keeps your relational data and vectors in the same ACID-compliant transaction.

```sql
-- Creating a 1536-dimension vector column (OpenAI standard)
CREATE TABLE documents (
  id serial PRIMARY KEY,
  content text,
  embedding vector(1536)
);

-- Indexing for HNSW (Postgres 16+)
CREATE INDEX ON documents USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- Querying with Cosine Similarity (<=> operator)
SELECT content, 1 - (embedding <=> '[0.12, 0.05, ...]') AS similarity
FROM documents
ORDER BY similarity DESC
LIMIT 5;
```

## Product Comparison (2026)

- **pgvector:** The default for existing Postgres users. Scales to ~10-50M vectors effectively.
- **Qdrant:** High-performance Rust-based engine. Best for pure vector workloads requiring sub-5ms p99 latency.
- **Pinecone:** Managed SaaS. Best for teams that want zero infrastructure management and have significant budgets.
- **Milvus:** Distributed, K8s-native. Best for billion-scale vector search.

## Critical Trade-offs

### 1. Filtering: Pre-filter vs. Post-filter
If you query "Find similar documents where `user_id = 42`":
- **Pre-filtering:** The database finds all rows where `user_id = 42` first, then does the vector search. This is more accurate but can be slow if the filter is not selective.
- **Post-filtering:** The database finds the top 100 similar documents, then filters by `user_id`. This is fast but might return 0 results if none of the top 100 belong to user 42.

### 2. Dimensionality vs. Recall
Higher dimensionality (e.g., 3072) provides better semantic resolution but increases index size and search latency. 768 or 1536 dimensions is the sweet spot for most RAG applications.

### 3. Quantization
**Product Quantization (PQ)** or **Scalar Quantization (SQ)** compresses vectors (e.g., from 32-bit floats to 8-bit ints). This can reduce memory usage by 4x with only a 1-2% drop in recall.

## Further Reading
- [[EmbeddingsVectorDB]] — How to generate high-quality vectors.
- [[RagImplementationPatterns]] — Connecting vector DBs to LLMs.
- [[HybridRetrieval]] — Combining Keyword search (BM25) with Vector search.
