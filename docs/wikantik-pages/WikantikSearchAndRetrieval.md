---
date: '2026-05-15'
summary: Technical deep-dive into Wikantik's 3-phase hybrid search pipeline. Covers
  lexical retrieval (Lucene), dense vector retrieval (pgvector), and Graph-Aware reranking
  using RRF fusion.
cluster: wikantik-platform
canonical_id: 01KQTCB8K3TXN8SKQFJ7WZ7FJC
type: article
relations:
- type: component_of
  target_id: WikantikPlatformHub
- type: extension_of
  target_id: IndustrialSearchSystems
title: Wikantik Hybrid Search Architecture
status: active
tags:
- search
- architecture
- hybrid-retrieval
- rrf
- lucene
- pgvector
- graph-rerank
hubs:
- WikantikPlatformHub
---

# Wikantik Hybrid Search Architecture

Wikantik features a high-precision, multi-stage retrieval pipeline designed to serve both humans and AI agents. It goes beyond simple keyword matching by fusing lexical, semantic, and relational data.

## 1. The Retrieval Pipeline

When a query is submitted (via `/api/search` or the `retrieve_context` tool), it undergoes four distinct phases:

### A. Lexical Retrieval (BM25)
The first stage uses **Apache Lucene** to perform a classic BM25 search. 
*   **Engine**: `com.wikantik.search.subsystem.lucene`
*   **Strength**: Excellent at finding exact matches, technical terms, and unique identifiers (e.g., "RTO", "SPIA").
*   **Scope**: Indexes page titles, content body, and frontmatter keywords.

### B. Dense Retrieval (pgvector)
In parallel, the query is converted into a high-dimensional vector.
*   **Engine**: `com.wikantik.search.hybrid.DenseRetriever`
*   **Pipeline**: The query is embedded (typically 768d via `nomic-embed-text`) and compared against chunked content in the `content_chunk_embeddings` table.
*   **Strength**: Captures semantic meaning and intent, finding relevant content even when keywords do not match exactly.

### C. Hybrid Fusion (RRF)
The results from BM25 and Dense retrieval are combined using **Reciprocal Rank Fusion (RRF)**.
*   **Mechanism**: Merges the ranked lists by summing the inverse ranks: $1/(k + \text{rank})$.
*   **Resilience**: The system is designed to "fail-safe." If the embedding service is down, the fuser automatically collapses to the lexical result, ensuring search remains functional.

### D. Knowledge Graph Reranking
The final stage is the **KG-Aware Reranker** (`GraphRerankStep`). It applies a "proximity boost" to the fused results based on the Wikantik Knowledge Graph.

1.  **Query-Entity Resolution**: `QueryEntityResolver` matches query terms to canonical KG entity names.
2.  **Graph Traversal**: `GraphProximityScorer` runs a multi-source BFS from the query entities through the **undirected co-mention neighborhood**.
3.  **Proximity Score**: Reachable entities get a distance score of $1 / (1 + \text{hops})$, capped at a maximum radius.
4.  **Final Ordering**: The boost is applied to the fused list: $\text{Rank}_{fused} + (\text{boost} \times \text{proximity})$. This surfaces highly relevant documentation clusters together.

## 2. The Embedding Infrastructure

Wikantik's dense search depends on a chunk-and-embed pipeline:
1.  **Chunking**: Pages are split into logical chunks (usually by headings) during the save process.
2.  **Embedding**: Chunks are processed by the `EmbeddingClient` (communicating with Ollama, OpenAI, or TEI).
3.  **Storage**: Vectors are stored in PostgreSQL with an **HNSW index** for fast $O(\log n)$ nearest-neighbor retrieval.

## 3. Evaluation and Refinement

Search quality is measured by a standalone utility in the `wikantik-tools` module.
*   **Tool**: `bin/search-eval` (backed by `ExperimentEvaluator.java`).
*   **Harness**: Runs a set of 40+ "Ideal Queries" against the running wiki.
*   **Metrics**: Reports **Recall@5**, **Recall@20**, and **Mean Reciprocal Rank (MRR)**.
*   **Baseline**: Results are committed to `eval/` (e.g., `grand-finale.txt`) to ensure retrieval performance never regresses during refactoring.

---
**See Also:**
- [Building Industrial Search Systems](IndustrialSearchSystems) — The underlying theory.
- [Evaluating Retrieval Quality](EvaluatingRetrievalQuality) — Deep dive into the math of MRR.
- [Knowledge Graph Extraction](WikantikKnowledgeGraph) — How the reranking data is generated.
