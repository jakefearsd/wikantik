---
canonical_id: 01KQ427DYFF50310Q971E5GB5C
title: Interpreting Hybrid Retrieval Metrics
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: What the Wikantik hybrid-retrieval Prometheus gauges actually mean — `wikantik_for_agent_response_bytes` for projection size, `wikantik_structural_index_*` for the index health, and the planned `wikantik_retrieval_*` family from Phase 5 of the agent-grade design.
tags:
  - observability
  - metrics
  - retrieval
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You're investigating whether retrieval quality has regressed
    - You want to know whether the for-agent projections are landing under budget
    - A user asks "is the wiki retrieval doing OK"
  inputs:
    - The metric scrape from /metrics (or a Grafana dashboard)
    - Optional: a baseline from a known-good period
  steps:
    - Read /metrics and grep for `wikantik_` — the family is small enough to scan
    - For projection size, look at wikantik_for_agent_response_bytes_bucket — `_count` should be non-zero, p50 well under 4 KB, p99 under 8 KB
    - For structural index health, watch wikantik_structural_index_{pages_total,clusters_total,unclaimed_total,lag_seconds} — unclaimed should be close to zero post-backfill
    - For Phase-5 retrieval quality (when shipped), wikantik_retrieval_ndcg_at_5{set,mode} is the headline gauge — alert threshold 0.75 absolute or > 5 % relative drop week-over-week
    - Cross-reference with /api/health/structural-index for a one-shot status check
  pitfalls:
    - Reading raw histogram buckets without the _count line — the buckets are cumulative and meaningless without the total
    - Comparing absolute numbers across deploys without checking the page-count gauge — a different corpus size shifts every metric
    - Confusing for-agent response size with retrieval quality — the histogram is about projection budget, not relevance
    - Alerting on a single threshold-cross — Phase-5 design specifies "absolute OR relative drop", and the relative window is week-over-week
  related_tools:
    - /api/health/structural-index
  references:
    - HybridRetrieval
    - AgentGradeContentDesign
    - HandlingEmbeddingServiceOutages
---

# Interpreting Hybrid Retrieval Metrics

Wikantik publishes a small set of Prometheus metrics covering structural
index health, for-agent projection size, and (post-Phase 5) retrieval
quality. They're all under the `wikantik_` prefix.

## When to use this runbook

When you have a `/metrics` scrape and want to convert numbers into
verdicts.

## Context

- **Phase 1 (shipped):** `wikantik_structural_index_{pages_total,clusters_total,tags_total,unclaimed_total,lag_seconds}` — gauges. Plus `wikantik_structural_index_rebuild_duration_seconds` (timer) for rebuild cost.
- **Phase 2 (shipped):** `wikantik_for_agent_response_bytes` — DistributionSummary with percentile histogram. Records every projection's serialised size.
- **Phase 5 (planned):** `wikantik_retrieval_{ndcg_at_5,ndcg_at_10,recall_at_20,mrr}{set,mode}` — gauges, plus `wikantik_retrieval_run_duration_seconds` (histogram) and `wikantik_retrieval_run_failed_total` (counter).

## Walkthrough

The frontmatter `steps` walk the metric set in priority order: index
health first (the foundation), projection size second (the agent
contract), retrieval quality third (the eventual signal).

## Pitfalls

The frontmatter `pitfalls` capture the recurring misreads. The
"comparing absolute numbers across deploys" trap is especially common —
agents take a snapshot of metrics, deploy a different corpus, then
report that "retrieval got worse" when the page count just shifted.
