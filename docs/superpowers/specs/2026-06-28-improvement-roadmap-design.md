# Improvement roadmap — design

**Date:** 2026-06-28
**Status:** approved (brainstorming), pending spec review
**Author:** brainstorming session (Jake + Claude)

## Problem

The week of 2026-06-21 → 06-28 produced a backlog of concrete, measured
improvement opportunities from three sources:

1. The **grounded-agent eval** (`eval/agent-grounding/`, design
   `docs/superpowers/specs/2026-06-27-grounded-agent-eval-design.md`) — a
   reproducible scorecard + interface-friction findings.
2. The **prod graceful-degradation log review** (inference host powered off
   2026-06-27) — four issues, two of which (#1 embedding backpressure, #2 judge
   log-spam) are **already fixed and shipped** (`b2ccd46c0c`, `e7b00c1fbf`).
3. Incidental **cleanups** noticed while doing the above.

This roadmap sequences the *remaining* opportunities into prioritized themes,
each with scope / effort / dependencies, and names the **first tier to be
deep-planned** (a separate TDD-ready implementation plan via writing-plans).

## What the eval actually measured (grounding facts)

Latest run `smoke-20260628T062534Z`, N=16, dense (GPU up):

| Arm | Mean correctness (0–2) | Citation hit |
|-----|------------------------|--------------|
| cold | 0.062 | 0% |
| grounded_bundle | 1.312 | 81% |
| grounded_mcp | 1.438 | 75% |

**Verdict: grounding is decisively worth it** (+1.25 / +1.38 over cold). The
improvement opportunities are about closing the *remaining* gaps, not proving
value. The two diagnostic patterns that drive the roadmap:

- **Model autonomy sometimes regresses vs. the canned bundle.** grounded_mcp
  *lost* to grounded_bundle on 4 questions because, given freedom, the model
  over-elaborated past what retrieval supported and hallucinated specifics:
  `kg-rerank-default` (claimed the reranker is enabled by default — it is OFF /
  dormant), `chunker-heading-fidelity` (described the wrong bug + dead config
  keys), `contextual-embeddings-prefix` (invented a breadcrumb format),
  `kg-predicates-count` (partial). These trace to **thin or ambiguous corpus
  pages** on those exact facts — the agent had nothing authoritative to anchor on.
- **Single-query bundle retrieval has holes.** `dense-backend-options` and
  `kg-inclusion-default` scored bundle=0 but mcp=2 — the content *exists* (the
  tool-driven arm found it) but one canned bundle query missed it. A
  **retrieval-recall / query-shaping** signal, not a content gap.
- **Looping.** 5 questions issued `retrieve_context` ×3–4 within a single
  answer (`bundle-vs-synthesis`, `entity-class-count`, `read-path-acl`,
  `kg-inclusion-default`, `dense-backend-options`) — wasted calls, latency, cost.

## Themes (prioritized & sequenced)

Sequencing per the user's 2026-06-28 decision: **Corpus internals coverage runs
first**, ahead of the code-side work, because (a) it directly raises the agent's
correctness ceiling on the exact questions that regressed, (b) it is the cheapest
high-leverage lever (content, no deploy), and (c) the fixes are *verifiable today*
with the eval harness we just built.

### Theme A (FIRST) — Corpus internals coverage  · P0 · effort M

Author/strengthen the specific wiki pages whose thinness produced the eval's
hallucinations and retrieval misses. **Targeted, not generic** — corpus-wide
"enrichment" has been measured to *hurt* recall
(`[[reference_mcp_content_authoring_surface]]`), so every edit is gap-driven and
verified against `assemble_bundle`.

Concrete targets, each tied to a failing/weak eval question:

| Gap | Eval signal | Fix |
|-----|-------------|-----|
| KG rerank is **OFF / dormant** (boost=0, never wired) | mcp confidently said "enabled by default" | Make the default-OFF + "shelved Phase 4 Track A, zero net lift" fact explicit + retrievable (`KnowledgeGraphRerank` / HybridRetrieval page) |
| Chunker heading-fidelity fix (force-emit at heading boundary, `fragment_floor_tokens`, `overlap_tokens`) | mcp described the wrong bug + dead config keys | Add an authoritative, named-config section |
| Contextual document-embedding prefix (`Page: {title} \| Cluster: {cluster} \| Section: {heading}` + summary) | mcp invented a breadcrumb format | State the exact prefix format + the 0.60→0.74 recall number |
| Dense backend options (`inmemory \| pgvector \| lucene-hnsw`, docker1 default) | bundle=0 (retrieval miss) | Ensure a single section names all three + the default; confirm it's chunked into its own retrievable section |
| KG predicate count (21) + external-vocab mappings | mcp partial | Tighten the canonical statement |

**Dependency:** re-embedding + `assemble_bundle` verification require the
inference host (GPU/Ollama) to be **up**. If it's powered off for weather, this
theme is blocked on power-on; the code-side themes (B/D/E) are not.

