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
