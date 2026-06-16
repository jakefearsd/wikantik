# Phase 4 — Knowledge Base Quality (first-class KG)

**Status:** Approved design (2026-06-16). Final phase of the RAG-as-a-Service program
(`docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md`).
**Governing ADRs:** `0002` (KG is a first-class human Knowledge Base + retrieval signal),
`0007` (model selection is a cost-governed axis — never default to premium).

## Why this phase has been hard to land

Phase 4 has two halves. The **human-KB** half (the KG as a trustworthy human knowledge base —
curation surfaces, OWL-RL, event-fresh sync) is conceptually clear. The **retrieval-signal** half
(does the KG measurably improve retrieval?) has been stuck in a three-way logjam:

> Can't trust the rerank trial **(eval validity)** → unless coverage is high **(coverage)** →
> which needs a model commitment + a re-extraction spend **(model/cost)** → which won't be made
> until the trial proves value **(coverage again)**.

The unlock is to **stop trying to answer "is our KG good enough?" and instead answer "would a
*good* KG help — on a slice small enough that we can afford to make it genuinely good?"** A
**ceiling experiment, not a rollout.** Measure the upside on a cheap, trusted slice before paying
for the corpus-wide investment — the same measure-upstream-first discipline that cracked Phase 1's
recall levers.

## Current state (verified 2026-06-16)

- KG rerank is **OFF**: `wikantik.search.graph.boost = 0.0` (skips wiring entirely;
  `SearchWiringHelper`). The rerank machinery exists — `GraphRerankConfig` (boost, `max-hops`,
  `mention.confidence.floor`, human/machine edge-tier weights), `KgGraphTraversal`, `MentionIndex`.
  It boosts candidate sections by **KG-graph proximity** (within `max-hops`) to the entities the
  query mentions, weighted by mention confidence and edge tier.
- The eval corpus **already has the relational slice**: `eval/bundle-corpus/queries.csv` =
  38 SIMILARITY · **17 RELATIONAL** · 13 BOUNDARY, each with gold `canonical_id` + `heading_path`.
- KG **mention coverage ≈ 7%** of pages — the genuinely thin part.
- Extraction model: `wikantik.knowledge.extractor.ollama.model = gemma4-graph:12b`
  (`gemma4:e4b` was too small). A `ClaudeEntityExtractor` exists, reserved per ADR-0007 for
  targeted high-value slices.
- Harness: `bin/eval/run-baseline.py` + the `spike-*.py` family score recall@k over the corpus,
  reading the `category` column — so the relational slice is already scoreable.

## Two tracks

Phase 4 splits into two largely-independent tracks. **Track A is the unlock and this spec's focus.**
**Track B stands regardless of Track A's outcome** (ADR-0002: the KG's human-KB role holds even if
the retrieval-signal hypothesis is falsified) and can be a separate follow-on.

---

## Track A — KG retrieval-value: a ceiling-then-frontier spike

**This is an agent-run experiment whose deliverable is a *decision*, not necessarily a shipped
feature.** It is driven autonomously against a **local/dev deployment — never prod** — with two
small human touchpoints. The retrieval-signal rollout (corpus-wide re-extraction + prod boost-on)
happens *only if* the spike justifies it, as a separately-gated later step.

### A0 — Validate the slice (empirical, not subjective)

The rigor lives here: if the slice is sloppy, every downstream number is meaningless. Validation is
**mechanical**, not judgment:

1. **Headroom filter (objective).** Run all 17 relational questions through the *current* (KG-off)
   retrieval. Keep only the questions whose gold section **misses** the top-k — those are the only
   ones with room for the KG to help. A question similarity already nails cannot show KG lift either
   way, so it is dropped (or retained as a labelled control, not part of the lift measurement).
2. **Reachability rule (reproducible).** Of the misses, keep those where the gold section is
   actually reachable by the rerank: the query mentions ≥1 KG entity, and the gold section's page is
   within `max-hops` of those entities in the KG graph. This rule is written down and applied
   uniformly — no per-question vibes.
3. **Output:** the **KG-trial slice** + a **per-question rationale table** (question → similarity
   hit/miss → entities → reachable? → in/out + why).

**Human touchpoint #1:** a ~5-minute spot-check of *borderline* labels in that table — a bias-guard,
not a labelling session.

### A1 — Ceiling: does a *good* KG help at all?

