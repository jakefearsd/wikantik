---
canonical_id: 01KQ12YDXBDYP70FJB6WPM01GX
title: Vector Databases
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- vector-database
- embedding
- ann
- hnsw
- retrieval
- pgvector
summary: Concrete comparison of pgvector, Qdrant, Pinecone, Weaviate, and Milvus for
  production embedding search — indexing algorithms, operational trade-offs, and
  when each is the right pick.
related:
- EmbeddingsVectorDB
- HybridRetrieval
- RagImplementationPatterns
- AiPoweredSearch
- KnowledgeGraphVsRelationalDatabase
hubs:
- AgenticAi Hub
---
# Vector Databases

A vector database is a thing that stores high-dimensional vectors and does approximate nearest-neighbour (ANN) search over them quickly. Everything else — the query language, the metadata filtering, the hybrid ranking — is a convenience layer built on that core. When you're picking one, evaluate the core first and the convenience layer second; teams get this backwards and regret it six months in.

## Do you actually need one?

Before reaching for a dedicated vector DB, check:

- **< 100k vectors?** A Python dict and `numpy.dot` in-process will serve you faster than any networked vector DB. Don't overbuild.
- **Already on Postgres?** Use `pgvector`. It handles tens of millions of vectors competently, you don't add an operational dependency, and your joins against structured data remain free.
- **Want hybrid retrieval (BM25 + dense)?** You need a system that can score on both. Most dedicated vector DBs are catching up here; Elasticsearch/OpenSearch were already there.

## The indexing algorithm matters more than the product

The dominant choice is **HNSW** (Hierarchical Navigable Small World graphs). Every serious vector DB ships it. The next tier is **IVF-PQ** (inverted file with product quantisation), which trades recall for drastically lower memory — essential above 100M vectors.

| Algorithm | Build time | Recall | QPS | Memory | Best for |
|---|---|---|---|---|---|
| **Flat (exact)** | 0 | 100% | Low | High | < 1M vectors or regulatory "no approximation" requirements |
| **HNSW** | Medium | 95–99% | High | High (1.5–2× raw vectors) | < 10M vectors, latency-critical |
| **IVF-PQ** | High | 85–95% | Very high | Low (10–20× compression) | > 10M vectors, memory-bound |
| **DiskANN** | High | 95–98% | Medium | Low (on-disk) | > 100M vectors on commodity hardware |

Tuning matters. HNSW with default `ef=40` recalls ~90%; `ef=200` recalls ~99% at 3–4× latency cost. If someone publishes a benchmark without specifying `ef`, ignore the benchmark.

## The current product landscape

| Product | Core algo | Operational footprint | Licensing | Pick when |
|---|---|---|---|---|
| **pgvector** | HNSW (0.5+), IVFFlat | Postgres extension | Open source (MIT) | You're already on Postgres and have < 50M vectors. Strongly recommended default |
| **Qdrant** | HNSW | Single binary or cluster, Rust | Apache 2.0 | You want pure vector performance, self-hosted, and don't need SQL joins |
| **Pinecone** | Proprietary (HNSW-ish) | Managed SaaS only | Proprietary | You'll pay to avoid operating anything; you want multi-region and p99 latency SLAs |
| **Weaviate** | HNSW | Self-hosted or cloud, Go | BSD-3 | You want built-in modular vectorisers, GraphQL, and multi-tenancy |
| **Milvus / Zilliz** | HNSW, IVF-PQ, DiskANN | K8s-heavy; Milvus Lite for dev | Apache 2.0 | Very large scale (> 100M vectors); DiskANN support is the differentiator |
| **Elasticsearch / OpenSearch** | HNSW | JVM cluster you probably already run | Elastic (or Apache 2) | Hybrid BM25 + dense is the real requirement; you already have the cluster |
| **LanceDB** | IVF-PQ | Embedded, columnar on parquet/Lance | Apache 2.0 | You want file-based storage, no server process, and a Python-native developer experience |
| **Redis VectorSearch** | HNSW | Redis module | Redis Source-Available | You want < 10ms p99 and already run Redis at scale |