**Done = ** each target question re-scores ≥ the bundle arm's prior score in a
fresh eval run, with no recall regression on the others.

### Theme B — Judge robustness  · P1 · effort S

The two *unfixed* degradation findings:

- **#3 — JSON fence-parsing + truncation NPE.** 31× `MalformedJsonException`
  (`$.```json`): the judge LLM sometimes wraps its JSON in ```` ```json ````
  fences. Strip fences before parse; null-guard the truncated-body path so a
  partial response degrades to a transient-retry verdict, not an NPE.
- **#4 — Out-of-vocab rejection logged as "failed."** ~12× closed-vocabulary /
  SHACL rejections logged at WARN as "judge processing failed" (reads like an
  error). Reclassify to a distinct "rejected" outcome at INFO. Secondary:
  the volume signals the **extractor proposes many out-of-vocab predicates** —
  a prompt / synonym-mapping opportunity (measure before changing).

Both live in `DefaultKgProposalJudgeService` / `JudgeRunner` / the extractor —
one coherent Java unit, TDD-friendly, no eval dependency.

### Theme C — Agentic interface usefulness  · P1 · effort S–M

Make model-driven retrieval beat (not lose to) the canned bundle:

- **A1 — `retrieve_context` looping.** Investigate why 5 questions re-called it
  3–4×. Likely the return shape doesn't signal "you already have enough." Tighten
  the tool description / return contract (and/or surface a result-count hint) so
  the model stops re-querying. Validate by re-running the eval and counting loops.
- **A2 — steer autonomy toward grounding.** The 4 mcp<bundle regressions are the
  model elaborating past retrieval. Tighten `retrieve_context` /
  `assemble_bundle` tool descriptions to discourage answering beyond cited
  sections; consider surfacing the bundle as the *recommended default* path.
  Iterative — measured by the eval.
- **A3 — retrieval-mode toggle on the bundle.** `/api/bundle` has no lexical /
  mode parameter (confirmed in `bundle_client.py`); expose one so callers can
  force BM25 / dense / hybrid, and align it with `assemble_bundle`.

Theme C depends on the eval harness (Theme D keeps it healthy) and benefits from
Theme A having raised the content ceiling first.

### Theme D — Eval-harness follow-ups  · P2 · effort S

Strengthen the measurement loop so Themes A/C have a trustworthy gauge:

- **Citation-hit comparability.** bundle (81%) vs mcp (75%) hit-rates aren't
  apples-to-apples (different citation surfaces). Normalize or caveat in the
  scorecard (see `docs/agent-grounding` caveat commit `54a14e0e53`).
- **Robustness.** Graceful handling already added for connect failure + WAF UA;
  add retry/timeout polish and a `--internal` (docker1:8080) target switch.
- **Scheduling.** Currently manual (paid API). Decide cadence — ad-hoc after
  Theme A/C edits is sufficient; no CI wiring (per the eval design non-goals).

### Theme E — Cleanups  · P3 · effort XS

- `conn.rollback` hardening in `EmbeddingIndexService` (noticed during fix #1).
- `MEMORY.md` index line still says "25/18" → "26/20" (tool counts).
- Eval Minors M1–M13 (logged in the eval run notes) — triage, fix the cheap ones.

Some of these I can simply do without a plan (the MEMORY.md line, the index fix).

## Build sequence

```
Theme A (corpus, GPU-gated)  ─►  Theme B (judge, Java)  ─►  Theme C (interface)
                                       │
Theme E cleanups: opportunistic       └─►  Theme D (eval) keeps C honest
```

A first (ceiling), B in parallel/next (independent Java, no GPU), C after A+D,
D alongside C, E whenever.

## Deep-plan tier (next: writing-plans)

The **first detailed implementation plan is Theme A — Corpus internals
coverage**, since it is now the first task. It is content work driven through the
MCP wiki-content surface (`update_page` / `write_pages`, verified with
`assemble_bundle`) per `[[feedback_wiki_content_skill_mcp_only]]`, with a
re-run of `eval/agent-grounding` as the acceptance gate.

**Theme B — Judge robustness** is the immediate next plan (separate unit: Java +
TDD). Themes C/D/E follow per the sequence above.

## Non-goals

- Not re-litigating the grounding verdict (settled: grounding wins).
- Not re-attempting the page-level KG rerank (measured dead —
  `[[project_kg_rerank_shelved]]`); Theme A only *documents* that it's off.
- Not corpus-wide generic enrichment (measured to hurt recall).
- Not wiring the eval into CI (paid API calls; per eval design).

## Risks & mitigations

- **GPU powered off blocks Theme A.** Mitigation: B/D/E are GPU-independent;
  reorder if the host stays down.
- **Corpus edits regress recall.** Mitigation: gap-driven only; eval re-run as a
  gate; one section at a time.
- **Interface tweaks (C) are model-behavior-dependent** and may not move the
  needle. Mitigation: each change is eval-measured; revert if no lift.
