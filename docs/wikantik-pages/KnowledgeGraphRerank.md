---
canonical_id: 01KQPQVYPFSBSGX38YP6XFQPMV
date: 2026-05-03T00:00:00Z
cluster: wikantik-development
type: design
tags:
- knowledge-graph
- retrieval
- rerank
- bm25
- embeddings
- search
title: Knowledge Graph Rerank
relations:
- type: part-of
  target_id: 01KQ0P44YWV8Q0JMN1H2H5EGDX
- type: implements
  target_id: 01KQEKGDAZH3G3X2J4VFM9MP88
summary: Technical overview of the graph-aware reranking strategy in Wikantik. Explains
  how the system combines traditional lexical BM25 scores with dense vector similarity
  and Knowledge Graph (KG) co-mention data to improve retrieval precision.
status: active
---

# Knowledge Graph Rerank

Knowledge Graph Rerank is the final stage of the Wikantik retrieval pipeline. It transforms a raw list of search results into a contextually relevant set of pages by leveraging the semantic relationships stored in the [[Knowledge Graph]].

## The Retrieval Pipeline

The Wikantik search engine (exposed via `/api/search` and the `/knowledge-mcp` tool `retrieve_context`) follows a multi-stage process:

1.  **Lexical Retrieval (BM25)**: A fast keyword-based search against the Lucene index.
2.  **Semantic Retrieval (Dense Vector)**: Cosine similarity search using embeddings stored in `pgvector`.
3.  **Hybrid Fusion**: Combining the scores from BM25 and Vector search using Reciprocal Rank Fusion (RRF).
4.  **Graph Rerank (Final Stage)**: Adjusting the scores based on "Node Mention" density and co-occurrence in the graph.

## How Graph Rerank Works

The reranker identifies "seed nodes" within the top-N results from the hybrid fusion stage. It then uses the [[KnowledgeGraphService]] to find co-mentioned neighbors and high-confidence relationships.

- **Boost by Co-mention**: If a page is not in the top results but is heavily co-mentioned with multiple pages that *are* in the top results, its score is boosted.
- **Entity Density**: Pages that contain a high density of relevant entities (nodes) related to the query are prioritized.
- **Fail-Closed Fallback**: As documented in [[WikantikDevelopment]], if the embedding service or the graph database is unreachable, the system fails closed to a BM25-only result set to ensure availability.

## Configuration

Reranking behavior is controlled via `wikantik-custom.properties`:

```properties
# Enable/Disable graph reranking
jspwiki.search.graphRerank.enabled = true

# Weights for different retrieval signals
jspwiki.search.hybrid.bm25Weight = 0.4
jspwiki.search.hybrid.vectorWeight = 0.6

# Rerank depth (how many initial results to consider)
jspwiki.search.graphRerank.depth = 20
```

## Performance Impact

Benchmarks recorded in [[KnowledgeGraphExtractionBenchmarks]] show that while graph reranking adds approximately 15-20ms of latency, it significantly improves `Recall@5` for "multi-hop" queries where the relevant information is spread across related topics rather than a single page.
