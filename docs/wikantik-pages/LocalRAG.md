---
title: Local Rag
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- rag
- local-llm
- privacy
- self-hosted
- embedding
summary: Building a fully local RAG pipeline — open embedding model + vector
  store + open LLM, all on your own hardware. The hardware requirements,
  software stack, and quality / cost trade-offs.
related:
- RagImplementationPatterns
- OpenSourceLLMs
- VectorDatabases
- HybridRetrieval
- RunningLocalLlms
hubs:
- AgenticAi Hub
---
# Local RAG

A fully local RAG (Retrieval-Augmented Generation) pipeline does the embedding, the indexing, and the generation on hardware you control. No data leaves your machines. Useful for privacy-sensitive workloads, on-prem deployments, edge / offline scenarios, or just learning the mechanics without API bills.

In 2026, this is a practical option. The quality is competitive (not frontier) and the operational footprint is manageable.

## What "local" buys you

- **No data egress.** Documents stay on your hardware; queries don't leave.
- **Cost predictability.** Pay for hardware once; no per-token charges.
- **Offline capability.** Works without internet.
- **No vendor lock-in.** Swap any component independently.
- **Compliance.** Easier data-residency story; no third-party processor.

What it doesn't buy:

- **Frontier quality.** Open LLMs trail commercial frontiers by 6-12 months on hard tasks.
- **Zero ops.** Self-hosted means real ops work.
- **Burst capacity.** Capacity is bounded by your hardware.

## The minimum stack

Three components:

1. **Embedding model** — locally hosted; embeds queries and document chunks.
2. **Vector store** — local index of chunk embeddings.
3. **LLM** — locally hosted; generates the answer from retrieved chunks.

Plus retrieval orchestration (the glue), chunking pipeline, optional reranker.

## Component choices

### Embedding model

Options (all local-friendly):

- **BGE-small / BGE-base / BGE-large** (BAAI) — strong open embedding family. BGE-large is competitive with commercial.
- **e5-base / e5-large** (Microsoft) — strong; well-supported.
- **gte-large / gte-multilingual** — solid, multilingual variants.
- **Nomic-embed-v1.5 / v2** — Apache 2.0; long-context.
- **all-MiniLM-L6-v2** — small (60MB); fast; weaker but fine for small corpora.
- **Jina v3** — multilingual, multi-modal capable.

For most use cases: BGE-base or BGE-large is the safe default. Run via `sentence-transformers` library. CPU-friendly.

### Vector store

- **pgvector** — Postgres extension. Reuses your existing Postgres if you have one. Sub-millisecond at tens of millions of vectors.
- **Qdrant** — single binary, Rust-based, strong filtering.
- **LanceDB** — embedded; columnar; cosy with Python data workflows.
- **ChromaDB** — Python-native; minimal setup.
- **Weaviate** — fuller-featured; can run locally.
- **FAISS** — bare-bones; the index, no server. Good for embedded use.
- **Just numpy** — for under 100k vectors, in-memory cosine search is fine.

For most local deployments: pgvector or Qdrant.

### LLM

See [OpenSourceLLMs] for the full landscape. For RAG specifically:

- **Smaller models often suffice.** RAG provides the knowledge; the LLM just needs to read and reason over the retrieved context. A 7B-13B model with strong context handling works.
- **Strong long-context** matters. Models that handle 32k-128k tokens process larger retrieved contexts.

Options:

- **Llama 3.1 8B** — strong; well-supported.
- **Mistral Small** (24B) — good quality / cost trade.
- **Qwen 2.5 7B / 14B** — strong on reasoning; good for code.
- **Phi-3.5-medium** — small, capable; runs on modest hardware.

For laptop deployment: 7B at int4 quantisation. For workstation: 13B-30B. For dedicated GPU: anything up to 70B int4 fits on an H100.

### Reranker

- **bge-reranker-base / large** — strong open reranker.
- **`ms-marco-MiniLM-L-12-v2`** — fast, decent.
- Skip the reranker if you can't afford the latency; it's optional.

## Hardware sizing

### Laptop (16-32 GB RAM, modest GPU or none)

