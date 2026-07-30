---
status: active
date: '2026-05-15'
summary: How graph-proximity reranking worked — multi-source BFS over a co-mention
  graph, max-proximity scoring, stable-sort boost. Removed from Wikantik in 2026-07.
tags:
- search
- graph-theory
- reranking
- proximity-search
- bfs
- algorithms
type: article
relations:
- type: component_of
  target_id: WikantikSearchAndRetrieval
- type: extension_of
  target_id: SpectralGraphTheoryConceptual
canonical_id: 01KRQJ845TTE8DNKNVWC8EBVN3
cluster: wikantik-platform
title: Graph-Aware Reranking and Semantic Proximity
---

# Graph-Aware Reranking and Semantic Proximity

> **Removed from Wikantik (2026-07).** This page documents an algorithm that
> Wikantik no longer runs. The reranker shipped, was measured, and was deleted
> after a ceiling experiment found no net retrieval lift even with a
> high-quality knowledge graph — see [Knowledge Graph Rerank](KnowledgeGraphRerank)
> for the verdict. The algorithm and its analysis are kept because the technique
> is sound and the negative result is worth understanding; production search is
> BM25 + dense fused with RRF.

Wikantik's search pipeline once ended in a **Graph-Aware Reranker**. Where the
earlier stages score textual and vector similarity, this stage used the
**topology** of the Knowledge Graph to surface related content that might lack
direct keyword or vector overlap.

## 1. The Proximity Heuristic

The reranker operated on a simple and appealing intuition: **"If a page mentions entities that are closely connected in the Knowledge Graph to the user's intent, that page is likely highly relevant."**

### A. Graph Traversal (Multi-Source BFS)
The process began by resolving query terms into a set of seed entity IDs ($Q$), then performed a multi-source Breadth-First Search (BFS) through the `kg_edges` adjacency map up to a maximum radius ($H_{max}$, typically 2).
*   **Distance Calculation**: Every reachable entity $e$ is assigned a distance $d(Q, e)$ equal to the shortest undirected hop count from any seed entity.

### B. Scoring Function
The proximity score $S_{prox}$ for a candidate page was determined by the **maximum proximity** of its mentioned entities:

$$
S_{prox}(p) = \max_{m \in \text{Mentions}(p)} \left( \frac{1}{1 + d(Q, m)} \right)
$$

*   **Why Max?**: Using the maximum (rather than the mean or sum) ensures that a single high-quality match is enough to boost a page, preventing relevant content from being diluted by "noisy" co-mentions of unrelated entities.

## 2. Implementation

The implementation was built for high performance and **graceful degradation**.

1.  **Candidate Anchoring**: The input was the fused list from the RRF stage. No pages were added or removed; the candidate set was fixed.
2.  **Bulk Loading**: All entity mentions for the entire candidate set (e.g., top 100 pages) were fetched in a **single SQL round-trip** using the `ANY(?)` operator.
3.  **Base Rank Scaling**: To keep the boost proportional to initial relevance, each page was assigned a base score derived from its fused rank: $B(p) = 1.0 - (\text{rank} / N)$.
4.  **The Boost Calculation**:

    $$
    \text{FinalScore}(p) = B(p) + (\text{boost\_weight} \times S_{prox}(p))
    $$

5.  **Stable Reordering**: The list was re-sorted by the final score. Because the sort was stable, pages with equal proximity scores retained their relative RRF ordering.

## 3. Fail-Safe Mechanics

The reranker was a non-critical enhancement, wrapped in fail-closed logic:
*   **Disabled**: with the feature off, returned the input list verbatim.
*   **Index Not Ready**: if the `kg_edges` table was being rebuilt or exceeded memory caps, returned the input list.
*   **Zero Matches**: if no query entities resolved, or no candidate mentioned the graph neighbourhood, every proximity score was \$0.0$ and the RRF order was preserved bit-identically.

## 4. Why it did not survive

The fail-safe design worked exactly as intended — and that turned out to be the
problem. The stage could only reorder pages it was handed, and the shipped
context bundle stopped handing it anything: the bundle retrieves chunks globally,
never page-gating, so the rerank sat outside the serving path entirely. On the
path where it did run, a much richer knowledge graph moved measured recall from
slightly-negative to merely neutral. Entity proximity answers *which entities
relate*; it does not answer *which passage answers the question*.

---
**See Also:**
- [Wikantik Search and Retrieval](WikantikSearchAndRetrieval) — The 4-phase overview.
- [Spectral Graph Theory](SpectralGraphTheoryConceptual) — The theory of graph shape.
- [Knowledge Graph Extraction](WikantikKnowledgeGraph) — Generating the co-mention edges.
- [Knowledge Graph Rerank](KnowledgeGraphRerank) — The measured verdict and the removal.
- [Hybrid Retrieval](HybridRetrieval) — The BM25 + dense pipeline that actually serves queries.
