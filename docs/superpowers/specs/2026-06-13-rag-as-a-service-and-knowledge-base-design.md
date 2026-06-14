# RAG-as-a-Service & the first-class Knowledge Base

**Status:** design agreed 2026-06-13 (grilling session). Decisions are recorded as ADRs
0001–0006; vocabulary in `CONTEXT.md`. This doc organizes them into a target architecture and
a phased plan — it does not re-litigate them.

## North star

Make the wiki return an **exceptional, grounded, version-pinned [context bundle]** for a
question — built *on top of* the mature Retrieval-as-a-tool baseline, never compromising it —
over a **highly dynamic, agent-edited** knowledge base, with the **Knowledge Graph as a
first-class human Knowledge Base** as well as a retrieval signal.

## Governing principles (the lenses every decision passed through)

- **Agents are the primary customer; linked-data publishing is subordinate** (ADR-0002, Q1).
- **Stop at the assembled bundle — never synthesize answers** (ADR-0001). "Solid" = verifiable
  grounding, an assembly property, not generated prose.
- **Measurement gates everything.** No chunking/embedding/ingestion/KG change ships without
  lift on the frozen [evaluation corpus]. We currently *cannot trust our own numbers* — fixing
  that is Phase 0.
- **Human–machine parity** (ADR-0003): anything a machine can ingest/retrieve/see is browsable
  and curatable by a human, mutually. No machine-only path.
- **Advanced, not overkill** (ADR-0006); **B only where forced** (Q1); **protect baseline (i)**.
- **Model choice is a cost-governed experiment axis** (ADR-0007): every LLM in the pipeline is
  swappable and scored on the *value/cost frontier* — favor local/low-cost models, reserve
  premium models for targeted high-value slices, never default to one vendor.

## Target architecture (one screen)

- **RAG-as-a-Service** — an *in-process module* (ADR-0003) with a dedicated REST endpoint +
  MCP action. Pipeline: query → retrieve (existing BM25+dense hybrid) → fuse → **de-dup** →
  **ground** → return a ranked context bundle. No generation LLM in the path.
- **The context bundle** — ranked, de-duplicated evidence pieces, each carrying a version-
  pinned **citation handle** (`canonical_id` + version + heading-path + verbatim span + span
  content-hash). Staleness is *detectable*, not hidden.
- **Ingestion** — new content types enter as **derived pages** (ADR-0004): source binary
  retained as an attachment, `derived_from` provenance, regenerable body (reflow), human edits
  at-own-risk, version history as the recovery path. They ride every existing page rail.
- **Citation edges** (ADR-0005) — persisted, version-pinned, span-hashed grounding references
  parsed from inline body markup into a derived `citations` index (mirroring Page Graph
  wikilink projection). Graded **span-level** staleness feeds a patient, bidirectional
  (outbound/inbound) curation queue and the **self-healing loop**: churn → span-drift → agents
  re-ground via RAG-as-a-Service → re-pin → drain.
- **Knowledge Base / ontology** — KG extracted by a strong LLM, curated by humans+agents;
  projected to OWL-RL-reasoned RDF kept fresh by event-incremental sync (ADR-0006).

## Phased plan (dependency-ordered)

**Phase 0 — Trustworthy baseline + the lab bench. (This is the "small sample project".)**
The foundation everything else gates on.
- Build the [evaluation corpus]: frozen, versioned, checked-in; **section-level gold passages**;
  three-way question mix — *similarity + relational/multi-hop + boundary-straddling*. Seed from
  the existing 40 queries. Levelling it up later is a data edit, not a code change.
- Build the **bundle-quality harness**: context recall / context precision / citation
  faithfulness — with **cost & latency tracked alongside**, so model/strategy choices are made
  on the value/cost frontier (ADR-0007). Wire it into CI as a gate (frozen snapshot, reproducible).
- **Re-baseline honestly:** resolve the prod-config unknown (which dense backend is actually
  running on docker1), measure with the dormant knobs *both ways*, and **remove the misleading
  `boost=0` "TEMP DIAGNOSTIC" override** — replace it with an explicit "off pending fair trial"
  state. Root-cause the 4 all-retriever miss cases.