1. Re-extract **only the pages the trial slice touches** (dozens, not 1,200) with the **best
   available extractor** — best local first; a premium API model (`ClaudeEntityExtractor`) only with
   explicit go-ahead, cost-bounded to the tiny slice (ADR-0007 permits premium on a targeted
   high-value slice).
2. Turn `graph.boost` on; run the existing harness on the trial slice; measure **recall lift,
   boost-on vs boost-off**.
3. **Gate:**
   - **Lift** → the KG-as-retrieval-signal hypothesis is *real*; proceed to A2.
   - **No lift even with great coverage** → the hypothesis is *falsified for this corpus*. Stop the
     retrieval-signal track, keep the KG as a human KB (Track B), document the finding. **This is a
     pre-committed, fully-acceptable outcome.**

**Guardrail (the Phase-1 reranker lesson):** a rerank can only change recall@k by pulling a gold
section from *outside* the top-k *into* it. Score at a `k` meaningfully smaller than the candidate
pool the rerank reorders (e.g. recall@12 over a top-N dense-chunk pool), or the trial is rigged to
show a false "no lift."

### A2 — Cost frontier: which model is the sweet spot? *(only if A1 shows lift)*

Re-extract the **same fixed slice** with progressively cheaper models (e.g. `gemma4-graph:12b`, a
mid local model, a low-cost API). Per model, measure (a) the fraction of the ceiling's lift it
recovers and (b) its extraction cost (tokens / wall-time). Output: a **cost/quality curve** + a
**projected corpus-wide rollout cost** (extrapolated from per-page cost × corpus size).

**Human touchpoint #2:** go/no-go on any premium-API spend (A1's best model and/or an A2 rung).

### A3 — Decision + conditional rollout

Pick the cost-effective model. If projected rollout value/cost is worth it, the corpus-wide
re-extraction + prod boost-on is a **separate, later, gated step** (with a full-corpus no-regression
check). Otherwise, the documented finding *is* the deliverable.

### Metrics & environment

- **Reuse the existing bundle-corpus harness 100%.** Primary metric: relational-slice section
  recall@k, boost-on vs boost-off. **No-regression guard:** the full corpus (similarity + boundary)
  must not regress when boost is on — the rerank must not rob Peter to pay Paul.
- **Cost metric:** extraction tokens + wall-time per model on the slice.
- **Environment:** a local/dev deployment with the extraction models. **Never prod.**

### Integrity guards (an agent grading its own homework needs these)

- The headroom filter is **objective**; the reachability rule is **written + reproducible**;
  per-question work is **shown**.
- **"No lift" is pre-committed as a valid result** — the goal is to *land the decision*, not to make
  the KG look good. Falsifiability is the point.
- A human spot-checks borderline labels.

---

## Track B — Human-KB quality *(scoped; independent of Track A; a lighter follow-on)*

Stands regardless of A's outcome. Items, smallest-surface-first:

- **Event-incremental entity sync** — kill the 24h lag. Entity graphs currently reconcile only
  nightly (`OntologyRebuildScheduler`) because no KG-change events exist; emit them on
  extraction/curation so entity graphs refresh like page graphs already do.
- **OWL-RL reasoning** — move beyond RDFS `subClassOf` to OWL-RL (per ADR-0006) where it earns its
  cost.
- **Lazy SHACL shaping** + the existing human/agent **curation surfaces** (`/admin` KG curation,
  `/api/page-knowledge/*`) — extend only where a real curation workflow demands it.

Track B is deliberately under-specified here — it is a separate effort that does not block Track A's
decision. It gets its own plan if/when prioritised.

## Out of scope / deferred

- **Corpus-wide re-extraction** — only if Track A justifies it (separate gated step).
- **Prod boost-on** — only after the spike + a full-corpus no-regression check.
- **Premium model as the *default* extractor** — ADR-0007; premium is slice-only.
- A deep Track-B build — scoped above, planned separately.

## Validation / testing

- The spike **is** the validation — the harness numbers and the falsifiable gate.
- Any **code** changes the spike needs (e.g. verifying the rerank wires correctly at `boost > 0`,
  adding per-model extraction cost logging, a slice-scoped re-extraction entry point) follow normal
  TDD.
- Reproducibility: the slice-validation script, the per-model extraction commands, and the harness
  invocations are all recorded so the result can be re-run and audited.

## Human touchpoints (the entire ask of the user)

1. ~5-min spot-check of borderline slice labels (A0).
2. Go/no-go on premium-API extraction spend (A1/A2).
3. Review the spike results + the rollout decision (A3).
