---
canonical_id: 01KQ12YDWB16GH9VTKNN1WKAB1
title: Rag Implementation Patterns
type: article
cluster: agentic-ai
status: active
date: '2026-04-24'
tags:
- rag
- retrieval-augmented-generation
- hybrid-retrieval
- chunking
- rerank
summary: Production RAG beyond "embed, retrieve, prompt" — chunking strategies,
  hybrid retrieval, reranking, and the moves that turn an 80% RAG demo into a
  95% production system.
related:
- HybridRetrieval
- VectorDatabases
- EmbeddingsVectorDB
- AiPoweredSearch
- LLMFineTuning
- AgenticWorkflowDesign
- KnowledgeGraphCompletion
hubs:
- AgenticAi Hub
---
# RAG Implementation Patterns

Naive RAG is four lines of code: embed the query, top-k nearest neighbours, stuff them in the prompt, generate. It usually scores around 60–75% on a task-specific eval — good enough for a demo, nowhere near good enough for production.

Getting from 75 to 95 is the whole discipline. This page is that delta, in concrete steps, ranked by how much return each move produces.

## The biggest single lever: hybrid retrieval

If your RAG is dense-only (pure vector search), adding BM25 keyword search and fusing with reciprocal rank fusion typically adds 5–15 points of recall at near-zero latency cost. Dense embeddings miss exact-string queries ("error 451", "SKU ABC-1234"); BM25 misses on semantic paraphrase. The fusion gets both.

Minimum RRF:

```python
def rrf(dense_hits, bm25_hits, k=60):
    scores = {}
    for rank, d in enumerate(dense_hits):
        scores[d.id] = scores.get(d.id, 0) + 1 / (k + rank + 1)
    for rank, d in enumerate(bm25_hits):
        scores[d.id] = scores.get(d.id, 0) + 1 / (k + rank + 1)
    return sorted(scores.items(), key=lambda x: -x[1])
```

See [HybridRetrieval] for the full ranking pipeline this wiki uses.

**Do this first.** Nothing else in this list beats the effort-to-payoff ratio of adding BM25.

## The second biggest: chunking strategy

"How do I chunk my documents" is the second question every RAG system gets wrong. The trap is either (a) fixed-size 500-token chunks that cut sentences in half, or (b) a section-header splitter that produces one 8000-token chunk and ten 50-token ones.

Working strategy:

1. **Split on semantic boundaries first** — headings, paragraph breaks, list items. Never mid-sentence.
2. **Size target: 256–512 tokens per chunk.** Smaller hurts context; larger dilutes embeddings.
3. **Overlap adjacent chunks by 10–20%.** One-sentence overlap usually suffices; prevents information loss at boundaries.
4. **Attach context metadata.** Every chunk carries `{document_id, section_path, preceding_heading}`. Your retriever uses the heading path for filtering and the context in the prompt.

For structured content (code, tables, JSON) stop before applying text chunking. Treat code blocks and tables as atomic units. A chunk that contains half a table is actively harmful — the model will hallucinate the missing rows.

## Rerank: the pattern most people skip

A cross-encoder reranker over the top-20 candidates typically adds another 3–8 points of nDCG. Cohere Rerank, BGE-reranker-large, or a locally hosted `ms-marco-MiniLM-L-12-v2` all work.

Shape:

```
query → retrieve top 50 (hybrid) → cross-encode rerank → top 5-10 to prompt
```

Cross-encoders are slow compared to ANN (50ms vs 2ms for 50 candidates on GPU) but the recall gain outweighs it for anything with a human in the loop. Skip reranking only for real-time < 100ms budgets where you'd rather take the recall hit.

## Query transformation

User queries are often bad retrieval queries. Three moves that help:

- **HyDE (Hypothetical Document Embeddings)** — LLM writes a hypothetical answer, you embed *that* and retrieve. Works well when the user's query is short or ambiguous; works badly when the LLM hallucinates technical details.
- **Query decomposition** — split "compare X and Y" into "tell me about X" + "tell me about Y" + final synthesis. Essential for multi-entity queries.
- **Multi-query expansion** — generate 3–5 paraphrases, retrieve for each, union the results. Adds recall at the cost of one small LLM call.