- **LLM posture:** Phase 0 builds the *instrument*, not the system-under-test — **freeze one
  baseline model set and measure it**: extraction on **Gemma4:12b** (the operator's deliberate
  floor — the least-capable model worth using, now that it fits local hardware; it replaces the
  abandoned gemma4-e4b) and qwen3 embeddings. Establish the baseline at this floor; later-phase
  experiments move *up* from it on the value/cost frontier (ADR-0007). Keep the gate
  deterministic / LLM-free where possible (recall = gold-overlap; citation faithfulness = hash
  compare); use a **structural** precision proxy first, adding a single **validated, pinned**
  judge model (favor local) only if that proxy is too coarse. An LLM may *draft* corpus
  questions (human-verified) — no broad model experimentation in Phase 0.
- **Exit:** a number we trust + a merge gate. Nothing in later phases ships without it.

**Phase 1 — The context bundle contract + RAG-as-a-Service surface.**
- Define the bundle data contract + the version-pinned citation handle.
- Build the dedicated REST endpoint + MCP action: assemble + de-dup + ground, **no synthesis**.
- Land the **entity-dedup-for-precision** carve-out (Q5) feeding context precision.
- **Exit:** a grounded bundle service, scored on the harness, beating the Phase-0 baseline.

**Phase 2 — Ingestion breadth (derived pages).** *(parallelizable with Phase 4 after Phase 0)*
- Derived-page subsystem: PDF/office/text → extract → derived page (source retained,
  `derived_from`), riding all page rails; reflow + at-own-risk edits + version-history recovery.
- Content-type-aware chunking *strategy selection* (flat-and-tuned for now — parent-child stays
  deferred until the harness shows boundary-straddling misses).
- **Exit:** PDFs/docs are in the bundle and curatable as pages; harness shows no regression.

