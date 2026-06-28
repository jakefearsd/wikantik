# Agentic interface usefulness (Theme C) — design

**Date:** 2026-06-28
**Status:** approved (brainstorming), pending spec review
**Author:** brainstorming session (Jake + Claude)

## Problem

Theme C is the last tier of the improvement roadmap
(`docs/superpowers/specs/2026-06-28-improvement-roadmap-design.md`). The
grounded-agent eval surfaced three interface frictions on `/knowledge-mcp`:

- **A1 — looping:** the grounded-mcp arm re-called `retrieve_context` 3–4× within
  one answer on five questions.
- **A2 — over-elaboration:** on four questions the model, driving its own tool
  use, *lost* to the canned `assemble_bundle` bundle by asserting specifics
  beyond what retrieval returned.
- **A3 — no retrieval-mode toggle:** `/api/bundle` (and `assemble_bundle`) have
  no way to force lexical/dense/hybrid; the eval's `--lexical` flag is therefore
  inert and the dense-vs-BM25 comparison the eval design wanted is impossible.

A fourth observation — `dense-backend-options` scored bundle=0 despite the
content ranking #1 — is a probable synthesis/variance issue, not a clear bug.

### Key finding that shapes the design

`retrieve_context` **already returns full section text** (`contributingChunks[]`
carries `text` + `headingPath`), not just pointers. So A1/A2 are **model-behavior
steering** problems, not missing-content problems. `assemble_bundle` (ranked,
de-duplicated, version-pinned, citation-bearing section text across the whole
corpus) is simply the stronger answer-grounding primitive — which is why it beat
`retrieve_context` in the eval. The steering lever is therefore *tool
descriptions*, whose payoff is uncertain and measurable only via the (noisy)
eval.

## Scope (decided)

**A3 (deterministic) + a focused A1/A2 steering pass + a dense-gap investigation.**
A3 is the high-confidence, unit-testable core; A1/A2 are cheap, revertible
tool-description edits validated by a median eval run; the dense-gap is
investigated, fixed only if it turns out to be a real truncation bug.

## Component 1 — A3: per-request retrieval-mode toggle

### Current architecture

`BundleAssemblyService.assemble(String query)` takes only a query. The retrieval
source is selected **once at wiring time** in `BundleServiceWiring` from config
and injected into `DefaultBundleAssemblyService`:

- `HybridChunkSectionSource` — RRF-fuses dense + BM25 (`wikantik.bundle.bm25.*`).
- `DenseChunkSectionSource` — dense-only.
- `RetrievalSectionSource` — page-gated hybrid (fallback when dense disabled).

There is no per-request mode and no BM25-only section source today.

### Design

- **`RetrievalMode` enum** in `wikantik-api` (`com.wikantik.api.bundle`):
  `HYBRID`, `DENSE`, `LEXICAL`. `HYBRID` is the default (current behavior).
- **`BundleAssemblyService`** gains `ContextBundle assemble(String query,
  RetrievalMode mode)`. The existing `assemble(String query)` becomes a default
  method delegating to `assemble(query, <default mode>)` — backward compatible;
  no existing caller changes.
