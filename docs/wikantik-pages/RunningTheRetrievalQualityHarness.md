---
canonical_id: 01KQ42G7FNHJXBFF246W07JZ3K
title: Running the Retrieval Quality Harness
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: How to invoke `RetrievalExperimentHarness` manually today, and what the upcoming Phase-5 nightly scheduler will add. Includes how to read the per-mode nDCG / Recall / MRR output and where the eventual Prometheus gauges will land.
tags:
  - retrieval
  - evaluation
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You are about to land a change that touches retrieval (chunker, embeddings, rerank weights, query parsing)
    - A user reports anecdotal quality regression and you want a quantitative read
    - You are calibrating thresholds before Phase 5 schedules nightly runs
  inputs:
    - A query set (the design names `core-agent-queries` as the seed — until Phase 5 lands you may need to author one)
    - The retrieval mode under test (`bm25`, `hybrid`, or `hybrid_graph`)
  steps:
    - Confirm the harness is on the classpath — class name is `com.wikantik.knowledge.eval.RetrievalExperimentHarness` (or as currently named in the wikantik-knowledge module)
    - Build with mvn install -DskipITs so the harness has a fresh artifact set
    - Invoke the harness with your query set and mode — return value is per-query plus aggregate (nDCG@5, nDCG@10, Recall@20, MRR)
    - Compare to the prior baseline; an absolute drop > 5% in nDCG@5 is the design's alert threshold
    - Once Phase 5 is shipped, the same call runs nightly on a ScheduledExecutorService and writes to the `retrieval_runs` table
  pitfalls:
    - Running the harness once and treating the number as authoritative — variance across runs matters; take median of three
    - Comparing across different query sets — every query set has its own absolute baseline; mode-relative comparisons are the safer signal
    - Forgetting to rebuild the index after a change to chunker config — stale chunks give stale scores
    - Using BM25-mode results to evaluate hybrid changes — the modes are independent comparisons
  related_tools:
    - /api/search
    - /knowledge-mcp/search_knowledge
  references:
    - RetrievalExperimentHarness
    - HybridRetrieval
    - AgentGradeContentDesign
    - InterpretingHybridRetrievalMetrics
---

# Running the Retrieval Quality Harness

Wikantik already has a retrieval experiment harness; what's pending is
the scheduler that turns it into a nightly CI gate. Until Phase 5
ships, the harness is invokable manually for spot-checks.

## When to use this runbook

Before merging a retrieval-touching change, or when investigating an
anecdotal regression.

## Context

The harness lives in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/eval/`
(or its current equivalent). It evaluates a query set against a chosen
retrieval mode, computes nDCG@k, Recall@k, and MRR, and returns
per-query + aggregate scores.

Phase 5 of `AgentGradeContentDesign` adds the missing pieces: a
scheduled runner, a database-backed query-set store
(`retrieval_query_sets` / `retrieval_queries` / `retrieval_runs`),
Prometheus gauges, and a `RetrievalQualitySmokeTest` that runs in CI
on every merge.

## Walkthrough

The frontmatter `steps` are the canonical procedure for the manual
path. Once Phase 5 lands, the same logic runs without operator
intervention nightly.

## Pitfalls

The frontmatter `pitfalls` capture the recurring methodology mistakes.
"One run is authoritative" is the most common — retrieval scores have
real variance across runs and any single number is suspect.
