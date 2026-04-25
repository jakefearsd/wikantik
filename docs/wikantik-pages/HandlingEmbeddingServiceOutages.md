---
canonical_id: 01KQ4QYF0V2A08QN5MCR3M4C6P
title: Handling Embedding Service Outages
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: What happens when the embedding service (Ollama / nomic-embed) is down — hybrid retrieval fails closed to BM25, no user-visible error, but agents should detect the degradation and adjust expectations.
tags:
  - resilience
  - embedding
  - retrieval
  - runbook
  - agent-context
runbook:
  when_to_use:
    - Hybrid retrieval results look noticeably worse than usual
    - You see the Ollama process down or the nomic-embed model unavailable
    - You want to deliberately exercise the BM25 fallback path
  inputs:
    - Symptom (degraded results, error in logs, or a deliberate test)
    - Whether you have access to the Ollama host
  steps:
    - Confirm whether Ollama is reachable — `curl -fsS http://localhost:11434/api/tags` should return JSON
    - If Ollama is down, restart it and wait for the model load — hybrid retrieval recovers automatically
    - If Ollama is up but results are bad, inspect HybridSearchService logs for "embedding service unavailable" or "rerank skipped"
    - When BM25 is in active fallback, broaden your query — dense recall is gone, so synonyms matter more
    - Check /metrics for wikantik_retrieval_run_failed_total (Phase 5 metric, when present) — the embedding outage shows up there separately from quality drops
  pitfalls:
    - Treating BM25 fallback as a code defect — it's the designed degraded mode; do not chase a "bug"
    - Restarting Wikantik to "fix" embedding-service outages — Wikantik is fine; restart Ollama instead
    - Trusting hybrid scores as authoritative during fallback — the embedding contribution is zero, so RRF reduces to BM25 ranks
    - Failing to flag the degradation to the user — the response will look plausible but recall is reduced
  related_tools:
    - /api/health/structural-index
    - /api/search
    - /knowledge-mcp/search_knowledge
  references:
    - HybridRetrieval
    - InterpretingHybridRetrievalMetrics
    - ChoosingARetrievalMode
---

# Handling Embedding Service Outages

Hybrid retrieval is designed fail-closed: when the embedding service
goes away, the system reverts to BM25 with no user-visible error and no
service interruption. The trade-off is a quality drop that agents need
to detect.

## When to use this runbook

When retrieval is misbehaving and you suspect (or want to confirm) it's
the embedding side.

## Context

`HybridSearchService.rerank()` is the integration point. It calls the
embedding service for each query, fuses dense scores with BM25 via
Reciprocal Rank Fusion (k=60), and returns the merged result list. When
the embedding call throws, the dense list is treated as empty and RRF
reduces to BM25 ranks.

The local default embedding service is Ollama running `nomic-embed-v1.5`
on port 11434. Misbehaviour usually traces to (a) the Ollama process
crashed, (b) the model wasn't loaded, or (c) GPU/memory pressure
delaying inference.

## Walkthrough

The frontmatter `steps` are the canonical diagnostic sequence: confirm
Ollama, restart if needed, look at logs, broaden the query while in
fallback, check metrics.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes. The "BM25
fallback is a defect" misreading is the most common — it's a feature,
and chasing it as a bug wastes time.
