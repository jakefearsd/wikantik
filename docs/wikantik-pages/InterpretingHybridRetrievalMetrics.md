---
title: Interpreting Hybrid Retrieval Metrics
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ427DYFF50310Q971E5GB5C
audience:
- humans
runbook:
  when_to_use:
  - You're investigating whether retrieval quality has regressed
  - You want to know whether the for-agent projections are landing under budget
  - You want to know whether the dense (embedding) side of hybrid retrieval is healthy
  - A user asks "is the wiki retrieval doing OK"
  inputs:
  - The metric scrape from /metrics (or a Grafana dashboard)
  - 'Optional: a baseline from a known-good period'
  steps:
  - Read /metrics and grep for `wikantik_` - the family is small enough to scan
  - For projection size, look at wikantik_for_agent_response_bytes_bucket - `_count`
    should be non-zero, p50 well under 4 KB, p99 under 8 KB
  - For structural index health, watch wikantik_structural_index_{pages_total,clusters_total,unclaimed_total,lag_seconds}
    - unclaimed should be close to zero post-backfill
  - For dense-side health, check wikantik_search_hybrid_embedder_circuit_state (0
    CLOSED, 1 HALF_OPEN, 2 OPEN), wikantik_search_hybrid_vector_index_size (0 means
    BM25-only) and wikantik_search_hybrid_bootstrap_state (5 FAILED)
  - For retrieval quality, wikantik_retrieval_ndcg_at_5{set,mode} is the headline
    gauge (NaN until a nightly or on-demand run has completed) - alert on an absolute
    drop of more than 5 points against the previous run of the same set and mode
  - Cross-reference with /api/health/structural-index for a one-shot status check
  pitfalls:
  - Reading raw histogram buckets without the _count line - the buckets are cumulative
    and meaningless without the total
  - Comparing absolute numbers across deploys without checking the page-count gauge
    - a different corpus size shifts every metric
  - Confusing for-agent response size with retrieval quality - the histogram is about
    projection budget, not relevance
  - Alerting on a single threshold-cross - one low nightly point may be a degraded
    run (check the degraded flag); look at the trend
  - Treating a hybrid row that equals the bm25 row as healthy - it usually means the
    dense side is silent
  related_tools:
  - /api/health/structural-index
  - /admin/retrieval-quality
  - /api/bundle
  references:
  - HybridRetrieval
  - AgentGradeContentDesign
  - HandlingEmbeddingServiceOutages
  - RunningTheRetrievalQualityHarness
summary: 'What the `wikantik_` Prometheus gauges mean: structural index, projection
  size, dense-side health and retrieval quality, plus thresholds and pitfalls.'
tags:
- hybrid-search
- rrf
- bm25
- vector-search
- retrieval-metrics
- rag
- agent-cookbook
---
# Interpreting Hybrid Retrieval Metrics

Wikantik publishes a small set of Prometheus metrics covering structural index health, for-agent projection size, the dense side of hybrid retrieval, and retrieval quality. They are scraped at `/metrics` and all start with `wikantik_`.

## When to use this runbook

When you have a `/metrics` scrape and want to convert numbers into verdicts: has retrieval quality regressed, are for-agent projections landing under budget, is the dense (embedding) side healthy?

## Context

- **Structural index (shipped):** `wikantik_structural_index_{pages_total,clusters_total,tags_total,unclaimed_total,lag_seconds}` — gauges. Plus `wikantik_structural_index_rebuild_duration_seconds` (timer) for rebuild cost. `GET /api/health/structural-index` gives a one-shot status.
- **For-agent projection (shipped):** `wikantik_for_agent_response_bytes` — DistributionSummary with percentile histogram (`_bucket`, `_count`, `_sum`). Records every projection's serialised size against the 4 KB / 8 KB budget.
- **Query embedder and dense index (shipped):** `wikantik_search_hybrid_embedder_{calls_total{result},cache_total{result},breaker_transitions_total{to},breaker_rejected_total,circuit_state,latency_seconds}`, `wikantik_search_hybrid_vector_index_size`, `wikantik_search_hybrid_vector_index_last_rebuild_millis` (`lucene-hnsw` only) and `wikantik_search_hybrid_bootstrap_{state,chunks_total}`. A `circuit_state` of 2 (OPEN), a vector-index size of 0, or a `bootstrap_state` of 5 (FAILED) all mean the dense side is silent and retrieval is BM25-only.
- **Retrieval quality (shipped, Phase 5):** `wikantik_retrieval_{ndcg_at_5,ndcg_at_10,recall_at_20,mrr}{set,mode}` — gauges, plus `wikantik_retrieval_run_duration_seconds` (timer) and `wikantik_retrieval_run_failed_total` (counter). They are `NaN` until a nightly or on-demand run has completed — see [RunningTheRetrievalQualityHarness](RunningTheRetrievalQualityHarness).

## Walkthrough

The frontmatter `steps` walk the metric set in priority order: index health first (the foundation), projection size second (the agent contract), dense-side health third, retrieval quality last (the headline signal). For quality, `wikantik_retrieval_ndcg_at_5{set="core-agent-queries",mode="hybrid"}` is the headline gauge. The design's alert is an absolute drop of more than 5 points against the previous run of the same `(set, mode)`; `docs/admin/RetrievalQuality.md` also shows a floor rule (`< 0.80`) you can calibrate to your corpus.

To tell *which* retriever is at fault, compare the arms of `GET /api/bundle?q=…`: `mode=lexical` ranks by BM25 alone, `mode=dense` by vectors alone, and the default `hybrid` fuses them with weighted Reciprocal Rank Fusion. `debug=rankings` returns the raw dense and BM25 chunk rankings (ids and scores) when `wikantik.bundle.bm25.enabled` is on. BM25 wins on exact identifiers, class names and error codes; dense wins on paraphrase and synonym drift. Page-level search fuses with $k = 60$ and BM25 and dense weights of 1.5 each (`wikantik.search.hybrid.rrf.*`); the bundle uses its own `wikantik.bundle.bm25.*` weights — see [HybridRetrieval](HybridRetrieval). A `hybrid` row that matches the `bm25` row in the nightly runner, or a bundle coverage block with `topSimilarity: -1.0` and `confidence: unknown`, means the dense side contributed nothing — see [HandlingEmbeddingServiceOutages](HandlingEmbeddingServiceOutages).

## Pitfalls

The frontmatter `pitfalls` capture the recurring misreads. The "comparing absolute numbers across deploys" trap is especially common — agents take a snapshot of metrics, deploy a different corpus, then report that "retrieval got worse" when the page count just shifted.
