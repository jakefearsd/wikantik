---
canonical_id: 01KQTCB8K3TXN8SKQFJ7WZ7FJC
hubs:
- WikantikPlatformHub
---

# Wikantik Search and Retrieval

Wikantik features a high-precision, multi-stage retrieval pipeline designed to serve both humans and AI agents. It goes beyond simple keyword matching by fusing lexical, semantic, and relational data.

## The Retrieval Pipeline

When a query is submitted (via `/api/search` or the `retrieve_context` tool), it undergoes four distinct phases:

### 1. Lexical Retrieval (BM25)
The first stage uses **Apache Lucene** to perform a classic BM25 search. 
- **Strength:** Excellent at finding exact matches, technical terms, and unique identifiers.
- **Scope:** Indexes page titles, content body, and frontmatter keywords.

### 2. Dense Retrieval (pgvector)
In parallel, the query is converted into a high-dimensional vector using an external embedding model (e.g., `nomic-embed-text` via Ollama).
- **Search:** A K-Nearest Neighbor (KNN) search is performed against the `content_chunk_embeddings` table in PostgreSQL.
- **Strength:** Captures semantic meaning and intent, finding relevant content even when keywords don't match exactly.

### 3. Hybrid Fusion (RRF)
The results from BM25 and Dense retrieval are combined using **Reciprocal Rank Fusion (RRF)**.
- **Goal:** Balance the precision of lexical search with the recall of semantic search.
- **Config:** Tunable via `wikantik.search.rrf.k` (default: 60) in `wikantik-custom.properties`.

### 4. Knowledge Graph Reranking
The final, and most unique, stage is the **KG-Aware Reranker** (`GraphRerankStep`). It is a pure reordering — never adds or removes pages — and degrades closed to the fused order on any signal failure (`!enabled`, neighbor index not ready, no query entities resolved, no mentions loaded).

- **Query-Entity Resolution:** `QueryEntityResolver` matches query terms to canonical KG entity names → a set of seed entity UUIDs.
- **Page Mentions:** `PageMentionsLoader` fetches, for each fused candidate, the set of entity UUIDs the page mentions (via `chunk_entity_mentions`).
- **Graph Traversal:** `GraphProximityScorer` runs a multi-source BFS from the query entities through the **undirected co-mention neighborhood** (`InMemoryGraphNeighborIndex`, an in-memory adjacency built from `kg_edges`). Reachable entities get distance score `1 / (1 + hops)`, capped at `maxHops`.
- **Per-Page Score:** `max` proximity over the page's mentioned entities — one strong mention is enough; the page does not have to mention every query entity.
- **Final Order:** `base_rank + boost × proximity`, stable-sorted. Pages with no proximity signal keep their fused-order position. Boost is configurable via `GraphRerankConfig`.
- **Outcome:** Highly relevant documentation clusters are surfaced together.

**Two important nuances about what the reranker uses today:**

- **Edges are treated as untyped.** The scorer reads only `(source_id, target_id)` from `kg_edges` and builds an undirected adjacency map. The `relationship_type` column is loaded into the schema but ignored at rerank time, so a `part-of` edge contributes the same single hop as a generic co-mention. Earlier versions of this page implied typed-relation weighting; that remains aspirational.
- **Edge provenance does not affect scoring.** `kg_edges.provenance` distinguishes `human-authored` from auto-extracted edges in the schema, but the rerank loader drops the column. A human-approved edge therefore carries the same weight as a machine-extracted one. Per-provenance weighting (e.g., 1.0 for human-authored, 0.5 for auto-extracted) is a plausible future tuning — confined to the loader SQL plus a weighted-BFS variant in the scorer.

**Edge inclusion policy.** The loader's only filter is `KgInclusionFilter.EDGE_FILTER_*` — edges whose source page is on the `kg_excluded_pages` list are dropped. (Asymmetric on purpose: edges pointing *into* an excluded page still appear in the adjacency, but the excluded page's outgoing edges stay hidden.) This is page-level opt-out, not an approval gate.

**Operational guard.** When `kg_edges` exceeds `maxEdges` (configurable cap on `InMemoryGraphNeighborIndex`), the snapshot empties and `isReady()` returns `false`, which makes `GraphRerankStep` no-op. The cap exists so large graphs don't silently blow the latency budget; raising it is an explicit operator decision.

## The Embedding Infrastructure

Wikantik's dense search depends on a chunk-and-embed pipeline:
1. **Chunking:** Pages are split into logical chunks (e.g., by headings) during the save process.
2. **Embedding:** Chunks are sent to the `EmbeddingClient` which communicates with an external service (Ollama, OpenAI, or TEI).
3. **Storage:** Vectors are stored in the `embeddings` table with an HNSW index for fast retrieval.

## Evaluation and Refinement

The `wikantik-tools` module includes a standalone `search-eval` utility.
- **Workflow:** Runs a set of "ideal" queries against the running wiki.
- **Metrics:** Reports Recall@K, MRR, and Precision.
- **Baseline:** Stored in `eval/retrieval-queries.csv` to ensure search quality never regresses.

## Strengths in Search

- **Agent Optimization:** The pipeline is specifically tuned to provide high-density context for AI agents.
- **Fallback Resilience:** If the embedding service is down, the system gracefully falls back to pure BM25.
- **Relational Awareness:** The KG rerank ensures that related concepts (like a Hub and its children) appear together. The proximity is computed over an undirected co-mention neighborhood, so a hub and its members surface together as long as they share entity mentions in the graph — no typed-relation curation needed at the page level.