Use one of these, not all three. Stacking transformations adds latency faster than quality.

## Metadata filtering done right

Most RAG failures in production aren't retrieval failures, they're *context* failures — the right information is retrieved but it's for the wrong tenant, wrong time period, or wrong document version. Metadata filtering is the defence.

- Pre-filter (narrow candidates by metadata, then ANN) when filters are selective. "Documents for tenant X" cuts 99% of candidates.
- Post-filter (ANN first, then filter) when filters are broad. "Documents from last year" might match most of the corpus; pre-filter costs more than post.
- Your vector DB should support both modes and let you pick per-query. Qdrant does this cleanly; pgvector is weaker but good enough for most workloads.

## The prompt matters more than people admit

Once you have the right chunks, the prompt determines whether the model uses them. Three rules:

1. **Label the chunks explicitly.** `[Source: doc-423, section "Returns Policy"]` beats dumping raw text. The model cites correctly only if you tell it how.
2. **Instruct citation.** "Answer using only the provided sources. Cite each claim with the source label." Without this, the model will cheerfully answer from pretraining and give you a confidently wrong answer.
3. **Handle empty retrievals.** "If the sources do not contain the answer, reply 'I don't know.'" This one line eliminates a specific, high-confidence hallucination class.

## Evaluation: the thing most teams skip

You cannot improve what you don't measure. Minimum RAG eval:

| Metric | What it catches | Cost |
|---|---|---|
| **Retrieval recall@k** | Did the right chunk surface? | Free once you have labelled query→doc pairs |
| **nDCG@10** | Is the ranking sensible? | Same labels as recall |
| **Answer faithfulness** (RAGAS) | Does the answer stick to the retrieved context? | One LLM call per eval row |
| **Answer correctness** | Is the answer actually right? | Human label or LLM-as-judge |
| **Latency p95** | Does the pipeline fit your budget? | Free |

Build a frozen eval set of 100–500 queries with gold-labelled relevant chunks and expected answers. Run it on every change. This wiki's own retrieval is evaluated this way — see [HybridRetrieval] and [RetrievalExperimentHarness].

## RAG vs long-context models

"Just put the whole corpus in a 1M-token context" sounds like it replaces RAG. It doesn't, for three reasons:

1. **Cost.** 1M-token calls are expensive. RAG with 4k of retrieved context costs 0.4% of the same query against a full-corpus long-context call.
2. **Attention degrades with length.** Models retrieve from long contexts noticeably worse than from short, focused ones (the "lost in the middle" effect). Recall drops from 98% at 4k to 60–75% at 1M depending on the model.
3. **You can't update.** A long-context call re-ingests the whole corpus every time. RAG's retrieval index lets you update incrementally.

Long context is the right answer for some tasks (e.g. summarising a single 500-page contract). RAG is the right answer for retrieval over a knowledge base. They compose — use RAG to narrow to the relevant subset, then long-context for the deep analysis.

## Production debugging

When RAG quality regresses in prod, look in this order:

1. **Check retrieval first.** 90% of RAG regressions are retrieval regressions. Log top-k chunk IDs per query; diff against a known-good baseline.
2. **Check the index.** HNSW tombstone ratio high? Recall drops silently.
3. **Check embedding model version.** Silent model swaps on managed APIs change the embedding space — vectors indexed against v2 are noise for v3. Pin the model version in deployment config.
4. **Check chunking.** If you reindexed and recall dropped, the new chunking is probably worse than the old. A/B test chunking strategies — don't assume the new one is better.

Instrument each layer with a trace span so a single query shows retrieval, rerank, and generation cost/latency. Without this you'll spend hours reproducing intermittent issues by hand.

## Further reading

- [HybridRetrieval] — the fusion architecture this wiki runs on
- [VectorDatabases] — picking the substrate
- [EmbeddingsVectorDB] — embedding model choices
- [AiPoweredSearch] — query-side improvements beyond retrieval
- [KnowledgeGraphCompletion] — when graphs complement vector retrieval
- [LLMFineTuning] — when the generator needs training
- [AgenticWorkflowDesign] — agents that retrieve as part of larger loops
