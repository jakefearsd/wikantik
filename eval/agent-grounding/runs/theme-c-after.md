# Theme C — agentic interface usefulness: validation

**Run:** `theme-c-3s-20260628T2200Z` (dense, N=16, **--samples 3 median**) vs the
post-Theme-A baseline `theme-a-20260628T115937Z` (**--samples 1**).

## Means (correctness 0–2)

| Arm | Baseline (1-sample) | Theme C (3-sample median) | Δ |
|-----|---------------------|---------------------------|---|
| cold | 0.062 | 0.000 | −0.06 (noise; ungrounded) |
| grounded_bundle | 1.500 | **1.625** | +0.125 |
| grounded_mcp | 1.625 | **1.562** | −0.063 |

**Methodology caveat:** baseline was a single sample; this run is a 3-sample
median. There is no `--samples 3` *before* measurement, so the correctness
comparison is muddy — the reliable A1/A2 signals are the raw tool-usage logs
(directly comparable across runs).

## A1/A2 behavioral goals — clearly achieved (directly comparable)

| Signal | Theme A baseline | Theme C | Result |
|--------|------------------|---------|--------|
| `assemble_bundle` calls | **0** (model never used it) | **20** (now the most-used tool) | ✅ reposition worked |
| `retrieve_context` calls | 34 | 8 | ✅ demoted to discovery |
| Questions with a tool loop (×3–4) | **5** | **1** | ✅ anti-loop worked |
| Theme-A mcp<bundle hallucinations (kg-rerank / chunker / contextual-embeddings) | 3 | 0 (all now mcp==bundle) | ✅ resolved |

The reposition + guardrails decisively shifted model behavior: `assemble_bundle`
went from **unused to the primary tool**, and per-answer looping collapsed
5→1. These are the real A1/A2 objectives and benefit every real agent (lower
tool-call cost, better default grounding primitive).

## Correctness — flat within noise

grounded_mcp mean moved −0.063 (≈ one question of 16) — within observed
run-to-run variance (cold and untouched-content questions also shuffled ±1).
Per-question movement vs baseline:
- **Improved/held:** kg-rerank 0→2, contextual-embeddings 0→2, chunker 0→1,
  dense-backend bundle 0→1, mcp-tool-counts mcp→2.
- **Dropped (offset, within noise / untouched content):** `read-path-acl`
  mcp 2→0 (bundle also only 1 there — weak retrieval for that Q regardless of
  steering); `scim-group-admin` mcp 2→1 (SCIM content untouched by any theme).

No clean evidence of aggregate correctness harm; the steering held correctness
flat while improving behavior.

## dense-gap investigation (Task 6 step 4) — not a bug

`assemble_bundle` returns the **complete** 3-backend table at rank #1 (no
truncation). Root cause of the historical miss: the table's "Default" column
marks **`inmemory`** (the shipped `wikantik.properties` default), while the
**docker1 production default (`lucene-hnsw`)** is documented in a separate prose
subsection. A model reading the rank-1 table answers "inmemory." → **Follow-up
(out of Theme C scope):** a one-line corpus clarification on `HybridRetrieval`
marking lucene-hnsw as the docker1 production default in the table itself.

## Verdict

**KEEP A1/A2.** The steering achieved its stated behavioral goals (assemble_bundle
now primary; looping 5→1) with correctness flat within noise. A1/A2 are low-risk,
revertible description strings. A3 (mode toggle) is deterministic and was verified
live on prod (mode=lexical/dense→200, invalid→400, no-mode→HYBRID unchanged).

**Follow-ups:** (1) `HybridRetrieval` dense-backend "Default" table clarification
(corpus); (2) the eval would benefit from a `--samples 3` *baseline* for clean
future before/after correctness comparison.
