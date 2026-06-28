---
summary: Technical overview of the graph-aware reranking strategy in Wikantik. Explains
  how the system combines traditional lexical BM25 scores with dense vector similarity
  and Knowledge Graph (KG) co-mention data to improve retrieval precision.
title: Knowledge Graph Rerank
tags:
- knowledge-graph
- retrieval
- rerank
- bm25
- embeddings
- search
cluster: wikantik-development
type: article
date: 2026-05-03T00:00:00Z
status: active
canonical_id: 01KQPQVYPFSBSGX38YP6XFQPMV
---

# Knowledge Graph Rerank

> **Status: OFF by default — shelved.** Knowledge-Graph reranking is **not**
> used in default hybrid search. The boost weight defaults to **0** and the
> page-level reranker was **never wired into production**. A 2026-06-16 ceiling
> spike measured **zero net lift** even with a Claude-quality KG: relational
> section relevance is not the same as entity-proximity. The rerank was shelved
> (Phase 4 Track A) and left **dormant, not removed**. Do not expect KG
> reranking to affect retrieval results unless it is explicitly re-enabled.

> 🌐 **Product overview:** [Knowledge graph on wikantik.com](https://www.wikantik.com/platform/knowledge-graph.html) — a plain-language walkthrough for readers and AI agents.


Knowledge Graph Rerank is the final stage of the Wikantik retrieval pipeline. It transforms a raw list of search results into a contextually relevant set of pages by leveraging the semantic relationships stored in the [Knowledge Graph](Knowledge Graph).

## The Retrieval Pipeline

The Wikantik search engine (exposed via `/api/search` and the `/knowledge-mcp` tool `retrieve_context`) follows a multi-stage process:

1.  **Lexical Retrieval (BM25)**: A fast keyword-based search against the Lucene index.
2.  **Semantic Retrieval (Dense Vector)**: Cosine similarity search using embeddings stored in `pgvector`.
3.  **Hybrid Fusion**: Combining the scores from BM25 and Vector search using Reciprocal Rank Fusion (RRF).
4.  **Graph Rerank (Final Stage)**: Adjusting the scores based on "Node Mention" density and co-occurrence in the graph.

## How Graph Rerank Works

The reranker identifies "seed nodes" within the top-N results from the hybrid fusion stage. It then uses the [KnowledgeGraphService](KnowledgeGraphService) to find co-mentioned neighbors and high-confidence relationships.

- **Boost by Co-mention**: If a page is not in the top results but is heavily co-mentioned with multiple pages that *are* in the top results, its score is boosted.
- **Entity Density**: Pages that contain a high density of relevant entities (nodes) related to the query are prioritized.
- **Fail-Closed Fallback**: As documented in [WikantikDevelopment](WikantikDevelopment), if the embedding service or the graph database is unreachable, the system fails closed to a BM25-only result set to ensure availability.

## Configuration

Reranking behavior is controlled via `wikantik-custom.properties`:

```properties
# Graph reranking boost weight — defaults to 0 (OFF; shelved 2026-06-16)
wikantik.search.graphRerank.boost = 0

# Weights for different retrieval signals
jspwiki.search.hybrid.bm25Weight = 0.4
jspwiki.search.hybrid.vectorWeight = 0.6

# Rerank depth (how many initial results to consider)
jspwiki.search.graphRerank.depth = 20
```

## Performance Impact

Benchmarks recorded in [KnowledgeGraphExtractionBenchmarks](KnowledgeGraphExtractionBenchmarks) show that while graph reranking adds approximately 15-20ms of latency, it significantly improves `Recall@5` for "multi-hop" queries where the relevant information is spread across related topics rather than a single page.
