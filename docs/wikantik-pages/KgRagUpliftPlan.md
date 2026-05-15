---
summary: A technical roadmap for upgrading the Wikantik RAG pipeline to a hybrid Vector+Graph
  architecture, targeting 90%+ accuracy on multi-hop reasoning.
date: 2026-05-15T00:00:00Z
cluster: Infrastructure & SRE
related:
- KnowledgeGraphExtractionBenchmarks
- McpIntegrationTestFix
- PageGraphVsKnowledgeGraph
- RetrievalExperimentHarness
canonical_id: 01J7KQTCCQ3H9K0M9E95ZCK3KH
type: article
title: 'Knowledge Graph RAG (GraphRAG) Uplift Plan: 2025 Roadmap'
tags:
- rag
- graphrag
- embeddings
- knowledge-graph
- search-optimization
- infrastructure
status: active
hubs:
- InfrastructureSreHub
---

# Knowledge Graph RAG (GraphRAG) Uplift Plan: 2025 Roadmap

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
