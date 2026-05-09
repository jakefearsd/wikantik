---
cluster: generative-ai
canonical_id: 01KQ0P44Q3Y083P3R11WCRW3XR
title: Embeddings Vector DB
type: article
tags:
- generative-ai
- vector-databases
- hnsw
- search
- rag
status: active
date: 2025-05-15
summary: Technical guide to Vector Databases for AI applications. Covers HNSW indexing, Approximate Nearest Neighbor (ANN) search, and pgvector implementation.
auto-generated: false
---

# Embeddings and Vector Databases: Semantic Retrieval

Vector databases are specialized storage engines designed to store and perform high-speed similarity searches on high-dimensional vector embeddings.

## 1. Search Mechanics: ANN vs. Exact

Performing an exact search (comparing a query against every vector in the DB) is $O(N)$ and too slow for large datasets.
*   **Approximate Nearest Neighbor (ANN):** Algorithms that trade a small amount of accuracy for massive gains in speed.
*   **HNSW (Hierarchical Navigable Small World):** The current industry standard. It builds a graph where nodes are connected based on proximity, allowing the search to "jump" across the space to find the cluster of neighbors quickly.

## 2. Key Indexing Parameters (HNSW)

When configuring a vector index (e.g., in `pgvector` or Pinecone), two parameters are critical:
*   **m:** The number of bi-directional links created for every new element. Higher $m$ = higher accuracy, but higher memory use.
*   **ef_construction:** The size of the dynamic candidate list during index building. 
*   **Concrete Spec:** For 1536-dim vectors, start with `m=16` and `ef_construction=64` for a balance of build speed and search precision.

## 3. Hybrid Search: Combining BM25 and Vector

Vector search is great at **semantic intent** but poor at **exact keyword matching** (e.g., searching for a specific serial number).
*   **Solution:** Use Hybrid Search. Combine results from a traditional keyword index (BM25) and a vector index using **Reciprocal Rank Fusion (RRF)**.
*   **Concrete Example:** A search for "XJ-9000 troubleshooting" will find the document with the exact model number (Keyword) AND documents about related mechanical failures (Vector).

## 4. Vector Database Options

| Database | Implementation | Best For |
| :--- | :--- | :--- |
| **Pinecone/Zilliz** | Managed Service | Rapid scale, zero infrastructure management. |
| **pgvector** | Postgres Extension | Applications already using SQL; keeps data/vectors together. |
| **Milvus/Qdrant** | Dedicated OSS | High-performance, self-hosted clusters. |
| **Chroma** | Lightweight/Local | Prototyping and local-only agent workflows. |

---
**See Also:**
- [Embeddings In Gen AI](EmbeddingsInGenAI) — Understanding the vector source.
- [Context Window Management](ContextWindowManagement) — Scaling RAG context.
- [Data Lakehouse](DataLakehouse) — Storing raw data for embedding pipelines.