- **`DefaultBundleAssemblyService`** is constructed with a
  `Map<RetrievalMode, SectionCandidateSource>` plus a default mode, instead of a
  single source. `assemble(query, mode)` looks up the source for `mode`. If the
  requested mode has no available source (e.g. `DENSE`/`HYBRID` when embeddings
  are down, or `mode` absent from the map), it **degrades to the default
  source** and logs one WARN — never throws, never returns null (consistent with
  the bundle's fail-soft posture).
- **`BundleServiceWiring`** builds the map:
  - `HYBRID` → `HybridChunkSectionSource` (when BM25 enabled) else
    `DenseChunkSectionSource`.
  - `DENSE` → `DenseChunkSectionSource`.
  - `LEXICAL` → a BM25-only path. Preferred implementation: reuse
    `HybridChunkSectionSource` constructed with a `HybridFuser` weighted
    dense=0/bm25=1 (no new class, reuses the tested fusion path). If that proves
    awkward, a thin `Bm25OnlyChunkSectionSource` wrapping `LuceneBm25ChunkIndex`
    is the fallback. The plan picks one after reading `HybridFuser`.
  - When the page-gated `RetrievalSectionSource` is the only option (dense
    disabled), all three modes map to it (toggle is a no-op; logged once).
- **Surfaces:**
  - `GET /api/bundle?q=…&mode=hybrid|dense|lexical` (`BundleResource`). Missing
    `mode` → `HYBRID`. Unrecognized value → HTTP 400 with a clear JSON error
    listing valid modes.
  - `assemble_bundle` (`AssembleBundleTool`) gains an optional `mode` string arg
    (same three values, default `hybrid`); the tool description documents it.
    Invalid value → the tool's existing error-result path with a clear message.
  - The retrieval-query log entry records the mode used (if cheaply available on
    the existing `QueryLogService` write path; otherwise out of scope).

### Eval unblock (byproduct)

Wire the harness's currently-inert `--lexical` flag to send `mode=lexical`:
`bundle_client.fetch_bundle` adds `&mode=lexical` when `lexical=True`, and the
`assemble_bundle` MCP arm passes `mode="lexical"`. This makes the eval's
dense-vs-BM25 comparison real.

## Component 2 — A1/A2: reposition + guardrails (tool-description edits only)

No logic change — three description strings:

- **`assemble_bundle`** (`AssembleBundleTool`): documented as the **primary
  answer-grounding tool** — "Returns a ranked, de-duplicated, version-pinned,
  citation-bearing set of section texts. Prefer this to answer how/why/what
  questions from the wiki; ground your answer only in the returned sections and
  cite them."
- **`retrieve_context`** (`RetrieveContextTool`): reframed as **page/section
  discovery** + guardrails — "Discover which wiki pages/sections are relevant
  (returns pages with their top contributing chunk texts + related pages). To
  compose an answer, prefer `assemble_bundle`. One call usually suffices — raise
  `maxPages`/`chunksPerPage` rather than re-querying with reworded queries.
  Ground any claim only in the returned chunk text; do not add specifics that
  aren't present." Keep the `{query, pages:[…], totalMatched}` return-shape
  documentation.
- **`KnowledgeMcpInitializer`** server-instructions blurb: point answer-grounding
  at `assemble_bundle`, `retrieve_context` for discovery.

These are revertible in one commit if the eval shows no lift.

## Component 3 — dense-backend gap: investigation only

During implementation, call `assemble_bundle` for the dense-backend query and
inspect the returned section text: if the 3-backend table is **truncated**
(a real bundle bug) → fix the truncation; if it is returned **whole** (the model
just under-synthesized) → record as model/variance, no code change. Findings go
in the plan's report; not a committed deliverable.

## Testing & validation

- **A3 — deterministic JUnit:** mode routing (`LEXICAL` → BM25-only path;
  `DENSE` → dense-only; `HYBRID` → fused); unavailable-source fallback degrades
  to default + logs; `BundleResource` mode parsing (valid/missing/invalid);
  `AssembleBundleTool` mode arg. No eval needed for A3 correctness.
- **A1/A2 — median eval run:** `run_eval.py --run-id … --samples 3` before and
  after the description edits. **Success = no correctness regression on the
  grounded arms AND a directional drop in `retrieve_context` loop count
  (interface-findings) and/or the mcp-vs-bundle gap.** Steering is soft; if the
  median run shows no benefit, revert the three description edits (keep A3).
- **Eval dense-vs-BM25:** a `--lexical` vs default run to quantify dense's
  contribution (reporting only; not a gate).

## Non-goals / out of scope

- Not changing the eval harness's *system prompt* (arms.py) to steer the model —
  we change only the **wiki's** tool descriptions (what any real agent sees).
- Not a new retrieval algorithm; LEXICAL reuses the existing BM25 index/fuser.
- Not chasing the dense-backend synthesis gap with corpus edits (Theme A already
  confirmed that content is correct + rank #1).
- Not persisting per-mode analytics beyond what the existing query log offers.

## Risks & mitigations

- **A3 wiring rework touches the bundle hot path.** Mitigation: backward-compatible
  default method; default mode = current behavior; fail-soft degrade-to-default;
  deterministic unit tests including the embeddings-down fallback.
- **A1/A2 steering may not move the needle** (model-behavior-dependent, noisy
  measurement). Mitigation: median (`--samples 3`) run; revert the description
  edits if no lift — they are isolated strings.
- **LEXICAL via dense-weight-0 fuser** could behave subtly differently from a
  pure BM25 ranking. Mitigation: the plan verifies the fused-with-dense-0 output
  equals the BM25 ranking on a unit test; fall back to a thin BM25-only source
  if not.

## Out of this spec / future

If A1/A2 steering shows a real lift, consider promoting `assemble_bundle` to the
`/tools/*` OpenAPI surface. The deferred extractor out-of-vocab synonym-map
(measure-first, from Theme B) remains separate.
