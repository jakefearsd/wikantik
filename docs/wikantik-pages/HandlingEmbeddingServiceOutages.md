---
title: Handling Embedding Service Outages
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ4QYF0V2A08QN5MCR3M4C6P
audience:
- humans
runbook:
  when_to_use:
  - Hybrid retrieval results look noticeably worse than usual
  - You see the Ollama process down or the qwen3-embedding model unavailable
  - You want to deliberately exercise the BM25 fallback path
  inputs:
  - Symptom (degraded results, error in logs, or a deliberate test)
  - Whether you have access to the embedding host (Ollama)
  steps:
  - Confirm the embedding backend is reachable - curl -fsS <wikantik.search.embedding.base-url>/api/tags
    should return JSON listing qwen3-embedding:0.6b (the Docker deployment uses its
    bundled ollama sidecar)
  - If Ollama is down, restart it and wait for the model load - the circuit breaker
    closes itself after its 30 s cooldown and hybrid retrieval recovers automatically
  - If Ollama is up but results are bad, read the Wikantik logs for 'Query embedder
    circuit breaker OPEN', 'DenseRetriever failed; falling back to BM25' or 'Bootstrap
    indexer stale-reconcile FAILED'
  - When BM25 is in active fallback, broaden your query - dense recall is gone, so
    synonyms matter more
  - Check /metrics for wikantik_search_hybrid_embedder_circuit_state (0 CLOSED, 1
    HALF_OPEN, 2 OPEN) and wikantik_search_hybrid_embedder_calls_total{result} - or
    Admin -> Content -> Index Status
  - Afterwards, run Reindex Embeddings (or restart once the backend is healthy) so
    chunks saved during the outage get vectors
  pitfalls:
  - Treating BM25 fallback as a code defect - it is the designed degraded mode; do
    not chase a bug
  - Restarting Wikantik to clear the outage itself - the breaker heals on its own;
    restart the embedding backend instead
  - Trusting hybrid scores as authoritative during fallback - the dense contribution
    is zero, so RRF reduces to BM25 ranks
  - Failing to flag the degradation to the user - the response will look plausible
    but recall is reduced; a bundle with confidence unknown is the tell
  - Assuming a recovered breaker re-embeds skipped chunks - pages saved during the
    outage stay unembedded until the next startup reconcile or a Reindex Embeddings
    run
  related_tools:
  - /admin/content/index-status
  - /admin/content/reindex-embeddings
  - /api/search
  - /api/bundle
  - /knowledge-mcp/assemble_bundle
  references:
  - HybridRetrieval
  - InterpretingHybridRetrievalMetrics
  - ChoosingARetrievalMode
  - RunningTheRetrievalQualityHarness
summary: 'Embedding service (Ollama) down: hybrid retrieval fails closed to BM25 with
  no visible error; how to detect it, recover, and re-embed missed chunks.'
tags:
- embeddings
- outages
- circuit-breaker
- resilience
- rag
- vector-search
- agent-cookbook
---
# Handling Embedding Service Outages

Hybrid retrieval is designed fail-closed: when the embedding service goes away, the system reverts to BM25 with no user-visible error and no service interruption. The trade-off is a quality drop that agents need to detect. Wikantik has **no** local or in-process fallback encoder (no ONNX model, no in-memory encoder) — the only fallback is lexical search.

## When to use this runbook

When retrieval is misbehaving and you suspect (or want to confirm) it's the embedding side — for example "the embedding service is down", hybrid results look noticeably worse, or you want to exercise the BM25 fallback on purpose.

## Context

Every query is embedded by `QueryEmbedder` (Ollama, default model `qwen3-embedding-0.6b`). It wraps the client in a 2 s per-call timeout, a Caffeine cache of query vectors (4 h TTL, 1000 entries, so a repeated query still gets dense results during a short outage), and a circuit breaker. The breaker watches a rolling window of the last 20 calls: once at least 10 have been seen and half or more failed, it trips **OPEN** and short-circuits for 30 s, then admits one **HALF_OPEN** probe; success closes it, failure re-opens it. All thresholds are `wikantik.search.hybrid.embedder.*` keys (see [HybridRetrieval](HybridRetrieval)).