**Strong opinion:** for most teams in 2026, the answer is pgvector or Qdrant. Pinecone is fine if you genuinely don't want to operate anything, but the cost curve past a few million vectors is harsh. Everything else is a niche answer to a specific constraint.

## Operational concerns people forget until production

**Index build time scales poorly.** HNSW insert is roughly O(log N); building from scratch on 10M vectors takes hours. Plan for incremental indexing from day one — most products support it, but default config often rebuilds the full index on a schema change.

**Updates and deletes are hard.** HNSW graphs don't truly delete; they tombstone. Recall degrades as your tombstone ratio grows. Periodic full reindexing is a real operational task. pgvector with IVFFlat is worse than HNSW here; IVFFlat bucket drift is severe after heavy updates.

**Metadata filtering costs a lot more than you'd guess.** Pre-filter (narrow candidate set with metadata, then ANN) is faster when the filter is selective; post-filter (ANN, then filter) is faster when it isn't. Products do one or the other; some let you pick per-query. Qdrant's pre-filter is the most mature. pgvector's filter push-down via the planner is weaker — if filter-heavy workloads dominate, benchmark before committing.

**Quantisation changes everything.** Binary quantisation (cosine similarity on 1-bit vectors) reduces memory 32× with typically < 2% recall loss. If your vectors are > 10M, enable it. Cohere's `embed-v3` and OpenAI's `text-embedding-3-large` were trained to work well with binary quantisation; older models degrade more.

**Embedding dimension trade-off.** 1536-dim (OpenAI v3) vs 768-dim (BGE-M3) vs 384-dim (MiniLM): a 4× dimension jump is a 4× memory and index-time cost. For most retrieval tasks the 768-dim options are near-tied with 1536-dim on recall. Pick 1536 only if a specific eval justifies it.

**Hybrid search is usually the right answer.** Dense alone misses on exact-term queries ("error code 42"); BM25 alone misses on semantic paraphrases. Reciprocal rank fusion (RRF) of the two is a 2-line change that typically adds 5–15 points of retrieval recall at nearly zero cost. See [HybridRetrieval].

## A minimum production stack

For a team starting from scratch:

```
  Postgres 16 + pgvector 0.7+
  ├── dense index: HNSW (m=16, ef_construction=200, ef_search=40)
  ├── metadata: GIN index on tag/author columns for fast pre-filter
  ├── BM25 via paradedb or tsvector + ts_rank_cd
  └── daily VACUUM ANALYZE, monthly REINDEX CONCURRENTLY on the HNSW index
```

This runs comfortably up to 20M chunks on a single well-sized node (e.g. 128 GB RAM, NVMe). You'll hit write-throughput limits before read-latency limits. When you outgrow it, Qdrant is the typical next step.

## The embedding model question

A vector DB is only as useful as the embeddings you put in it. As of 2026:

- **OpenAI `text-embedding-3-large`** — safe default, 3072-dim (truncatable to 1536 or 768 without retraining).
- **Cohere `embed-v3`** — stronger on multilingual and code, binary-quantisation-native.
- **BGE-M3** — open-weights option that's competitive with commercial APIs and you can self-host.
- **Voyage AI `voyage-3`** — generally strongest on retrieval benchmarks; paid API.

Don't mix embedding models in one index. You can have *multiple* indexes (per-domain models sometimes help) but never mix within an index — cosine similarity between vectors from different embedding spaces is meaningless.

## Evaluation, not benchmarking

Public benchmarks (MTEB, BEIR) tell you the average. Your traffic isn't the average. Build a retrieval eval set of 100–500 real queries from your application, label relevance manually, track nDCG@10 and recall@20 per-embedding-model and per-index-config. This is the only honest way to pick.

See [HybridRetrieval] for how this wiki actually evaluates its own retrieval pipeline end-to-end.

## Further reading

- [EmbeddingsVectorDB] — conceptual intro, pair this with that
- [HybridRetrieval] — the fusion step on top of vector search
- [RagImplementationPatterns] — what you do with the results
- [AiPoweredSearch] — agent-era query rewriting and rerank
- [KnowledgeGraphVsRelationalDatabase] — when vector isn't the right substrate
