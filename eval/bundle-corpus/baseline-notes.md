# Phase 0 baseline notes

Root-cause analysis of all-retriever misses from the 2026-06-03 evaluation run, and a frozen-baseline placeholder for Tasks 9 & 11.

---

## All-retriever miss-case root-causes (2026-06-13)

Source report: `eval/report-qwen3-embedding-0.6b-2026-06-03-current-corpus.txt`  
Source corpus: `eval/retrieval-queries.csv` (current, post-2026-06-03 revision)

The four `ideal_page` names in the June 3 report (`JspwikiDeployment`, `EmbeddingsVectorDB`,
`TestDrivenDevelopmentGuide`, `DockerSetup`) are **stale identifiers** from a prior version of the
query corpus that was revised between the run date and today. In three of the four cases the page
did not exist at run time under that slug; in one case the slug exists but the current corpus CSV
renamed it to a better target. Each is classified below.

---

### JspwikiDeployment

**Query:** `how do I deploy the wiki locally`  
**Category:** indirect  
**Stale slug in report:** `JspwikiDeployment`  
**Current CSV target:** `BuildingAndDeployingLocally`

**Classification: MISSING-CONTENT (stale ground truth)**

No file named `JspwikiDeployment.md` exists under `docs/wikantik-pages/`. The corpus was
subsequently corrected to point to `BuildingAndDeployingLocally.md`, which does exist and directly
answers the query (it is titled "Building and Deploying Locally", type `runbook`, cluster
`agent-cookbook`, summary: "One-screen canonical procedure for building the WAR and redeploying to
the local Tomcat"). The retriever returned zero hits because the gold target did not exist in the
index at run time — the query itself is sensible and the correct target page is retrievable. This
is purely a stale-ground-truth miss, not a retrieval failure.

---

### EmbeddingsVectorDB

**Query:** `which embedding model should we pick for local RAG`  
**Category:** synonym-drift  
**Stale slug in report:** `EmbeddingsVectorDB`  
**Current CSV target:** `LocalRAG`

**Classification: MISSING-CONTENT (wrong ground truth)**

`EmbeddingsVectorDB.md` exists in the corpus, but it is the wrong target for this query.
`EmbeddingsVectorDB` covers HNSW vs IVF indexing strategies and pgvector/Qdrant configuration
— it mentions embedding models only in passing (no model selection guidance, no local-hardware
sizing). The query asks for model selection guidance for a local RAG stack, which is answered by
`LocalRAG.md` (section "Component choices > Embedding model" lists BGE, e5, gte, Nomic, Jina;
section "Pragmatic configuration" gives a concrete 2026 default: `BAAI/bge-large-en-v1.5`).

The ground truth was corrected in the CSV revision to `LocalRAG`. The June 3 miss is a
stale-ground-truth miss: the retriever may have surfaced `EmbeddingsVectorDB` at some rank, but
that page genuinely doesn't answer the query — so a miss against that target is actually correct
retriever behavior.

---

### TestDrivenDevelopmentGuide

**Query:** `test driven development`  
**Category:** general  
**Stale slug in report:** `TestDrivenDevelopmentGuide`  
**Current CSV target:** `TestDrivenDevelopment`

**Classification: MISSING-CONTENT (stale ground truth)**

No file named `TestDrivenDevelopmentGuide.md` exists under `docs/wikantik-pages/`. The actual
page is `TestDrivenDevelopment.md` (title: "Test Driven Development (TDD) in Wikantik"). The
corpus CSV was corrected to use the existing slug. This is a stale-ground-truth miss: the page
and its content are fully on-topic for the query ("test driven development" appears verbatim in
the title), so the retriever very likely ranked it — just not under the slug the evaluator was
looking for.

**Vocabulary note:** The page title and body repeat the exact query string multiple times
(`Test Driven Development`, `TDD`), so there is no vocabulary gap. BM25 should surface this
trivially under the correct slug. The miss is entirely an evaluation-harness artifact.

---

### DockerSetup

**Query:** `setting up docker for development`  
**Category:** general  
**Stale slug in report:** `DockerSetup`  
**Current CSV target:** `DockerDeployment`

**Classification: MISSING-CONTENT (stale ground truth)**

No file named `DockerSetup.md` exists under `docs/wikantik-pages/`. The actual page is
`DockerDeployment.md` (title: "Professional Wikantik Deployment with Docker"), which covers
environment variables, volume configuration, docker-compose setup, and backup — exactly the
scope of the query. The CSV was corrected to `DockerDeployment`. This is a stale-ground-truth
miss. The page title uses "Deployment" rather than "Setup", which introduces a minor
vocabulary gap between the query ("setting up") and the title, but the body uses "docker-compose",
"environment variables", "configuration", and "docker run" — sufficient for BM25 and dense
retrieval. The mismatch is slug-level only.

---

### Boundary-case candidates

None. All four misses are classified MISSING-CONTENT (stale or wrong ground truth). No CHUNKING
classifications were made, so there are no boundary-case candidates for the bundle corpus from
this miss set.

---

## Live baseline v1 — 2026-06-13

Measured against the **live retrieval stack** (`/api/search` on the running deployment) over the
54-question corpus, via `bin/eval/run-baseline.py`. Hybrid retrieval (BM25 + dense), KG rerank off
(`graph.boost = 0`).

| category   |  n | section recall | page recall | prec@5 |
|------------|---:|---------------:|------------:|-------:|
| SIMILARITY | 38 |          0.368 |       0.974 |  0.168 |
| RELATIONAL |  9 |          0.500 |       0.944 |  0.222 |
| BOUNDARY   |  7 |          0.571 |       1.000 |  0.200 |
| **OVERALL**| 54 |      **0.417** |   **0.972** |  0.181 |

**Headline:** page recall 0.97 (retrieval finds the right page) vs **section recall 0.42** (the
answering *section* makes the bundle <half the time). That gap is the Phase-1+ target.

- **Model set:** embeddings `qwen3-embedding-0.6b` (1024-dim); extraction `gemma4-graph:12b`
  (think:false). KG rerank off pending the Phase-4 fair trial.
- **Prod dense backend:** `lucene-hnsw` (confirmed from `.env.prod`). This run used the **local-dev
  deployment** (`inmemory` backend) — parity-proven within 0.02 nDCG of `lucene-hnsw`, so the page
  numbers are representative; re-run against `lucene-hnsw` to freeze the final figure.

**Caveats (why this is "v1", not the frozen gate number):**
- *Section* recall is a **text-containment proxy**: `/api/search` returns each page's top
  contributing-chunk *texts* (no heading-path), and a chunk from the gold section is a substring of
  that section's text — faithful, but it measures "did a chunk from the gold section rank into the
  page's top contributions," which is exactly the bundle semantics.
- `prec@5` is *page-level* and capped by single-gold questions (1 gold of 5 slots → 0.2 ceiling);
  the real signal-to-noise precision is a *section-level* metric that lands with RAG-as-a-Service.
- The reproducible CI gate (Task 8 real-corpus tier, Testcontainers) will re-measure via the
  heading-path-exact `BundleEvalRunner`; expect small differences from this live proxy.
- **Date frozen:** TBD.

---

## Leverage analysis — 2026-06-13 (how hard to move 0.42?)

`bin/eval/leverage-curve.py` ranks each gold page's chunks by dense (qwen3) similarity to
the query and reports section recall @ k chunks-per-page (exact heading-path match):

| cat | @1 | @3 | @5 | @10 | @20 | @all | unreachable |
|-----|----|----|----|-----|-----|------|-------------|
| OVERALL | 0.19 | 0.41 | 0.63 | 0.78 | 0.87 | 0.90 | 0.10 |

**Findings:** (1) chunks are ranked fine but *capped* at 5 — @5 0.63 vs @all 0.90, so depth
alone buys +0.24. (2) **Parent-section return (small-to-big)** captures that ~0.90 ceiling at
tight-bundle precision — the high-leverage, cheap Phase-1 lever (the deferred parent-child
trigger, now fired). (3) ~10% "unreachable" (heading mismatch / not chunked) is the hard floor.
(4) @1 0.19 → weak within-page ranking; a reranker is a *precision*, not recall, lever (deferred).

Note: this exact heading-match measure (@5 0.63) is higher than the v1 text-overlap proxy
(0.42); true section recall is ~0.6, ceiling ~0.90. The Testcontainers gate will pin the exact
production figure.

---

## Phase-1 spike — parent-section bundle (2026-06-13)

`bin/eval/spike-parent-section.py` simulated the proposed bundle on live data (real
/api/search candidate pages + dense section ranking, deduped):

- **Flat-global top-N sections:** recall 0.52 @ N=20 (global ranking crowds the gold section out).
- **Per-page-allocated top-S sections:** recall 0.60 @ S=5 (≈ current).
- Both **plateau ~0.60** at usable bundle sizes; the leverage curve's 0.87 needs ~20 chunks/page
  (a sprawling bundle). `sec_MRR ≈ 0.20` → the gold section ranks ~5th within its page.

**Conclusion (corrects the earlier "cheap parent-section win"):** assembly/dedup tightens and
cites the bundle but does NOT move recall — section recall is capped ~0.60 by **ranking quality**.
The recall lever is a **reranker** (cross-encoder) to lift the gold section toward rank 1-2;
candidate-set ceiling ~0.87-0.90. Reranker moves from "deferred precision lever" to the lead
Phase-1 recall lever. Parent-section/citation contract still ships (precision + grounding), but
the number moves with reranking.

## Free re-representation — does NOT move recall (2026-06-13)

`bin/eval/spike-rerank.py`: re-scoring sections without a new model — max 0.632@5 (current),
mean/top2-mean within noise, whole-section qwen3 embedding *worse* (0.603@5). No free lunch:
the gold section ranks ~5th and better aggregation/section-embedding doesn't fix it. The recall
lever is a **cross-encoder reranker** (or a stronger first-stage embedder) — measure next.

## LLM-as-reranker ceiling probe (2026-06-13) — reranking IS the recall lever

`bin/eval/spike-llm-rerank.py`: qwen3.5:9b listwise rerank of each gold page's sections
(per-page frame) vs the dense-max baseline:

| method | @1 | @2 | @3 | @5 |
|--------|----|----|----|----|
| dense max (current) | 0.191 | 0.309 | 0.426 | 0.632 |
| LLM reranker        | 0.324 | 0.515 | 0.588 | 0.691 |

Reranking roughly doubles recall@1-2 — the lift is at *tight* bundle sizes (so it buys recall
AND precision). **Latency p50 3.0s / p95 4.5s per call → an LLM reranker is unshippable** (the
"don't ship this" baseline; the operator's latency instinct was correct). Resolution: a
cross-encoder (bge-reranker-v2-m3 via TEI) should match/beat this recall at ~tens of ms.
Next: measure cross-encoder recall + p50/p95 latency on the GPU to close the loop.

## Cross-encoder reranker measurement (2026-06-13) — latency solved, quality insufficient

`bin/eval/spike-tei-rerank.py` vs `bge-reranker-v2-m3` on the GPU (headings in passages):

| method | @1 | @2 | @3 | @5 | latency |
|--------|----|----|----|----|---------|
| dense max (current) | 0.191 | 0.309 | 0.426 | 0.632 | — |
| LLM (qwen3.5:9b)    | 0.324 | 0.515 | 0.588 | 0.691 | 3-4.5s |
| cross-enc (bge-v2-m3)| 0.147 | 0.309 | 0.441 | 0.559 | **p50 40ms / p95 108ms** |

**Latency is NOT the blocker** — fast rerankers run ~40ms (operator's latency concern resolved).
The blocker is reranker **quality**: bge-reranker-v2-m3 only ties dense and trails the LLM ceiling
badly on identical input. The lever is real (LLM proves it) but the cheap cross-encoder lacks the
intent-discrimination. Open: sweep stronger fast rerankers (Qwen3-Reranker 0.6B/4B, jina-v2,
mxbai) or a small-LLM reranker (~0.5-1s). Caveat: per-page frame (intra-document section pick)
may disadvantage cross-encoders vs the LLM's reasoning.

## Reranker frontier (2026-06-13) — the 4B sweet spot

Per-page-frame gold-section recall@S, listwise rerank (dense baseline 0.19/0.31/0.43/0.63):

| reranker | @1 | @2 | @3 | @5 | latency |
|----------|----|----|----|----|---------|
| cross-enc bge-v2-m3 | 0.147 | 0.309 | 0.441 | 0.559 | p50 40ms |
| qwen2.5:1.5b        | 0.221 | 0.265 | 0.412 | 0.500 | p50 0.4s |
| **gemma4:e4b (4B)** | 0.088 | 0.412 | **0.603** | **0.750** | **p50 1.0s** |
| qwen3.5:9b          | 0.324 | 0.515 | 0.588 | 0.691 | p50 3-4.5s |

**Conclusion:** the fast cross-encoder lacks quality; the 1.5B collapses; the **4B (gemma4:e4b)
is the sweet spot** — recall@3 0.60 (+0.17), @5 0.75 (+0.12), beating the 9B at bundle depths,
at ~1s (acceptable on the agent path). The 9B wins @1-2 (single-best precision) but is slower
with no bundle-depth gain. Phase-1 recall lever = a ~4B LLM reranker as the bundle's ranking
step. Operator's latency skepticism held: the trivial-latency cross-encoder doesn't work; the
quality needs an LLM; the 4B is the usable compromise. Caveat: per-page frame — production
reranks the global candidate set (~30-60 sections, one listwise call ~1.5-2.5s).

## Production-frame rerank — the binding ceiling is first-stage recall (2026-06-13)

`bin/eval/spike-global-rerank.py` — rerank the real /api/search candidate set (not the gold
page in isolation):

| frame (gemma4:e4b, 1-call rerank) | @3 | @5 | @8 | @12 |
|-----------------------------------|----|----|----|----|
| per-page isolated (optimistic) | 0.60 | 0.75 | — | — |
| flat-global-30 + rerank | 0.34 | 0.43 | 0.44 | 0.52 |
| top-5/page-of-8 + rerank | 0.29 | 0.38 | 0.50 | 0.51 |
| dense, no rerank (top-5/page) | 0.19 | 0.24 | 0.36 | 0.47 |

**Conclusions:** (1) the per-page 0.75 was a frame artifact — realistic one-call bundle recall
is ~0.40@5 / ~0.51@12. (2) Reranking gives a real but modest lift (@5 0.24→0.40) at ~1.7s,
best at tight bundles. (3) The binding ceiling is **first-stage recall into the shortlist** —
qwen3-0.6b ranks the gold section ~5th in its page / deeper globally, so it often never reaches
the reranker; widening the shortlist barely helps (dense @12 plateaus ~0.47). **The bigger
untested lever is a stronger first-stage embedder** (latency-free, offline re-embed), which
raises the shortlist ceiling the reranker then orders. Phase 1: reranker (4B, +0.15@5) AND a
stronger embedder — the embedder is likely the larger lever.

## First-stage embedder 0.6B vs 4B (2026-06-13) — modest, and section-level undersells

`bin/eval/spike-embedder-4b.py` (section-level, queries w/ instruction prefix):

| embedder | @3 | @5 | @8 | @12 |
|----------|----|----|----|----|
| qwen3-emb 0.6B | 0.147 | 0.250 | 0.294 | 0.382 |
| qwen3-emb 4B   | 0.162 | 0.250 | 0.382 | 0.500 |
| prod max-chunk 0.6B (ref) | — | ~0.24 | — | ~0.47 |

4B helps **at depth** (@12 +0.12, raising the reranker's shortlist ceiling) but is flat at @5.
CAVEAT: section-level is a proxy and undersells — section-0.6B@12 (0.38) < production
max-chunk-0.6B (0.47). The faithful test is **max-chunk-4B vs max-chunk-0.6B** (chunk-level 4B
embeds, not yet run). Verdict: directionally promising as a ceiling-raiser, not certified; needs
the chunk-level run for production certainty.

## Faithful embedder verdict + instruction-prefix correction (2026-06-13)

`bin/eval/spike-embedder-4b-chunk.py` (max-chunk, production granularity, instruction prefix):

| embedder | @3 | @5 | @8 | @12 |
|----------|----|----|----|----|
| 0.6B (production) | 0.279 | 0.412 | 0.485 | 0.544 |
| 4B | 0.147 | 0.265 | 0.368 | 0.485 |

**0.6B beats 4B at every cutoff — do NOT switch (4B is a regression).** Section-level made 4B
look good at depth; that was a proxy artifact. Bigger embedding model != better (matches the
grand-finale qwen3-0.6b > bge-m3 result). **Correction:** earlier spikes (leverage-curve,
global-rerank) embedded the query WITHOUT the instruction prefix and thus understated recall —
with the prefix (production behavior) max-chunk-0.6B is 0.41@5 / 0.54@12, not ~0.24@5. The
model lever is dead; structural changes (heading-prepend / contextual embeddings) are the
remaining first-stage lever.

## Phase-1 LIVE bundle measurement (2026-06-13) — `bin/eval/spike-bundle-live.py`

Faithful end-to-end run of the shipped `DefaultBundleAssemblyService` pipeline against the
live stack: `/api/search` (top-20 pages) → per-page section shortlist (best chunk per
heading-path, top-5/page = `SectionAssembler`) → **listwise `gemma4:e4b` rerank** (think:false,
JSON `{"ranking":[…]}`) → dedup by (slug, heading-path) → top-N. Gold-section recall@N, dense
order vs reranked order on the **same candidate set** (isolates the reranker's contribution).

| method | recall@5 | recall@12 |
|--------|----------|-----------|
| dense baseline (global max-chunk, ref) | 0.412 | 0.544 |
| bundle dense-order (per-page shortlist) | 0.389 | 0.500 |
| bundle reranked (gemma4:e4b) | **0.389** | **0.500** |

rerank latency p50/p95 = 1.5s (n=54).

**Two findings, both robust:**

1. **The 4B reranker is an ordering lever, not a recall lever — at the bundle's operating point
   it changes nothing.** rerank == dense to three decimals at both cutoffs, every category. The
   model is *not* broken (a direct probe returns a correct ranking, e.g. `{"ranking":[2,1,3]}`
   putting the deploy passage first). The reason is structural: `recall@N` is set-membership in
   the top-N, and reranking *reorders* the top-N without changing *which* gold sections occupy it.
   The reranker's value is top-of-bundle ordering/precision (what the consuming agent reads first),
   and it never regresses (degrades safely to dense). This **confirms** the banked exploration
   verdict: first-stage recall is the binding ceiling.

2. **The per-page shortlist (`sectionsPerPage=5`) costs a little recall vs unbounded global dense**
   (0.389 vs 0.412 @5; 0.500 vs 0.544 @12) — a gold section ranked 6th+ on its page is dropped
   before the global top-N. The bundle's win is *structural* (dedup, version-pinned citations,
   parent-section grouping, ordering), not raw recall. If recall is paramount, raise
   `sectionsPerPage` or lift the per-page cap; that is the lever, not the reranker.

Caveat: this spike's first stage is the production *hybrid* `/api/search` page pre-selection
(not pure dense), re-scored with the 0.6B chunk vectors — a faithful replica of the runtime
pipeline, not a controlled pure-dense A/B. The directional conclusions above hold regardless.

## Recall-lever investigation (2026-06-14) — chunker fix, contextual embeddings, dense-chunk bundle

Measure-upstream-first sweep. All gains cheap/local, no premium models. Global section recall@12
(diagnostic `bin/eval/spike-recall-ceiling.py`, sublist match) and realized bundle recall.

**Chunker heading-fidelity bug (root cause).** Merge-forward stole the FIRST section's heading_path;
first-H2/early sections were unfindable (7/68 golds had NO matching chunk despite indexed content) and
citations mis-anchored. Force-emit the buffer at each heading boundary + a fragment floor (`fragment_floor_tokens`=24:
sub-floor sections merge forward adopting the DESTINATION heading, killing 1-19-tok fragment noise).
Global recall@12 0.574 → 0.603; no-match bucket 7 → 0.

**Contextual document embeddings (the big lever).** `EmbeddingTextBuilder.forDocument` prepends frontmatter
context (`Page: {title} | Cluster: {cluster} | Section: {heading}` + summary) before embedding — the
production "raw" already had heading_path, so the win is the page-level title/cluster/summary. Query side
unchanged (instruction prefix). **Global recall@12 0.603 → 0.735, @20 0.662 → 0.809, @5 0.471, none 0.**
Template (no-LLM, from frontmatter) — the research predicted our structured metadata is exactly the
disambiguation missing from raw chunks. `bin/eval/spike-contextual-embed.py`.

**Dead levers (measured & rejected):** doc2query HURTS (-0.13@12, max-combine floods top with on-topic
non-answer competitors; `spike-doc2query.py`); HyDE near-null at depth (`spike-hyde-recall.py`); the
gemma4:e4b listwise reranker is a BAD judge (shuffled-input 0.139 vs dense 0.583 — its "no lift" was
input-order anchoring; `spike-rerank-anchor.py`), default OFF.

**Bundle realization.** Per-page cap `sections_per_page` 5 → 20 (once contextual made section scores
discriminative the cap-of-5 was the binding constraint): realized bundle recall@12 0.602 → 0.685. Then
the **dense-chunk source** (`DenseChunkSectionSource`): retrieve top-K chunks globally (no page pre-select),
group to sections — the global path realises the ceiling where the page-gated hybrid drops sections on
pages outside the top-20. Chunk size near-optimal (240 worse than 120 @12); overlap marginal/flat live.

**LIVE /api/bundle end-to-end (deployed dense-chunk + contextual + overlap, `bin/eval/spike-api-bundle.py`):
recall@5 0.456 / @12 0.706** — lands against the 0.735 ceiling; the dense-chunk path closed the
page-pre-select gap. Realized bundle @12 trajectory: 0.500 → 0.583 → 0.602 → 0.685 → 0.706 (+41%).
Op note: the `inmemory` dense backend needs a reload after a re-index (restart) for the bundle to hydrate;
prod `lucene-hnsw` reads from DB and is unaffected.

## MMR rerank measurement gate (Phase 1, 2026-07-10)

The recall@12 no-regression gate is a MANUAL run — the bundle eval real-corpus tier is
Docker/embedding-snapshot-gated and non-blocking, so this mirrors how the 0.74 baseline was set.

Procedure (against a local deployment with a live dense index):
1. Baseline: `wikantik.bundle.rerank.chain` UNSET. Run the corpus (queries.csv) through the live
   bundle and record overall section recall@12 → the control number (expect ~0.74).
2. Treatment: set `wikantik.bundle.rerank.chain = mmr`, redeploy, re-run the same corpus.
3. Record both below. ACCEPT the stage only if treatment recall@12 >= control (no regression)
   AND the diversity metric improves (distinct-slug count among the top-12, averaged over the
   corpus, goes up). REJECT and record in the dead-levers list otherwise.
4. Sweep lambda in {0.5, 0.7, 0.9} and keep the best non-regressing point.

| Config | recall@12 | mean distinct-slug @12 | p95 assemble latency | verdict |
|--------|-----------|------------------------|----------------------|---------|
| chain unset (control) |  |  |  | baseline |
| chain=mmr, lambda=0.7 |  |  |  |  |
| chain=mmr, lambda=0.5 |  |  |  |  |
| chain=mmr, lambda=0.9 |  |  |  |  |

## Scheduled runs (Phase 1, 2026-07-10)

A scheduled `BundleEvalScheduler` now persists recall@12 to the `bundle_eval_run` table and logs a
regression WARN below the `thresholds.properties` floors. Off by default
(`wikantik.bundle.eval.interval.hours = 0`); enable in a deployment with a live index. See
`docs/agents/bundle-eval-runbook.md`.

## Metadata-boost rerank measurement gate (Stage 2, 2026-07-10)

Confidence is a QUALITY signal, not relevance — this stage is a bounded tie-breaker. Success =
NO recall@12 regression (a neutral corpus result is the expected, acceptable outcome for a
tie-breaker); the verified-above-stale behavior is proven by unit test on a constructed equal-score
set. Manual run against a live-index deployment with a populated page_verification table:

Treatment now composes: test `chain = mmr, metadata-boost` against control `chain = mmr` — the
rank-based boost preserves MMR's ordering among equal-confidence sections and only swaps
confidence-differing near-neighbours. Sweep positions in {1, 1.5, 3}.

| Config | recall@12 | p95 assemble latency | verdict |
|--------|-----------|----------------------|---------|
| chain = mmr (control) |  |  | baseline |
| chain = mmr, metadata-boost (positions 1.5, window 24) |  |  |  |
| chain = mmr, metadata-boost (positions 3, window 24) |  |  |  |

## Knee-cutoff measurement gate (2026-07-10)

Knee is a PRECISION/coverage-sharpening lever, not a recall lever — cutting the tail can only lower
recall@12 (fewer sections returned). Success = recall@12 does not regress meaningfully while the
coverage signal sharpens (weak-coverage bundles get shorter). Manual run against a live index:

1. Control: `wikantik.bundle.knee.enabled = false`. Record recall@12 + mean sections/bundle.
2. Treatment: `= true`, sweep `retain_ratio` in {0.4, 0.5, 0.6}. Record recall@12 + mean sections/bundle
   + coverage-confidence distribution.
3. ACCEPT the largest retain_ratio where recall@12 stays within ~0.01 of control AND mean
   sections/bundle drops for weak-coverage queries. REJECT if recall regresses beyond that — a
   sharper-but-lossy cut is not worth it.

| Config | recall@12 | mean sections/bundle | verdict |
|--------|-----------|----------------------|---------|
| knee off (control) |  |  | baseline |
| knee on, retain 0.5 |  |  |  |
| knee on, retain 0.4 |  |  |  |

**Scope:** the knee currently activates only on the pure-dense candidate path
(`wikantik.bundle.bm25.enabled=false`), where denseScore is a cosine on the same scale as
topSimilarity. On the DEFAULT hybrid path denseScore is a rank proxy (1/(1+pos)), so the knee
no-ops there — making it meaningful on the default path requires carrying real dense cosines
through HybridChunkSectionSource (a follow-up). Also: knee-N is derived from the dense order but
applied to the reranked+deduped output, so under an active reorder chain (mmr/metadata-boost) the
kept set is "fewer sections" but not guaranteed the densest.
