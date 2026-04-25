---
canonical_id: 01KQ12YDRX520ZN5PC6N2E5YG8
title: Ai Powered Search
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- search
- semantic-search
- hybrid-retrieval
- query-rewriting
- rerank
summary: Semantic, hybrid, and agentic search beyond keyword matching — query
  rewriting, dense retrieval, rerank, and the patterns that make a search bar
  feel modern.
related:
- HybridRetrieval
- VectorDatabases
- RagImplementationPatterns
- EmbeddingsVectorDB
- KnowledgeGraphCompletion
hubs:
- AgenticAi Hub
---
# AI-Powered Search

Search has been "good enough" for two decades because keyword inverted-index systems (Lucene, Elasticsearch, Postgres `tsvector`) are fast, predictable, and explainable. AI doesn't replace any of that — it adds two new capabilities: matching on meaning instead of words, and asking clarifying questions when the query is ambiguous.

Used together with classical search, these turn a search box from "exact-match retrieval" into "find what the user actually wanted." Used naively, they become a slower keyword search with worse explainability. The difference is design.

## What "AI-powered" actually means

Three distinct upgrades, often deployed together:

1. **Dense retrieval** — embed query and documents, retrieve by vector similarity. Catches paraphrases keyword search misses.
2. **Query understanding** — rewrite, decompose, or expand the query with an LLM before retrieval.
3. **Generative answer synthesis** — the search result is a synthesised answer with citations, not a list of links.

These can be deployed independently. A team can add #1 alone (just better retrieval), or jump straight to #3 (a chatbot over their docs). Most products converge on all three over time.

## The minimum modern stack

```
        ┌──────────────────────────────┐
query ─▶│    Query understanding       │
        │  (rewrite, decompose, expand)│
        └──────────┬───────────────────┘
                   │
        ┌──────────┴────────────┐
        ▼                       ▼
  ┌──────────┐           ┌─────────────┐
  │ BM25     │           │ Dense       │
  │ search   │           │ retrieval   │
  │ (Lucene) │           │ (HNSW)      │
  └────┬─────┘           └──────┬──────┘
       │                        │
       └────────────┬───────────┘
                    ▼
            ┌──────────────┐
            │ RRF fusion   │
            └──────┬───────┘
                   ▼
            ┌──────────────┐
            │  Rerank      │
            │ (cross-enc)  │
            └──────┬───────┘
                   ▼
            ┌──────────────┐
            │ Answer synth │
            │ + citations  │
            └──────────────┘
```

Each stage is independently swappable. This pipeline is what most production "AI search" looks like under the hood; calling it AI search overstates the AI's role and understates how much classical IR is still doing the work.

See [HybridRetrieval] for this wiki's own implementation.

## Stage by stage

### Query understanding

The query the user typed is rarely the right query for retrieval. Three useful transformations:

- **Rewrite** — fix typos, normalise casing, expand abbreviations. Cheap; can be done with rules or a tiny LLM.
- **Decompose** — split "compare X and Y on metric Z" into three sub-queries. Necessary for multi-entity or multi-aspect queries.
- **Expand (HyDE)** — generate a hypothetical answer with an LLM, embed *that* for retrieval. Helps when the query is short ("k8s networking issues" → a paragraph about typical k8s networking failures, embedded for vector search).

Don't apply all three. Each adds latency. Profile which transformation actually improves retrieval recall on your eval set; keep that one.

### Hybrid retrieval

BM25 and dense retrieval miss different things. BM25 misses paraphrases ("car" vs "automobile"). Dense retrieval misses exact strings ("error code 451"). Run both, fuse with reciprocal rank fusion (RRF) or learned-to-rank.

RRF in five lines:

```python
def rrf(*ranked_lists, k=60):
    scores = {}
    for hits in ranked_lists:
        for rank, hit in enumerate(hits):
            scores[hit.id] = scores.get(hit.id, 0) + 1 / (k + rank + 1)
    return sorted(scores.items(), key=lambda x: -x[1])
```

