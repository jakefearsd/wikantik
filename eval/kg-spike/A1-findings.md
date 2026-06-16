# Phase 4 Track A — A1 ceiling gate: findings

**Date:** 2026-06-16. **Environment:** local dev (`localhost:8080`), local Postgres `wikantik`.
**Slice:** 7 headroom RELATIONAL questions (`slice-ids.txt`: r01,r02,r05,r06,r07,r08,r10),
9 pages (`slice-pages.txt`). **Extraction:** `gemma4-graph:12b`, 65 mentions total across the 9 pages
(4–7 entities/page; `CanaryDeployments` = 0 entities). Harness: `bin/eval/spike-kg-rerank.py`.

## Headline: the spike found a *structural* blocker, not just sparse coverage

The KG graph rerank (`GraphRerankStep`, knob `wikantik.search.graph.boost`) reorders **whole page
names** inside the **page-gated** hybrid retrieval path (`ContextRetrievalService.retrieve`). But the
shipped `/api/bundle` default is the **global dense-chunk source** (`DenseChunkSectionSource`,
`wikantik.bundle.dense.enabled=true`), which goes straight from query embedding to
`ChunkVectorIndex.topKChunks` and **never invokes the graph rerank**. The only rerank hook in the
bundle pipeline is the `SectionReranker` (LLM listwise reranker, default-off).

So the boost knob is **architecturally absent from the path we actually ship** — which is also the
*higher-recall* path (dense ceiling recall@12 ≈ 0.735 vs page-gated ≈ 0.685).

## Numbers (7-question headroom slice, section recall@k)

### Dense-chunk path (default `/api/bundle`) — graph rerank bypassed

| boost | recall@5 | recall@12 | golds changed vs off |
|-------|----------|-----------|----------------------|
| 0 (off) | 0.3571 | 0.4286 | — |
| 0.2 | 0.3571 | 0.4286 | 0 |
| 0.5 | 0.3571 | 0.4286 | 0 |
| 1.0 | 0.3571 | 0.4286 | 0 |

Bit-identical at every boost — not a single gold's hit status changed (verified per-gold). Confirms
the boost is not in this code path.

### Page-gated path (`wikantik.bundle.dense.enabled=false`) — graph rerank live

| boost | recall@5 | recall@12 | vs off |
|-------|----------|-----------|--------|
| 0 (off) | 0.3571 | 0.3571 | — |
| 0.2 | 0.2857 | 0.2857 | **−1 gold** (r01 "Fail-closed behaviour" demoted out of top-12) |
| 0.5 | 0.2857 | 0.2857 | −1 gold (same) |

The rerank IS live here (output changes), but with the current `gemma4-graph:12b` extraction it is a
**net-negative** reranker — it demotes a correct page and surfaces nothing new, at every boost tried.
This is exactly the "robs Peter to pay Paul" regression the spec's no-regression guard warns about.

## Interpretation against the A1 gate

The spec's A1 asks: *"would a good KG help — measured by boost-on vs boost-off lift?"* Two findings
block a clean "yes":

1. **Structural (path):** the rerank can't affect the shipped dense bundle at all. Turning the boost
   on does literally nothing to the path we serve. To express any KG signal in the shipped bundle
   would require **new code** — a section-level graph-rerank seam inside `DenseChunkSectionSource` —
   which the spike did not scope.
2. **Quality (extraction):** on the page-gated path where the rerank does run, the current local
   extraction makes it worse, not better. Establishing the true ceiling needs a richer extraction
   (the `ClaudeEntityExtractor`, slice-scoped — a premium-spend touchpoint).

Per the spec, **"no lift" is a pre-committed, fully-acceptable outcome.** What we have is stronger
than "no lift": the retrieval-signal mechanism, as built, is mis-wired for the shipped path and
net-harmful on the alternate path.

## Options at the gate (touchpoint #2 — the user's call, involves cost)

- **A — Shelve the retrieval-signal track now (recommended, $0).** Document this finding; keep the KG
  for its human-KB role (Track B). The structural blocker + the page-gated regression are sufficient
  to stop here under the pre-committed gate.
- **C — Establish the page-gated ceiling cheaply first (small Claude spend, ~9 pages).** Re-extract
  the 9 slice pages with `ClaudeEntityExtractor`, re-run the page-gated sweep. Decisively answers
  "can a *good* KG at least make the rerank non-negative?" before any code investment. If it stays
  negative → shelve with maximum confidence. If it goes positive → justifies option B.
- **B — Reframed larger investment.** Build a section-level KG-rerank seam into the dense bundle +
  rich extraction; only worth it if relational retrieval becomes a named product priority.

## Reproducibility

All artifacts in `eval/kg-spike/`: `recall-off-slice.json`, `recall-on-slice.json` (dense 0.2),
`recall-on-boost-{0.5,1.0}.json` (dense), `recall-pagegated-{off,on-0.2,on-0.5}.json`,
`slice-ids.txt`, `slice-pages.txt`, `slice-rationale.md`, `extract-reports/`. Harness +
self-tests: `bin/eval/spike-kg-rerank.py`, `bin/eval/test_spike_kg_rerank.py`. Config levers:
`wikantik.bundle.dense.enabled` (source select), `wikantik.search.graph.boost` (rerank strength) —
both read at startup; flip + restart to reproduce. Wiring confirmed in `catalina.out`:
`Graph rerank wired (boost=…)` + `Bundle assembly service wired (source=page-gated|dense-chunk …)`.