When the embedder returns nothing, each surface degrades differently:

| Surface | Behaviour |
|---------|-----------|
| `GET /api/search`, `retrieve_context` | BM25 ordering returned unchanged |
| `GET /api/bundle`, `assemble_bundle` (default `mode=hybrid`) | Ranked from the BM25 chunk index alone; the coverage block shows `topSimilarity: -1.0` and `confidence: unknown` |
| same, `mode=lexical` | Unaffected — BM25 only by design |
| same, `mode=dense` | No candidates; the bundle comes back empty (WARN `Query embedding unavailable; dense-chunk bundle candidates empty`) |

Page saves keep working: chunking and the BM25 chunk index update first, then embedding is attempted. If the embed fails, `Async embedding reindex failed` is logged at WARN and the listener does **not** requeue the chunks, so pages saved during the outage have no vectors until the next startup reconcile or a Reindex Embeddings run.

## Walkthrough

The frontmatter `steps` are the canonical diagnostic sequence; the details behind each:

1. **Is the backend reachable?** `curl -fsS <wikantik.search.embedding.base-url>/api/tags` should return JSON listing `qwen3-embedding:0.6b`. The shipped default is `http://inference.jakefear.com:11434`; the Docker deployment points at its bundled `ollama` sidecar. Ollama up but the model never pulled (`ollama pull qwen3-embedding:0.6b`) fails every embed just like an outage.
2. **Restart the backend, not Wikantik.** Once Ollama answers again, the breaker closes by itself after its cooldown (look for `Query embedder circuit breaker CLOSED (recovered from …)`); hybrid retrieval recovers automatically.
3. **If the backend is up but results are bad,** read the logs for `Query embedder circuit breaker OPEN`, `DenseRetriever failed; falling back to BM25` (for example a vector-dimension mismatch after someone changed `wikantik.search.embedding.model`), and `Bootstrap indexer stale-reconcile FAILED`.
4. **Check the signals.** Admin → Content → Index Status shows the circuit state and call counters. In `/metrics`: `wikantik_search_hybrid_embedder_circuit_state` (0 CLOSED, 1 HALF_OPEN, 2 OPEN), `wikantik_search_hybrid_embedder_calls_total{result="failure|timeout"}`, `wikantik_search_hybrid_embedder_breaker_rejected_total`, `wikantik_search_hybrid_vector_index_size` and `wikantik_search_hybrid_bootstrap_state`. `wikantik_retrieval_run_failed_total` counts failed *quality-runner* runs, not embedding failures.
5. **While BM25 is in fallback, broaden your query** — dense recall is gone, so synonyms matter more.
6. **Heal the gap afterwards.** Chunks missed during the outage are embedded by the startup stale reconcile (it retries 4 times, 45 s apart, while the backend warms), or immediately by **Reindex Embeddings** (`POST /admin/content/reindex-embeddings`), which deletes the model's rows from the database and re-embeds everything. On the default `lucene-hnsw` backend (and on `inmemory`) the RAM index keeps serving its current vectors and is only reloaded when that run ends COMPLETED, so a run that ends FAILED heals nothing; on `pgvector` the dense side starts empty and refills as the rows stream back.
7. **Deliberate degradation:** `wikantik.search.hybrid.enabled=false`, or `wikantik.genai.mode=none`, builds no embedding client at all and runs BM25-only.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes. The "BM25 fallback is a defect" misreading is the most common — it's a feature, and chasing it as a bug wastes time.

- **Restarting Wikantik to clear the outage.** The breaker heals itself. A restart is only useful afterwards, to run the stale reconcile for pages saved during the outage.
- **Trusting hybrid scores during fallback.** The dense contribution is zero, so RRF reduces to BM25 ranks.
- **Failing to flag the degradation.** The response looks plausible but recall is reduced; a bundle with `confidence: unknown` is the tell.
- **Forgetting the missed chunks.** A recovered breaker does not re-embed what was skipped.