- Embedding: BGE-small on CPU. A few embeddings per second.
- Vector store: ChromaDB or pgvector with a few thousand to ~1M docs.
- LLM: 7B int4 via llama.cpp. ~5 tok/s on CPU; ~30 tok/s on Apple Silicon M-series.

Suitable for: personal knowledge base; offline assistant; experimentation.

### Workstation (64 GB RAM, RTX 4090 or equivalent)

- Embedding: BGE-large on GPU. Hundreds per second.
- Vector store: pgvector or Qdrant; tens of millions of docs.
- LLM: 13B-30B at int4 via vLLM or llama.cpp. 30-100 tok/s.

Suitable for: small team's internal RAG; production for low-volume use.

### Dedicated server (A100/H100, 128+ GB system RAM)

- Embedding: any model at speed.
- Vector store: 100M+ docs.
- LLM: 70B at int4 via vLLM. 50+ tok/s; can serve concurrent requests.

Suitable for: production workloads, hundreds of users.

## A concrete recipe

For a "chat with your documents" application:

```
1. Ingestion
   - Parse documents (text, PDF, HTML)
   - Chunk: 256-512 tokens with 10-20% overlap, on semantic boundaries
   - Embed each chunk via BGE-large
   - Insert into pgvector with metadata (document_id, page, etc.)

2. Indexing
   - HNSW index on embedding column
   - Optional: tsvector for BM25
   - GIN index on metadata for filters

3. Retrieval
   - Query → embed via BGE-large
   - Top-50 by cosine
   - Optional: BM25 top-50; RRF combine
   - Optional: rerank top-50 → top-10 via BGE-reranker
   - Select top 5-10 chunks to feed LLM

4. Generation
   - System prompt + retrieved chunks + user query
   - LLM (Qwen 2.5 14B or similar) at int4 via vLLM
   - Stream response

5. Citation
   - Track which chunks were retrieved
   - LLM cites by source label
   - UI shows expandable citations
```

This is the standard pattern. A weekend's work to a functional prototype; weeks to a polished product.

## Where local RAG falls short

- **Frontier reasoning quality.** Hard multi-step reasoning still favours commercial frontier models.
- **Multilingual breadth.** Best multilingual coverage is in commercial models; some open ones are catching up.
- **Multimodal complexity.** Vision-language local RAG works but trails commercial.
- **Operational maturity.** Self-hosted serving has more pitfalls than calling an API.

For most internal-knowledge / customer-support / document-search use cases, local RAG is competitive. For a frontier-quality consumer assistant, commercial models still win.

## Failure modes specific to local

- **Embedding model swap breaks the index.** Embeddings from BGE-base aren't comparable to embeddings from e5. Pin the model version; reindex on changes.
- **Too-aggressive quantisation hurts retrieval more than generation.** Embedding models are smaller; quantisation hurts proportionally more. Stick to FP16 or BF16 for embeddings.
- **LLM context overflow.** Pretty 7B model has 32k context; you stuff 30 chunks in; it returns garbled output. Cap retrieved chunks at 5-10; aggressive chunk-summarisation if needed.
- **Stale models.** Open-weights move fast; the model you picked 6 months ago is now mid-tier. Periodic re-evaluation.
- **No prompt cache.** Local serving stacks have prompt caching but it's less mature than commercial. Plan for re-processing repeated prompts.

## Pragmatic configuration

For a team starting in 2026:

```yaml
embedder: BAAI/bge-large-en-v1.5
embed_dim: 1024
chunk_size: 384 tokens
chunk_overlap: 64 tokens
vector_store: pgvector with HNSW (m=16, ef_construction=200)
hybrid: true (BM25 via paradedb or tsvector + RRF)
reranker: BAAI/bge-reranker-large (top-50 → top-10)
llm: Qwen2.5-14B-Instruct at int4 via vLLM
serving: vLLM on a single H100; or llama.cpp on Apple M-series workstation
context: top 5 chunks + system prompt + query, ~3-4k tokens
```

This stack runs comfortably on a single workstation; serves a small team.

## Further reading

- [RagImplementationPatterns] — RAG patterns generally
- [OpenSourceLLMs] — picking the LLM
- [VectorDatabases] — substrate detail
- [HybridRetrieval] — the fusion step
- [RunningLocalLlms] — LLM-specific hosting
