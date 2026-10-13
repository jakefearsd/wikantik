---
status: active
date: '2026-05-15'
title: Wikantik Hybrid Search Architecture
canonical_id: 01KQTCB8K3TXN8SKQFJ7WZ7FJC
cluster: wikantik-platform
relations:
- type: component_of
  target_id: WikantikPlatformHub
- type: extension_of
  target_id: IndustrialSearchSystems
type: article
tags:
- search
- architecture
- hybrid-retrieval
- rrf
- lucene
- pgvector
summary: 'Technical deep-dive into Wikantik''s hybrid search pipeline: BM25 lexical
  retrieval, dense vector retrieval, and RRF fusion.'
---
# Wikantik Hybrid Search Architecture

Wikantik features a multi-stage retrieval pipeline designed to serve both humans and AI agents. It goes beyond simple keyword matching by fusing lexical and semantic retrieval, and it degrades to lexical-only when the semantic side is unavailable.

## 1. The Retrieval Pipeline

When a query is submitted (via `GET /api/search` or the `retrieve_context` tool), it undergoes three phases. Operator-level detail — every configuration key, the metrics, and the failure runbook — lives on [Hybrid Retrieval](HybridRetrieval).

### A. Lexical Retrieval (BM25)
The first stage uses **Apache Lucene** to perform a classic BM25 search.
*   **Engine**: `com.wikantik.search.subsystem.lucene`
*   **Strength**: Excellent at finding exact matches, technical terms, and unique identifiers (e.g., "RTO", "SPIA").
*   **Scope**: Indexes the page body (`contents`), title (`name`), `author`, attachment names, `keywords`, and the frontmatter fields `tags`, `cluster` and `summary`. Query-time field boosts weight the title (4.0) above `keywords` (3.0), `summary` (2.5), `tags` and `cluster` (2.0), and the body, author and attachments (1.0).

### B. Dense Retrieval (HNSW)
In parallel, the query is converted into a high-dimensional vector.
*   **Engine**: `com.wikantik.search.hybrid.DenseRetriever`
*   **Pipeline**: The query is embedded by the configured model (default `qwen3-embedding-0.6b`, 1024 dimensions, served by Ollama) and compared against chunked content in the `content_chunk_embeddings` table. The nearest-neighbour search runs in the backend selected by `wikantik.search.dense.backend` — `lucene-hnsw` (the default, an in-process HNSW index), `pgvector`, or `inmemory`. Chunk scores are collapsed to page scores with `mean_top_3`.
*   **Strength**: Captures semantic meaning and intent, finding relevant content even when keywords do not match exactly.

### C. Hybrid Fusion (RRF)
The results from BM25 and Dense retrieval are combined using weighted **Reciprocal Rank Fusion (RRF)**.
*   **Mechanism**: Merges the ranked lists by summing `weight / (k + rank)` for each list. The defaults are `k = 60`, BM25 weight 1.5, dense weight 1.5, and only the first 20 results of each list contribute.
*   **Resilience**: The system is designed to "fail-safe." If the embedding service is down or its circuit breaker is open, the fuser automatically collapses to the lexical result, ensuring search remains functional.

### Not in the pipeline: Knowledge Graph reranking
KG reranking is **not used** — it was shelved after a measured zero-lift ceiling spike (2026-06-16) and removed in 2026-07. See [KnowledgeGraphRerank](KnowledgeGraphRerank).

The context bundle (`GET /api/bundle`, `assemble_bundle`) is a separate path: it retrieves and fuses at the chunk level instead of the page level (see [Hybrid Retrieval](HybridRetrieval)).

## 2. The Embedding Infrastructure

Wikantik's dense search depends on a chunk-and-embed pipeline:
1.  **Chunking**: Pages are split into logical chunks (by headings, with overlap) during the save process.
2.  **Embedding**: Chunks are processed by the `TextEmbeddingClient`, which talks to Ollama (the only supported backend). Each chunk is embedded with its page title, cluster, heading path and summary prepended as context.
3.  **Storage**: Vectors are persisted in PostgreSQL (`content_chunk_embeddings`, including a pgvector `vector(1024)` column with an HNSW index). The serving index is whichever dense backend is configured; the default keeps its HNSW graph in the JVM and rebuilds it from the table on boot.

## 3. Evaluation and Refinement

Search quality is measured at three levels:
*   **`bin/search-eval`** — a standalone Python 3 script (it is not a `wikantik-tools` module feature and is not backed by Java classes). It runs the 40 `(query, ideal_page)` pairs in `eval/retrieval-queries.csv` against `GET /api/search` on a running wiki and reports **Recall@5**, **Recall@20** and **Mean Reciprocal Rank (MRR)**, overall and per category. See [Wikantik Search Refinement](WikantikSearchRefinement).
*   **The nightly runner** — `DefaultRetrievalQualityRunner` scores the `core-agent-queries` set in `bm25` and `hybrid` modes at 03:00 UTC, stores nDCG@5/10, Recall@20 and MRR in `retrieval_runs`, and publishes them as `wikantik_retrieval_*` Prometheus gauges; the admin dashboard is `/admin/retrieval-quality`. See [Running the Retrieval Quality Harness](RunningTheRetrievalQualityHarness).
*   **The offline experiment harness** — test-scope classes (`ExperimentEvaluator` and friends) used for embedding-model and fusion selection; see [Retrieval Experiment Harness](RetrievalExperimentHarness). Its frozen reports are committed under `eval/` (e.g. `eval/grand-finale.txt`).

---
**See Also:**
- [Hybrid Retrieval](HybridRetrieval) — Operator reference: wiring, configuration, metrics, runbook.
- [Building Industrial Search Systems](IndustrialSearchSystems) — The underlying theory.
- [Evaluating Retrieval Quality](EvaluatingRetrievalQuality) — Deep dive into the math of MRR.
- [Knowledge Graph Extraction](WikantikKnowledgeGraph) — How entities and co-mention edges are extracted (they no longer feed ranking).
