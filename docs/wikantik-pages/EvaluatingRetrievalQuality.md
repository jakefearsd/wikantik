---
kg_include: true
date: '2026-05-15'
cluster: wikantik-development
title: 'Evaluating Retrieval Quality: MRR, NDCG, and Hit Rate in Hybrid Search Systems'
tags:
- retrieval-evaluation
- mrr
- ndcg
- search-quality
- rag
- hybrid-search
- wikantik-development
summary: 'How Wikantik measures retrieval quality: Recall@K, MRR and nDCG over curated
  query sets, the nightly runner, bin/search-eval, and fusion tuning.'
audience:
- humans
canonical_id: 01KRQJ720J8NH33YNC8E83PE8H
status: active
type: article
---
# Evaluating Retrieval Quality: MRR, NDCG, and Hit Rate in Hybrid Search Systems

In Retrieval-Augmented Generation (RAG) and hybrid search knowledge platforms, optimizing search relevance requires moving beyond subjective "vibes" to rigorous, quantifiable Information Retrieval (IR) evaluation metrics.

This guide details the mathematical definitions of Mean Reciprocal Rank (MRR), Normalized Discounted Cumulative Gain (NDCG@K) and Hit Rate@K, and then shows how Wikantik's own regression harnesses apply them.

---

## 1. Quick-Reference: Key IR Evaluation Metrics

```
+-----------------------------------------------------------------------------------------+
|                               INFORMATION RETRIEVAL EVALUATION METRICS                  |
+-----------------------------------------------------------------------------------------+
| Metric                 | Mathematical Focus                | Common rule of thumb       |
+------------------------+-----------------------------------+----------------------------+
| Mean Reciprocal Rank   | Inverse rank of 1st relevant item | MRR > 0.85 (High Precision)|
| (MRR)                  | $\text{MRR} = \frac{1}{|Q|} \sum \frac{1}{\text{rank}_i}$ | |
| Hit Rate @ K           | % of queries finding target in top K| Hit@5 > 95%               |
| NDCG @ K               | Graded relevance with log discount| NDCG@10 > 0.80             |
+-----------------------------------------------------------------------------------------+
```

The rule-of-thumb column is general IR guidance, not a Wikantik service-level target. With binary relevance, as Wikantik uses, $\text{DCG@}K = \sum_{i=1}^{K} \frac{\text{rel}_i}{\log_2(i+1)}$ and nDCG divides it by the best achievable DCG, so a perfect ordering scores 1.0. Hit Rate@K is the share of queries with a relevant result in the top K; when each query has exactly one ideal page it equals Recall@K.

---

## 2. What Wikantik measures

| Measurement | Runs | Query set | Metrics |
|-------------|------|-----------|---------|
| Nightly runner (`DefaultRetrievalQualityRunner`) | 03:00 UTC and on demand | `core-agent-queries`: 16 curated queries whose `expected_ids` are page `canonical_id`s, scored in `bm25` and `hybrid` modes | nDCG@5, nDCG@10, Recall@20, MRR — stored in `retrieval_runs`, published as `wikantik_retrieval_*` gauges, shown at `/admin/retrieval-quality` |
| `bin/search-eval` | on demand, against any wiki | `eval/retrieval-queries.csv`: 40 `(query, ideal_page, category)` rows scored through `GET /api/search` | Recall@5, Recall@20, MRR, overall and per category |
| Bundle section-recall corpus | `bin/eval/run-baseline.py`; a scheduler when `wikantik.bundle.eval.interval.hours` is above 0 | `eval/bundle-corpus/queries.csv` | Section recall@12 and precision, with per-category floors in `thresholds.properties` |
| Offline experiments | on demand (test-scope classes) | `eval/retrieval-queries.csv` | Recall@5, Recall@20, MRR for BM25, dense and hybrid across embedding models |

For scale: the 2.4.23 fusion fix (page aggregation `mean_top_3` and a BM25 weight of 1.5) moved hybrid nDCG@5 on `core-agent-queries` from 0.4528 to 0.9297 — the kind of change these numbers exist to catch. See [HybridRetrieval](HybridRetrieval).

---

## 3. Building a "Gold Set"

A retrieval evaluation set consists of triples `(query, ideal_page, category)` — `eval/retrieval-queries.csv` — or, in the database-backed sets, `(query, expected canonical_ids)`. The categories used in the CSV are `specific`, `synonym-drift`, `indirect`, `multi-word-concept`, `general`, `business-process` and `hard`:

*   **Specific**: "Ollama installation" $\rightarrow$ `OllamaSetup` (tests lexical accuracy).
*   **Synonym drift**: "running Wikantik in a container" $\rightarrow$ `WikantikOnDocker` (tests semantic/vector accuracy).
*   **Indirect**: "how do I deploy the wiki locally" $\rightarrow$ `BuildingAndDeployingLocally` (tests conceptual mapping).

Real traffic helps: Wikantik logs the text of every retrieval query (`wikantik.querylog.enabled`), and the admin MCP tool `list_retrieval_queries` exposes it for curation. Promoting a query into a set, with its ground truth, is a manual step. Keep `expected_ids` current — a deleted page makes its queries get skipped.

---

## 4. The Continuous Evaluation Loop

Search evaluation should be a habit, not a one-off:
1.  **Baseline**: Read the latest nightly values (or run the set on demand) before you change anything.
2.  **Experiment**: Modify a weight, a chunker setting, or add a reranking step.
3.  **Validate**: Run the evaluation again. If nDCG@5 or MRR drops, the "improvement" is a regression.

The nightly runner supplies the continuous signal; for a retrieval-touching change run it on demand before and after — see [Running the Retrieval Quality Harness](RunningTheRetrievalQualityHarness).

---

## 5. Tuning the fusion

The RRF constant, the BM25 and dense weights, the fused window and the page-aggregation strategy are configuration (`wikantik.search.hybrid.*`; the context bundle has its own `wikantik.bundle.bm25.*` set). Sweeps live in the offline harness (`ExperimentRrfSweep`, `ExperimentAggSweep` — see [Retrieval Experiment Harness](RetrievalExperimentHarness)) and, for the bundle's chunk-level fusion, `bin/eval/sweep-bm25-fusion.py`.

---
**External Deep Dive:**
- [Evaluation Measures for IR (Wikipedia)](https://en.wikipedia.org/wiki/Evaluation_measures_(information_retrieval)) — Deep dive into MAP and NDCG.
- [Mean Reciprocal Rank (Wikipedia)](https://en.wikipedia.org/wiki/Mean_reciprocal_rank) — Formal properties of MRR.

**See Also:**
- [Wikantik Search Refinement](WikantikSearchRefinement) — How to run the `search-eval` tool.
- [Industrial Search Systems](IndustrialSearchSystems) — The underlying architecture.

---

**Cluster Hub:** [[WikantikDevelopmentHub|Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent Briefings]] (`wikantik-development`)
