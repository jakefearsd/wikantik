---
title: Running the Retrieval Quality Harness
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ42G7FNHJXBFF246W07JZ3K
audience:
- humans
runbook:
  when_to_use:
  - You are about to land a change that touches retrieval (chunker, embeddings, fusion
    weights, query parsing)
  - A user reports anecdotal quality regression and you want a quantitative read
  - You want to read or investigate the nightly retrieval-quality gauges
  inputs:
  - A query set id in retrieval_query_sets (the seeded one is core-agent-queries,
    16 queries whose expected_ids are canonical_ids)
  - The retrieval mode under test (bm25 or hybrid; hybrid_graph and hybrid_graph_weighted
    are retired - the graph rerank was removed in 2026-07, so they now run as hybrid)
  steps:
  - Open Admin -> Retrieval Quality (/admin/retrieval-quality) and note the latest
    values and sparklines for the (query set, mode) row you are testing
  - Click Run now on that row, or POST /admin/retrieval-quality/run with {"query_set_id":"core-agent-queries","mode":"hybrid"}
    - it is synchronous and returns ndcg_at_5, ndcg_at_10, recall_at_20, mrr, queries_evaluated,
    queries_skipped and degraded
  - Compare with the previous run of the same set and mode (GET /admin/retrieval-quality?query_set_id=core-agent-queries&mode=hybrid&limit=30);
    an absolute drop of more than 5 points in nDCG@5 is the design's alert threshold
  - Check degraded and queries_skipped before trusting the number
  - For a quick check against any wiki without admin access, run bin/search-eval (scores
    eval/retrieval-queries.csv through GET /api/search)
  - The same runner fires nightly at wikantik.retrieval.cron.hour_utc (03:00 UTC)
    when wikantik.retrieval.cron.enabled is true, writes retrieval_runs and updates
    the wikantik_retrieval_* gauges
  pitfalls:
  - Treating a degraded run as clean - the retriever threw for at least one query,
    so the aggregate covers only the rest
  - Stale expected_ids - they are canonical_ids, so deleted or replaced pages make
    queries_skipped climb and cause phantom regressions
  - Comparing across different query sets - every set has its own absolute baseline;
    compare the same (set, mode) over time
  - Using bm25-mode results to evaluate hybrid changes - the modes are independent
    measurements
  - Reading a hybrid row that equals the bm25 row as a good result - it usually means
    the embedding service is down and hybrid fell back to BM25
  - Forgetting to rebuild indexes and re-embed after a chunker or embedding config
    change - stale chunks give stale scores
  related_tools:
  - /admin/retrieval-quality
  - /admin/retrieval-quality/run
  - /api/search
  references:
  - RetrievalExperimentHarness
  - HybridRetrieval
  - AgentGradeContentDesign
  - InterpretingHybridRetrievalMetrics
  - EvaluatingRetrievalQuality
  - WikantikSearchRefinement
summary: 'How to run the retrieval-quality harness on demand or via the nightly scheduler:
  modes, nDCG/Recall/MRR output, the admin dashboard and gauges.'
tags:
- retrieval-quality
- test-harness
- benchmarks
- mrr
- search-evaluation
- agent-cookbook
---
# Running the Retrieval Quality Harness

Wikantik's retrieval-quality harness runs a curated query set against the live search stack, on demand and every night, so a retrieval change can be judged with numbers instead of a vibe check. The scheduled runner, the database-backed query-set store (`retrieval_query_sets` / `retrieval_queries` / `retrieval_runs`), the Prometheus gauges and the admin dashboard are Phase 5 of [AgentGradeContentDesign](AgentGradeContentDesign), and all of them have shipped.

## When to use this runbook

Before merging a retrieval-touching change (chunker, embeddings, fusion weights, query parsing), when investigating an anecdotal quality regression, or when you want to read the nightly quality gauges.

## Context

`DefaultRetrievalQualityRunner` (`wikantik-main`, package `com.wikantik.knowledge.eval`) is the harness. For one `(query set, mode)` pair it:

