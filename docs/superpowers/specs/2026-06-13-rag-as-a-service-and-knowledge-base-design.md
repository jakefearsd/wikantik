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