This single change typically adds 5–15 points of retrieval recall. The most reliable upgrade in the entire stack.

### Reranking

Top 50 from retrieval is too noisy to feed directly to an LLM. A cross-encoder reranker (Cohere Rerank, BGE-reranker-large, or `ms-marco-MiniLM-L-12-v2`) reranks the candidates by query-document relevance and you keep the top 5–10.

- 50ms latency overhead on GPU; less on CPU for small candidate sets.
- 3–8 points of nDCG on top of hybrid retrieval.
- Skip for sub-100ms latency budgets; add anywhere with a human in the loop.

### Answer synthesis

The "AI search experience" — generative answer with citations — is RAG ([RagImplementationPatterns]). The search-specific concerns:

- Cite sources prominently. The user trusts cited answers more, which is correct because they can verify.
- Show snippets, not just links. The snippet should be the relevant span the model used.
- Provide a "show all sources" or "show traditional results" affordance. Sometimes users want the list of links, not the summary.

## Patterns that improve perceived quality

**Did-you-mean / typo correction** — classical, still useful. Pre-LLM or with a tiny model.

**Faceted filtering** — let users filter by metadata after search. AI search degrades without this; users feel like they lost control.

**Query suggestions / autocomplete** — show popular queries as user types. Reduces ambiguity by directing toward known-good queries.

**Re-search with feedback** — when no results match, ask the user to clarify. "Did you mean documentation, troubleshooting, or pricing?" beats an empty results page.

**Recent searches and saved searches** — personalisation that's easy to implement and hard to do badly.

## Common failure modes

**Pure dense retrieval breaks on rare exact terms.** Product SKUs, error codes, names with unusual spelling. BM25 catches these; pure-dense systems miss. Always include BM25 in the fusion.

**Embedding drift.** You silently swapped the embedding model. Old vectors are now noise relative to new queries. Pin embedding model versions in deployment config; reindex on planned upgrades.

**Stale index.** New content takes 24h to be searchable because indexing is batch. Users don't notice for days. Make indexing latency a tracked metric, alert above thresholds.

**Filter blindness.** User filtered by date range; search ignored the filter. Pre-filter when filters are selective; verify the filter is actually applied at every layer.

**Ranking opaqueness.** Users can't understand why result A came before B. AI search is harder to debug than keyword search. Build an internal "explain" tool — for a given query and result, show which retriever surfaced it, what its similarity score was, what the reranker did.

## Building vs buying

| Need | Buy | Build | Hybrid |
|---|---|---|---|
| Generic web search inside an app | Algolia, Vespa Cloud, Elastic | — | — |
| Internal knowledge search | Glean, Coveo, Pinecone Inference | Postgres + pgvector + BM25 | Use Elastic + your own LLM layer |
| Customer-facing AI assistant | Cohere, Voyage RAG | Hybrid retrieval + open LLM | Most production systems |
| Domain-specific (legal, medical) | Specialist vendors | Custom embedding fine-tune + curated retrieval | The interesting middle |

The "build" column is increasingly viable in 2026. Open-source embedding models (BGE-M3, Voyage open variants), Postgres-native vector search, and self-hostable rerankers cover most needs. Pick "buy" only if speed-to-market dominates control.

## Measurement

Your search eval set has 100–500 query-document relevance labels. Track:

- **Recall@k** — did the right document surface in top-k?
- **nDCG@10** — is the ranking sensible?
- **MRR** — is the right answer near the top?
- **Click-through and dwell time** — production proxies for relevance once you have traffic.
- **Zero-result rate** — queries that return nothing. High = retrieval gaps; investigate.

Run weekly on your eval set; deploy changes only when metrics improve. See [HybridRetrieval] for the full eval pattern.

## Further reading

- [HybridRetrieval] — fusion architecture details, with this wiki as the case study
- [VectorDatabases] — substrate choices
- [RagImplementationPatterns] — answer synthesis patterns
- [EmbeddingsVectorDB] — embedding model selection
- [KnowledgeGraphCompletion] — when graph traversal beats vector search
