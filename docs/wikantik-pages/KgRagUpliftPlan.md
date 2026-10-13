---
status: active
date: '2026-05-15'
title: 'Knowledge Graph RAG (GraphRAG) Uplift Plan: 2025 Roadmap'
related:
- KnowledgeGraphExtractionBenchmarks
- McpIntegrationTestFix
- PageGraphVsKnowledgeGraph
- RetrievalExperimentHarness
canonical_id: 01J7KQTCCQ3H9K0M9E95ZCK3KH
cluster:
- wikantik-development
- devops-sre
type: article
tags:
- rag
- graphrag
- embeddings
- knowledge-graph
- search-optimization
- infrastructure
summary: Roadmap to upgrade the Wikantik RAG pipeline to hybrid Vector+Graph (GraphRAG),
  targeting 90%+ accuracy on multi-hop and schema-bound reasoning queries.
---
# Knowledge Graph RAG (GraphRAG) Uplift Plan: 2025 Roadmap

> **Status (2026-10-13):** A historical planning document; the plan was not executed as written, and the target dates in section 5 passed without those milestones. What happened instead:
>
> - **Hybrid retrieval shipped as BM25 + dense with Reciprocal Rank Fusion** ([HybridRetrieval](HybridRetrieval)), and RAG context is assembled by the version-pinned, cited context bundle (`GET /api/bundle`, `assemble_bundle`) rather than a graph-plus-vector fusion.
> - **"Path B" (Knowledge Graph traversal feeding ranking) was never built as the parallel retrieval path described below. The nearest thing that was built, a Knowledge Graph rerank stage over the fused results, measured zero net lift in a 2026-06-16 ceiling experiment even with a Claude-quality Knowledge Graph, and was deleted in 2026-07.** See [KnowledgeGraphRerank](KnowledgeGraphRerank). The accuracy targets in section 1 were never validated against this corpus.
> - **LLM triplet extraction shipped** as the Ollama chunk-level and page-level entity extractors (default `gemma4-graph:12b`), feeding a proposal queue with an asynchronous judge and human review ([WikantikKnowledgeGraph](WikantikKnowledgeGraph), [KnowledgeGraphExtractionBenchmarks](KnowledgeGraphExtractionBenchmarks)). `canonical_id` stability shipped with the structural spine ([StructuralSpineDesign](StructuralSpineDesign)).
> - **The trust tiers became a `machine` / `human` review tier** on every node and edge ([ProvenanceModel](ProvenanceModel)); `human-vetted` and `ai-reviewed` as used below are not the current terms.

As of mid-2025, pure vector-based Retrieval Augmented Generation (RAG) has reached a performance ceiling. While effective for simple semantic recall, it fails spectacularly on schema-bound and multi-hop reasoning queries. This document outlines the uplift plan to transition Wikantik to a **Hybrid GraphRAG** architecture.

## 1. The Performance Gap (2025 Benchmarks)

Recent evaluations using the [Retrieval Experiment Harness](RetrievalExperimentHarness) confirm the industry-wide trend: Vector RAG accuracy collapses as query complexity increases.

| Query Complexity | Vector RAG | GraphRAG (Target) | Delta |
| :--- | :--- | :--- | :--- |
| Simple Factoid | 92% | 88% | -4% |
| **Multi-Hop Reasoning** | **22%** | **78%** | **+56%** |
| **Global Theme Synthesis** | **5%** | **85%** | **+80%** |
| Schema-Bound (e.g. KPIs) | 0% | 92% | +92% |

The "Graph Tax" (latency) is estimated at **~200ms** per request, which is acceptable given the massive gains in precision.

## 2. Phase I: Extraction & Indexing Uplift

The primary bottleneck is currently the quality of entity extraction. We will move from a "Chunk-First" to an "Entity-First" indexing model.

1.  **LLM-Augmented Triplets:** Upgrade from basic regex extraction to gemma4-based triplet extraction ($Subject \xrightarrow{predicate} Object$).
2.  **Canonical ID Stability:** Ensure every extracted entity maps to a stable `canonical_id` to prevent graph fragmentation.
3.  **Benchmark Integration:** Use [Knowledge Graph Extraction Benchmarks](KnowledgeGraphExtractionBenchmarks) to measure triplet recall on every commit.

## 3. Phase II: Hybrid Retrieval Architecture

The new pipeline will execute two parallel retrieval paths:

*   **Path A (Recall):** Standard BM25 + Dense Vector search on the `chunk_embeddings` table.
*   **Path B (Precision):** BFS-based traversal of the `kg_edges` table starting from identified entities in the query.

The results are fused using **Reciprocal Rank Fusion (RRF)** before being presented to the LLM context window. This architecture is detailed in the [Page Graph vs Knowledge Graph](PageGraphVsKnowledgeGraph) comparison.

## 4. Phase III: Validation & Quality Control

To prevent regressions, the following controls will be implemented:

*   **MCP Guardrails:** All graph mutations must pass the [McpIntegrationTestFix](McpIntegrationTestFix) suite.
*   **Trust Tiers:** Only `human-vetted` or high-confidence `ai-reviewed` nodes will be admitted to the Path B retrieval loop.

## 5. Timeline

| Milestone | Target Date | Status |
| :--- | :--- | :--- |
| Baseline Benchmark | 2026-04-24 | [DONE] |
| Triplet Extractor Upgrade | 2026-06-01 | [PLANNED] |
| Hybrid Fusion Engine | 2026-07-15 | [PLANNED] |
| Production Cutover | 2026-08-30 | [PLANNED] |

This plan is managed by the [Infrastructure & SRE](InfrastructureSreHub) team.
