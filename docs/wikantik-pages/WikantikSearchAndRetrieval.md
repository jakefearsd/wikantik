---
canonical_id: 01KQTCB8K3TXN8SKQFJ7WZ7FJC
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
The final, and most unique, stage is the **KG-Aware Reranker**.
- **Seed Discovery:** Identifies "seed nodes" in the top-N results.
- **Graph Traversal:** Uses the [[KnowledgeGraphService]] to find co-mentioned neighbors and high-confidence relationships (`part-of`, `implements`).
- **Boost:** Pages that share strong semantic links with other high-ranking results receive a contextual boost.
- **Outcome:** Highly relevant documentation clusters are surfaced together.

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
- **Relational Awareness:** The KG rerank ensures that related concepts (like a Hub and its children) appear together.
