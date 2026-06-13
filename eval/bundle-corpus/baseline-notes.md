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

## Frozen baseline (TBD)

> This section will be filled in by Tasks 9 & 11.

- **Baseline numbers:** overall recall@5/recall@20/MRR per retriever, per-category breakdown — to be recorded here once the corpus CSV is stabilized and the gate thresholds are set.
- **Model set:** embeddings `qwen3-embedding-0.6b` (1024-dim; prod uses the ini default — no env
  override); extraction `gemma4-graph:12b` (think:false, sent per-request). Numbers TBD with the run.
- **Prod dense backend:** `lucene-hnsw` — **confirmed 2026-06-13** from `.env.prod`
  (`WIKANTIK_DENSE_BACKEND=lucene-hnsw`). Local-dev tomcat is `inmemory` (the ini default), which
  explained the earlier apparent contradiction. The baseline must be measured against `lucene-hnsw`.
- **Date frozen:** TBD.