**Phase 3 — Citation edges + self-healing grounding.** *(depends on Phase 1's handle)*
- Citation markup syntax → parsed `citations` edge table; span-level graded staleness reconciler.
- Bidirectional per-page stale-citation views (extend `/admin/drift`) + `list_stale_citations`
  MCP tool + for-agent projection field. The self-healing loop, end to end.
- **Exit:** grounded content that heals itself as the base churns; parity surfaces live.

**Phase 4 — Knowledge Base quality (first-class KG).** *(parallelizable with Phase 2)*
- **Upgrade the extraction model on the value/cost frontier** (ADR-0007) — a stronger *local*
  model or a low-cost API model, `ClaudeEntityExtractor` reserved for targeted high-value
  slices; gemma4-e4b was simply too small. **Targeted re-extraction** of the relational-eval
  slice first; broaden only on measured lift.
- The **fair KG-rerank trial** on the relational question subset — turn `boost` back on *only*
  if it lifts the harness.
- OWL-RL reasoning; **event-incremental entity sync** (kill the 24h lag); lazy SHACL shaping;
  human+agent curation surfaces.
- **Exit:** a KG that is a trustworthy human Knowledge Base *and* a measured retrieval signal.

## Explicitly out of scope for v1 (deferred until evidence or a real consumer)

Wiki-side answer synthesis · parent-child / hierarchical chunking · a cross-encoder reranker ·
query rewriting/decomposition · multi-hop retrieval · `owl:sameAs` external reconciliation ·
OWL-DL reasoning · extracting RAG-as-a-Service into a separate microservice. Each has a written
trigger condition; none is rejected forever.

## Phase 1 — measured plan (from the 2026-06-13 spike sweep)

The diagnostic sweep (`bin/eval/spike-*.py`, `leverage-curve.py`; full data in
`eval/bundle-corpus/baseline-notes.md`) turned Phase 1 from a guess into a measured plan.

**Lever map (realistic bundle frame, section recall):**
- *Assembly / dedup (parent-section):* moves **precision**, not recall.
- *Free re-representation* (chunk-score aggregation, whole-section embed): no recall move.
- *Reranking:* a **~4B LLM reranker (`gemma4:e4b`)** is the model — **+0.15 @5** (0.24→0.40),
  ~1.7 s, fine on the agent path. Fast cross-encoder (`bge-reranker-v2-m3`, 40 ms) lacks the
  quality; 1.5B collapses; 9B best @1–2 but 3–4.5 s with no bundle-depth gain.
- *First-stage recall is the **binding ceiling*** — the gold section (ranked ~5th-in-page by
  the small `qwen3-embedding-0.6b`) must reach the shortlist or no reranker can recover it.
  **Embedder verdict (0.6B vs 4B) pending.** If 4B lifts it, that's the *latency-free* recall
  lever and the reranker then orders against a better shortlist.

**Architecture:** retrieve (hybrid; stronger embedder if 4B wins) → **per-page shortlist**
(top-S sections per retrieved page, so rank-~5 gold sections survive a flat global cut) → one
**4B listwise rerank** → parent-section expand + dedup + version-pinned citations → top-N bundle.

**Realistic recall target:** ~0.40–0.51 @ bundle today (reranker alone); higher if the 4B
embedder raises the first-stage ceiling. Precision + grounding come from the bundle contract
regardless of the recall number.

**Deferred ceiling-raisers:** chunk-level 4B re-embed (production-faithful confirmation), and
**fine-tuning** a small reranker/embedder on the eval-corpus golds — the real "tune it to our
task" lever (where the gemma4-style instinct actually applies, via training not prompting).

### Exploration concluded — 2026-06-13

The directional sweep is **done** (data in `eval/bundle-corpus/baseline-notes.md`; tools in
`bin/eval/spike-*.py` + `leverage-curve.py`). Final verdicts:

- **First-stage: keep `qwen3-embedding-0.6b` + the query instruction prefix** (already wired in
  `EmbeddingModel`). 4B is a *regression* at production granularity (max-chunk 0.6B 0.41@5 /
  0.54@12 vs 4B 0.27@5 / 0.49@12 — bigger ≠ better). Cheap structural tweaks (re-aggregation,
  heading-prepend) are **null**. True first-stage recall is **~0.41@5 / 0.54@12** (earlier
  proxy numbers understated it by omitting the query instruction prefix).
- **Ranking: a ~4B LLM reranker** (`gemma4:e4b`, ~1.7 s, `think:false`) is the one positive recall
  lever — modest, best at tight bundle sizes, fine on the agent path. Cross-encoder (40 ms) lacks
  quality; 1.5B collapses; 9B too slow with no bundle-depth gain.
- **Shortlist: per-page** (each retrieved page contributes its top-S sections, preserving the
  0.97 page-recall), not a flat global cut.
- **Bigger first-stage gains require expensive structural bets** — full LLM-contextual embeddings,
  ColBERT late-interaction, or fine-tuning on the corpus golds — **banked as deliberate,
  harness-gated follow-ons** (de-risked: the cheap shortcuts are proven null; a better GPU is the
  enabler for ColBERT/fine-tuning).

**Phase-1 build (architecturally simple, rides the existing stack):**
`retrieve (hybrid, 0.6B + instruction) → per-page shortlist (top-S sections/page) → one 4B
listwise rerank → parent-section expand + dedup + version-pinned citation handles → top-N context
bundle`, via a dedicated REST endpoint + MCP action (assemble + ground, **no synthesis**), scored
on the bundle-quality harness.

## Phase 1 — SHIPPED (2026-06-14), and what the measurements actually said

The bundle shipped (`GET /api/bundle?q=`, `assemble_bundle` MCP, `com.wikantik.knowledge.bundle.*`).
But the measure-upstream-first sweep **overturned the spike-sweep's lever ranking** — full record in
`eval/bundle-corpus/baseline-notes.md`:

- **The 4B listwise reranker is dead.** Its apparent "no lift" was input-order anchoring masking a
  *bad* relevance judge (shuffled-input recall collapses to a third of dense). It ships **default
  OFF** and is not in the realized pipeline. HyDE was near-null; doc2query actively hurt.
- **The real levers were upstream, and cheap.** (1) A `ContentChunker` **heading-fidelity bug** —
  merge-forward stole the first section's heading, so early sections were unfindable AND mis-cited;
  fixed (+ fragment floor + overlap). (2) **Contextual document embeddings** from frontmatter
  (`Page | Cluster | Section | Summary` prefix) — the single biggest lever, global section recall@12
  **0.60 → 0.74**, zero LLM. (3) A **global dense-chunk** candidate source (no page pre-select) so
  the bundle realizes the ceiling rather than the page-gated ~0.685.
- **Realized live `/api/bundle` recall@12 0.706** (from 0.500 at the investigation's start, +41%),
  measured end-to-end through the deployed endpoint (`bin/eval/spike-api-bundle.py`).

Lesson banked: the bundle contract was the right product, but the recall came from chunking +
embedding correctness, not from a reranking stage. Config: `wikantik.bundle.*`, `wikantik.chunker.*`.
