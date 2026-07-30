---
summary: Historical record of Wikantik's Knowledge Graph rerank stage — how it worked,
  and why it was removed in 2026-07 after measuring zero retrieval lift.
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

> **Status: REMOVED (2026-07). This page is a historical record.**
> Knowledge-Graph reranking is **not** part of Wikantik retrieval, and the code
> that implemented it has been deleted. A 2026-06-16 ceiling spike measured
> **zero net lift** even with a Claude-quality KG — relational section relevance
> is not the same thing as entity proximity — and the shipped dense-chunk
> context bundle never invoked the step at all. The feature was first shelved
> (dormant, boost = 0), then removed once it was clear the page-level design
> could never be the fix.

> 🌐 **Product overview:** [Knowledge graph on wikantik.com](https://www.wikantik.com/platform/knowledge-graph.html) — a plain-language walkthrough for readers and AI agents.


Knowledge Graph Rerank *was* intended as the final stage of the Wikantik
retrieval pipeline: reordering a fused result list using the semantic
relationships stored in the [Knowledge Graph](Knowledge Graph). This page
records what was built and what the measurement showed, so the experiment is not
repeated blind.

## The retrieval pipeline as it ships today

The Wikantik search engine (exposed via `/api/search` and the `/knowledge-mcp`
tool `retrieve_context`) runs three stages:

1.  **Lexical Retrieval (BM25)**: A fast keyword-based search against the Lucene index.
2.  **Semantic Retrieval (Dense Vector)**: Cosine similarity search using embeddings.
3.  **Hybrid Fusion**: Combining BM25 and dense scores with Reciprocal Rank Fusion (RRF).

A fourth **Graph Rerank** stage used to sit after fusion. It is gone. See
[HybridRetrieval](HybridRetrieval) for the pipeline that actually serves
queries.

## How the graph rerank worked

The reranker identified "seed nodes" within the top-N results from hybrid
fusion, then used the [KnowledgeGraphService](KnowledgeGraphService) to find
co-mentioned neighbours and high-confidence relationships.

- **Boost by co-mention**: a page heavily co-mentioned with pages already in the
  top results had its score boosted.
- **Entity density**: pages containing a high density of query-relevant entities
  were prioritised.
- **Fail-closed fallback**: if the embedding service or graph store was
  unreachable, the system fell back to BM25-only results. That fail-closed
  behaviour still governs hybrid retrieval today — see
  [WikantikDevelopment](WikantikDevelopment).

## Why it was removed

Two independent findings, both from the 2026-06-16 ceiling spike:

1.  **Structural.** The rerank reordered whole *page names* inside the
    page-gated retrieval path. The shipped context bundle uses a global
    dense-chunk source that goes straight from query embedding to top-K chunks,
    so it never called the rerank at all. Output was bit-identical at every
    boost value tried — the knob could not affect the path being served.
2.  **Quality.** On the page-gated path where the step *did* run, it was
    net-negative. Re-extracting the evaluation slice with a much richer
    Claude-generated KG (84 mentions vs 65, every node embedded) moved the
    result to **zero net lift at recall@12 and −1 at recall@5**.

The root cause: the KG knows *which entities are related*. It does not know
*which section answers a relational question*. Entity coverage was never the
bottleneck for these queries, so a better KG could not help.

## What replaced it

Nothing — the retrieval gains came from elsewhere. The levers that actually
moved section recall (roughly 0.60 → 0.74) were a chunker heading-fidelity fix
and contextual document embeddings, both documented on
[HybridRetrieval](HybridRetrieval).

The Knowledge Graph and the RDF ontology remain first-class for the human
knowledge base, agent traversal, and SPARQL. Their value is as a curated
knowledge surface, not as a retrieval ranking signal.

If relational retrieval becomes a priority again, the reframed lever is a
**section-level** signal inside the dense bundle — a new design, not the
page-level boost described here.
