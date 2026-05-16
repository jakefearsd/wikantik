---
canonical_id: 01KRQJ845TTE8DNKNVWC8EBVN3
type: article
tags:
- search
- graph-theory
- reranking
- proximity-search
- bfs
- algorithms
title: Graph-Aware Reranking and Semantic Proximity
relations:
- type: component_of
  target_id: WikantikSearchAndRetrieval
- type: extension_of
  target_id: SpectralGraphTheoryConceptual
summary: Technical analysis of Wikantik's Phase 3 search reranker. Details multi-source
  BFS traversal through the co-mention graph, the max-proximity scoring heuristic,
  and the stable-sort boost mechanism.
status: active
date: '2026-05-15'
cluster: wikantik-platform
---

# Graph-Aware Reranking and Semantic Proximity

The final stage of the Wikantik search pipeline (Phase 3) is a **Graph-Aware Reranker** (`GraphRerankStep`). While Phase 1 and 2 focus on textual and vector similarity, Phase 3 utilizes the **topology** of the Knowledge Graph to surface related content that might lack direct keyword or vector overlap.

## 1. The Proximity Heuristic

The reranker operates on a simple but powerful intuition: **"If a page mentions entities that are closely connected in the Knowledge Graph to the user's intent, that page is likely highly relevant."**

### A. Graph Traversal (Multi-Source BFS)
The process begins by resolving query terms into a set of seed entity IDs ($Q$). The system then performs a multi-source Breadth-First Search (BFS) through the `kg_edges` adjacency map up to a maximum radius ($H_{max}$, typically 2).
*   **Distance Calculation**: Every reachable entity $e$ is assigned a distance $d(Q, e)$ equal to the shortest undirected hop count from any seed entity.

### B. Scoring Function
The proximity score $S_{prox}$ for a candidate page is determined by the **maximum proximity** of its mentioned entities:
$$ S_{prox}(p) = \max_{m \in \text{Mentions}(p)} \left( \frac{1}{1 + d(Q, m)} \right) $$
*   **Why Max?**: Using the maximum (rather than the mean or sum) ensures that a single high-quality match is enough to boost a page, preventing relevant content from being diluted by "noisy" co-mentions of unrelated entities.

## 2. Implementation: The GraphRerankStep

The implementation is designed for high-performance and **Graceful Degradation**.

1.  **Candidate Anchoring**: The input to the step is the fused list from Phase 2 (RRF). No pages are added or removed; the candidate set is fixed.
2.  **Bulk Loading**: The `PageMentionsLoader` fetches all entity mentions for the entire candidate set (e.g., top 100 pages) in a **single SQL round-trip** using the `ANY(?)` operator.
3.  **Base Rank Scaling**: To ensure the boost is proportional to the initial relevance, each page is assigned a base score derived from its fused rank: $B(p) = 1.0 - (\text{rank} / N)$.
4.  **The Boost Calculation**:
    $$ \text{FinalScore}(p) = B(p) + (\text{boost\_weight} \times S_{prox}(p)) $$
5.  **Stable Reordering**: The list is re-sorted by the final score. Because the sort is stable, pages with equal proximity scores retain their relative RRF ordering.

## 3. Fail-Safe Mechanics

The reranker is a "non-critical" enhancement. The `GraphRerankStep` is wrapped in a fail-closed logic:
*   **Disabled**: If the graph feature is off, returns input list verbatim.
*   **Index Not Ready**: If the `kg_edges` table is being rebuilt or exceeds memory caps, returns input list.
*   **Zero Matches**: If no query entities are resolved or no candidates mention the graph neighborhood, the proximity score is $0.0$ for all, and the RRF order is preserved bit-identically.

---
**See Also:**
- [Wikantik Search and Retrieval](WikantikSearchAndRetrieval) — The 4-phase overview.
- [Spectral Graph Theory](SpectralGraphTheoryConceptual) — The theory of graph shape.
- [Knowledge Graph Extraction](WikantikKnowledgeGraph) — Generating the co-mention edges.