1. loads the set's queries from `retrieval_queries` (tables `retrieval_query_sets`, `retrieval_queries`, `retrieval_runs` come from migration V016);
2. runs each query through the live retrieval stack in the requested mode;
3. resolves the returned page names to `canonical_id`s and scores them against the query's `expected_ids` with binary relevance — **nDCG@5, nDCG@10, Recall@20 and MRR**;
4. averages across the set, writes a `retrieval_runs` row, and updates the `wikantik_retrieval_*` Prometheus gauges.

The seeded set is `core-agent-queries` (V017): 16 hand-curated questions, one per agent-cookbook runbook plus a cross-cluster query. Queries with empty `expected_ids` are skipped; a query whose retriever throws is skipped and marks the run `degraded`.

Modes (`com.wikantik.api.eval.RetrievalMode`):

| Mode | What it runs |
|------|--------------|
| `bm25` | Lucene BM25 only |
| `hybrid` | BM25 + dense, RRF-fused — the production default |
| `hybrid_graph`, `hybrid_graph_weighted` | **Retired.** The Knowledge Graph rerank was deleted in 2026-07; the names survive only so historical `retrieval_runs.mode` rows still parse, and a fresh run degrades to `hybrid` |

## Walkthrough

1. **Read the baseline.** Open Admin → Retrieval Quality (`/admin/retrieval-quality`): one row per `(query set, mode)` with the latest value and a sparkline per metric.
2. **Run now.** Click **Run now** on the row, or call `POST /admin/retrieval-quality/run` with `{"query_set_id":"core-agent-queries","mode":"hybrid"}` (admin session required). The call is synchronous and returns `ndcg_at_5`, `ndcg_at_10`, `recall_at_20`, `mrr`, `queries_evaluated`, `queries_skipped` and `degraded`.
3. **Compare with history.** `GET /admin/retrieval-quality?query_set_id=core-agent-queries&mode=hybrid&limit=30` returns recent runs. An absolute drop of more than 5 points in nDCG@5 against the previous run of the same set and mode is the design's alert threshold; the same figure is the `wikantik_retrieval_ndcg_at_5{set,mode}` gauge.
4. **Check the quality of the run itself** — `degraded` and `queries_skipped` — before trusting the number.
5. **Spot-check any wiki without admin access** with `bin/search-eval`, which scores `eval/retrieval-queries.csv` through `GET /api/search` (see [WikantikSearchRefinement](WikantikSearchRefinement)).

The same runner fires nightly at `wikantik.retrieval.cron.hour_utc` (default 03:00 UTC) for every non-retired mode when `wikantik.retrieval.cron.enabled` is true (the default); each failed `(set, mode)` pair is logged at WARN and counted in `wikantik_retrieval_run_failed_total`. Operator detail lives in `docs/admin/RetrievalQuality.md`.

Two sibling harnesses exist. The context bundle has a section-recall corpus (`eval/bundle-corpus/`, `bin/eval/run-baseline.py`, floors in `thresholds.properties`) that a scheduler can run when `wikantik.bundle.eval.interval.hours` is above 0 (default 0 = disabled). The offline embedding-model and fusion experiments are described on [RetrievalExperimentHarness](RetrievalExperimentHarness).

## Pitfalls

- **Treating a `degraded: true` run as clean.** The aggregate is computed over the queries that did not throw, so it can look better than reality.
- **Stale `expected_ids`.** They are `canonical_id`s; deleting or replacing a page makes `queries_skipped` climb and produces phantom regressions. Refresh the `retrieval_queries` rows.
- **Comparing across query sets.** Every set has its own absolute baseline; compare the same `(set, mode)` over time.
- **Using `bm25` results to judge a hybrid change.** The modes are independent measurements.
- **A "hybrid" row that equals the `bm25` row.** If the embedding service is down the dense side falls back to BM25 and the row quietly measures BM25 — see [HandlingEmbeddingServiceOutages](HandlingEmbeddingServiceOutages).
- **Skipping the rebuild after a chunker or embedding change.** Stale chunks give stale scores; rebuild the indexes and re-embed first (see [HybridRetrieval](HybridRetrieval)).
- **Reading one run as authoritative.** Watch the sparkline: a downward trend over several nights is real, a single low point may be a degraded run.
