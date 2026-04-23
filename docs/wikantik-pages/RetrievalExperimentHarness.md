---
title: Retrieval Experiment Harness
type: article
tags:
- search
- retrieval
- embeddings
- bm25
- hybrid
- evaluation
summary: How to run the offline harness that compares BM25, dense, and hybrid retrieval across three candidate embedding models.
---
# Retrieval Experiment Harness

The harness at `com.wikantik.search.embedding.experiment` scores three candidate
embedding models against a page-level ground-truth CSV, so we can pick a winner
before committing to a pgvector schema on the production search path. It runs
entirely outside the wiki's serving code — no `WikiEngine`, no `SearchManager`,
no REST wiring — and talks to the running wiki only for BM25 via
`/api/search`.

This page is the operating manual. For the *design* (why three retrievers, why
chunk-level dense with max-score-per-page, why sandbox BYTEA instead of
`vector(n)`) see the file-level javadoc in the `experiment` package.

---

## 1. What gets compared

| Retriever | Source | Notes |
|---|---|---|
| **BM25-only** | `GET /api/search?q=…` on the running wiki | Lucene lexical baseline |
| **Dense-only** | Cosine similarity over per-chunk vectors, aggregated to pages by max-score | One run per candidate model |
| **Hybrid** | Reciprocal Rank Fusion (k=60) of the two rankings above | |

Three candidate models (all served by Ollama at `inference.jakefear.com:11434`):

| Code | Ollama tag | Dimension | Asymmetric prefix |
|---|---|---|---|
| `nomic-embed-v1.5` | `nomic-embed-text:v1.5` | 768 | `search_query:` / `search_document:` |
| `bge-m3` | `bge-m3:latest` | 1024 | none |
| `qwen3-embedding-0.6b` | `qwen3-embedding:0.6b` | 1024 | instruction prompt on queries only |

Each run produces `eval/report-<model>.txt` with overall, per-category, and
per-query metrics (recall@5, recall@20, MRR). `ExperimentCompare` then prints
a side-by-side table across all three reports.

---

## 2. Prerequisites (first-time setup)

1. **Models pulled on the Ollama host.** Check with:
   ```
   curl -s http://inference.jakefear.com:11434/api/tags | jq '.models[].name'
   ```
   All three tags above must be present.

2. **Wiki running locally** at `http://localhost:8080`. `/api/health` should
   show `engine: UP`.

3. **`kg_content_chunks` populated.** On a fresh checkout this table is empty
   — nothing to embed. Populate it by triggering the async rebuild:
   ```
   bin/trigger-rebuild-indexes.sh
   ```
   This posts to `/admin/content/rebuild-indexes` with the `testbot`
   credentials embedded in the (gitignored) script. Rebuild is async; poll
   until chunking finishes:
   ```
   curl -s -u testbot:<pw> http://localhost:8080/admin/content/index-status | jq
   ```
   Expect ~1K chunks from ~1K markdown pages after a few minutes.

4. **Sandbox DDL.** `eval/experiment-embeddings.sql` creates the
   dimension-agnostic `experiment_embeddings(chunk_id, model_code, dim, vec)`
   table. The runner applies it idempotently.

---

## 3. One-shot run

The full pipeline (DDL → indexer × 3 → evaluator × 3 → compare) is wrapped by
`bin/run-embedding-experiment.sh`:

```
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')

DB_PASSWORD='<jspwiki db pw>' \
WIKI_USER="${login}" WIKI_PASSWORD="${password}" \
    bin/run-embedding-experiment.sh
```

Required env: `DB_PASSWORD`, `WIKI_USER`, `WIKI_PASSWORD`.

Optional env:

| Var | Default | Purpose |
|---|---|---|
| `MODELS` | all three codes | Space-separated subset to test |
| `DB_HOST` / `DB_NAME` / `DB_USER` | `localhost` / `jspwiki` / `jspwiki` | |
| `WIKI_URL` | `http://localhost:8080` | |
| `OUTPUT_DIR` | `eval` | Where reports land |
| `SKIP_DDL=1` | off | Skip the DDL step |
| `SKIP_INDEX=1` | off | Skip indexer (re-score existing embeddings) |
| `MVN_QUIET=1` | off | `-q` on Maven (cuts chatter) |

The indexer fails fast with a clear message if `kg_content_chunks` is empty —
you'll see it immediately rather than after a silent 0-row run.

---

## 4. Running pieces individually

Each stage is a `main()` reachable via `mvn exec:java`. The runner script is
just shorthand for these.

### Apply the sandbox DDL

```
PGPASSWORD='<pw>' psql -h localhost -U jspwiki -d jspwiki \
    -f eval/experiment-embeddings.sql
```

### Indexer (once per model)

```
mvn -pl wikantik-main -am -q exec:java \
    -Dexec.mainClass=com.wikantik.search.embedding.experiment.ExperimentIndexer \
    -Dexec.args="nomic-embed-v1.5" \
    -Dwikantik.experiment.db.password='<pw>'
```

Writes embeddings into `experiment_embeddings` with `ON CONFLICT DO NOTHING`,
so reruns only top up what's missing. Batches of 32 chunks per HTTP call.

### Evaluator (once per model)

```
mvn -pl wikantik-main -am -q exec:java \
    -Dexec.mainClass=com.wikantik.search.embedding.experiment.ExperimentEvaluator \
    -Dexec.args="nomic-embed-v1.5 eval/retrieval-queries.csv eval/report-nomic.txt" \
    -Dwikantik.experiment.db.password='<pw>' \
    -Dwikantik.experiment.wiki.user=testbot \
    -Dwikantik.experiment.wiki.password='<pw>'
```

### Side-by-side comparison

```
mvn -pl wikantik-main -am -q exec:java \
    -Dexec.mainClass=com.wikantik.search.embedding.experiment.ExperimentCompare \
    -Dexec.args="eval/report-nomic-embed-v1.5.txt eval/report-bge-m3.txt eval/report-qwen3-embedding-0.6b.txt"
```

---

## 5. Output

Per-model report (`eval/report-<model>.txt`):

```
Retrieval evaluation — model: <model>  dim=<n>
Date: 2026-04-18T…
Queries: 40

Overall:
  retriever  recall@5  recall@20  MRR
  bm25         0.550     0.800   0.519
  dense        <model-dependent>
  hybrid       <model-dependent>

Per-category:
  <7 categories × 3 retrievers>

Per-query (rank of ideal_page; 0 = miss):
  <40 rows>
```

BM25 baseline is fixed at **recall@5=0.550, recall@20=0.800, MRR=0.519**
(40 queries, 7 categories) and does not move with the embedding model —
Lucene indexes the chunk table, not any vector store.

`ExperimentCompare` consolidates overall lines across model reports:

```
model                        retriever  recall@5  recall@20  MRR
nomic-embed-v1.5             bm25         0.550     0.800    0.519
nomic-embed-v1.5             dense        0.625     0.800    0.474
nomic-embed-v1.5             hybrid       0.650     0.900    0.530
bge-m3                       bm25         0.550     0.800    0.519
bge-m3                       dense        0.700     0.875    0.503
bge-m3                       hybrid       0.750     0.900    0.615
qwen3-embedding-0.6b         bm25         0.550     0.800    0.519
qwen3-embedding-0.6b         dense        0.750     0.900    0.490
qwen3-embedding-0.6b         hybrid       0.750     0.925    0.602
```

Exact numbers from the 2026-04-18 run — the decision-making run documented
in Section 7 below.

---

## 6. Related files

| Path | Purpose |
|---|---|
| `bin/trigger-rebuild-indexes.sh` | Populate `kg_content_chunks` (gitignored — embeds testbot creds) |
| `bin/run-embedding-experiment.sh` | End-to-end driver |
| `eval/experiment-embeddings.sql` | Sandbox DDL (not a migration) |
| `eval/retrieval-queries.csv` | 40-query, 7-category ground truth |
| `wikantik-main/src/main/java/com/wikantik/search/embedding/experiment/` | `ExperimentIndexer`, `ExperimentEvaluator`, `ExperimentCompare`, `ExperimentAggSweep`, `ExperimentRrfSweep`, `ExperimentFinalSweep`, `ExperimentGrandFinale`, `ExperimentHarness`, `Bm25Client`, `ExperimentDb`, `QueryCorpus`, `ReciprocalRankFusion`, `CosineSimilarity`, `VectorCodec` |
| `wikantik-main/src/main/java/com/wikantik/search/embedding/` | Production-side client + config (now `enabled=true` by default; feature-flag remains as the kill switch) |

The experiment code stays in place after each decision so regression runs
stay one Maven command away.

---

## 7. Model selection — the 2026-04-18 decision

This is the run that picked `qwen3-embedding-0.6b` as the production
embedding model. All three candidates indexed the same ~30k-chunk corpus,
same BM25 baseline, same 40-query / 7-category ground truth, same
max-score page aggregation at that point.

### Raw results

| Model | dim | bm25 r@5 | dense r@5 | dense r@20 | dense MRR | hybrid r@5 | hybrid r@20 | hybrid MRR |
|---|---|---|---|---|---|---|---|---|
| `nomic-embed-v1.5` | 768 | 0.550 | 0.625 | 0.800 | 0.474 | 0.650 | 0.900 | 0.530 |
| `bge-m3` | 1024 | 0.550 | 0.700 | 0.875 | 0.503 | 0.750 | 0.900 | 0.615 |
| **`qwen3-embedding-0.6b`** | 1024 | 0.550 | **0.750** | **0.900** | 0.490 | **0.750** | **0.925** | 0.602 |

Reports on disk: `eval/report-nomic-embed-v1.5.txt`,
`eval/report-bge-m3.txt`, `eval/report-qwen3-embedding-0.6b.txt`.

### Decision rationale

- **qwen3 leads on recall at both cutoffs.** Dense recall@5 is a full
  +0.050 over bge-m3 and +0.125 over nomic. Dense recall@20 is +0.025
  over bge-m3 and +0.100 over nomic. Hybrid recall@20 (0.925) is the
  best across the three.
- **bge-m3 leads narrowly on MRR** (dense 0.503 vs 0.490; hybrid 0.615
  vs 0.602). This was known, weighed, and accepted: for a RAG pipeline
  where the retrieved set feeds a generative answer model that handles
  its own reranking, "did the right page make it into the top-K?" is
  more load-bearing than "exactly where in the top-K did it land?"
  Recall > MRR for this workload.
- **nomic-embed-v1.5 loses on every dimension.** The 768-dim model
  trails the 1024-dim models by a consistent margin; the asymmetric
  prefix (`search_query:` / `search_document:`) did not make up the
  gap on this corpus.

### Per-category highlights

Where qwen3's dense recall pulled away:

- `synonym-drift` (7 queries like "blue green release strategy" →
  `BlueGreenDeployments`): qwen3 and bge-m3 both hit 1.000 dense
  recall@5; nomic only 0.571.
- `hard` (5 queries including `"k8s"` → `KubernetesBasics` and `"ai"`
  → `ArtificialIntelligence`): qwen3 held 0.600 dense recall@5;
  bge-m3 also 0.600; nomic only 0.400.
- `specific` (5 queries): qwen3 dense recall@5 = 0.800, same as
  nomic (1.000) and bge-m3 (0.800) — all three handle exact-name
  queries fine; this bucket didn't move the decision.

### Aggregation sweep — `ExperimentAggSweep`

With qwen3 locked in, the next question was which chunk → page
aggregation to use. `ExperimentAggSweep` produced
`eval/agg-sweep-qwen3-embedding-0.6b.txt`:

| aggregation | dense r@5 | dense r@20 | dense MRR |
|---|---|---|---|
| MAX | 0.750 | 0.900 | 0.490 |
| MEAN_TOP_3 | 0.750 | 0.950 | 0.576 |
| MEAN_TOP_5 | 0.750 | 0.900 | 0.589 |
| **SUM_TOP_3** | **0.800** | **0.975** | **0.602** |
| SUM_TOP_5 | 0.775 | 0.925 | 0.612 |
| MEAN_TOP_3_LOG_NORM | 0.350 | 0.850 | 0.175 |

`SUM_TOP_3` dominates. MEAN_TOP_3_LOG_NORM is the sanity-check negative
result — log-normalising chunk scores before summing kills signal.

### Joint sweep — `ExperimentFinalSweep` and `ExperimentGrandFinale`

The final sweeps fan every aggregation across every fusion strategy
(dense-only, RRF with three weighting variants, plain score averaging
with dense-heavy / bm25-heavy / equal weights) to confirm the winner
survives hyperparameter interaction.

Best combination per model (`eval/grand-finale.txt`):

| Model | Best aggregation | Best fusion | r@5 | r@20 | MRR |
|---|---|---|---|---|---|
| nomic-embed-v1.5 | SUM_TOP_5 | RRF_RECALL | 0.750 | 0.925 | 0.558 |
| bge-m3 | MEAN_TOP_3 | SCORE_DENSE_HEAVY | 0.775 | 0.900 | 0.622 |
| **qwen3-embedding-0.6b** | **SUM_TOP_3** | **dense-only** | **0.800** | **0.975** | **0.602** |

qwen3 + SUM_TOP_3 + dense-only wins r@5 and r@20 outright. bge-m3's
best-case MRR (0.622) edges qwen3's (0.602), but qwen3 comes
within 0.020 at r@5=0.800 vs bge-m3's 0.775. The decision stood:
**qwen3 with SUM_TOP_3 aggregation**.

### Production defaults picked from this data

`wikantik-main/.../search/hybrid/HybridConfig.java`:

```
DEFAULT_PAGE_AGGREGATION = PageAggregation.SUM_TOP_3;
DEFAULT_RRF_K            = 60;
DEFAULT_BM25_WEIGHT      = 1.0;
DEFAULT_DENSE_WEIGHT     = 1.5;   // dense-heavy, matches SCORE_DENSE_HEAVY
DEFAULT_RRF_TRUNCATE     = 20;
DEFAULT_DENSE_CHUNK_TOP  = 500;
DEFAULT_DENSE_PAGE_TOP   = 100;
```

Dense is weighted 1.5× vs BM25 in the RRF fusion because the grand
finale shows dense-leaning hybrids consistently within 0.05 of the
top recall while beating BM25-heavy variants on MRR. `RRF k=60` is
the standard Cormack/Clarke/Büttcher default carried from the
literature — the sweep confirmed no nearby value beat it on our
corpus by enough to justify a non-conventional choice.

---

## 8. Chunker improvement results (2026-04-19)

Two targeted changes to the chunking + embedding pipeline landed together and
were evaluated against the frozen `qwen3-embedding-0.6b` baseline. Both are
structural — no retriever, fusion weights, or query-side logic changed.

### What changed

1. **Atomic list chunking.** `ContentChunker.isAtomic(Node)` now treats
   `BulletList` and `OrderedList` as indivisible up to `maxTokens × 4` (≈ 2048
   tokens). Previously, Flexmark emitted each list item as a separate block
   and the merge pass sometimes split related items across chunks. Lists of
   command flags, step-by-step instructions, and config options now live in
   one chunk with their siblings.
2. **Heading path prepended at embed time.** A new `EmbeddingTextBuilder`
   renders `"<Top> > <Mid> > <Leaf>\n\n<body>"` and is the single rendering
   point for both `EmbeddingIndexService` (production) and `ExperimentIndexer`
   (sandbox). The stored chunk text in `kg_content_chunks.text` stays
   body-only; the heading-path-aware string only exists on the wire to the
   embedder. Chunk identity (`content_hash` = sha256(heading_path + text))
   is unchanged.

### Corpus impact

| | Before | After |
|---|---|---|
| `kg_content_chunks` rows | 23,656 | 39,264 |
| Avg tokens / chunk | ~230 | 103 |

More, smaller chunks — atomic lists stop the merge pass from gluing unrelated
blocks together, so prose paragraphs are no longer inflated by adjacent list
content.

### Retrieval metrics (40 queries, 7 categories)

**Overall:**

| retriever | recall@5 | recall@20 | MRR |
|---|---|---|---|
| bm25   | 0.550 → **0.775** (+0.225) | 0.800 → **0.975** (+0.175) | 0.519 → **0.650** (+0.131) |
| dense  | 0.750 → 0.750 (+0.000)     | 0.900 → **0.950** (+0.050) | 0.490 → **0.627** (+0.137) |
| hybrid | 0.750 → **0.850** (+0.100) | 0.925 → **0.975** (+0.050) | 0.602 → **0.667** (+0.065) |

**Categories that moved the most:**

- `business-process` bm25 recall@5: 0.400 → 0.800
- `hard` bm25 recall@20: 0.600 → 1.000; dense recall@20: 0.800 → 1.000
- `indirect` bm25 MRR: 0.458 → 0.614 (every retriever now recall@20 = 1.000)
- `general` dense MRR: 0.461 → 0.667

### Why the gains break down this way

- **BM25 jumped more than expected.** BM25 in this harness indexes over the
  chunk table, not pages. When a query's answer is "item 3 of the `--force`
  options list," splitting that list across chunks left BM25 with a diluted
  hit. Atomic lists put every related term in one chunk and BM25 lights up
  cleanly. The `hard`, `indirect`, and `business-process` categories — all
  heavy on flag/step/option queries — moved the most.
- **Dense recall@5 was already saturated at 0.750 and didn't move, but dense
  MRR jumped +0.137.** Heading-path context doesn't widen the net; it pulls
  the correct chunk up in rank. That's exactly the precision-not-recall win
  the change was designed to produce.
- **Hybrid is the winner** at recall@5 = 0.850 and recall@20 = 0.975 — best
  across every category. Because BM25 and dense both improved but on different
  axes (recall vs. rank), RRF compounds the gains.

### What this means for overlap

Overlap (replaying the last N tokens of chunk *i* as the first N tokens of
chunk *i+1*) was the obvious next lever — until these two changes absorbed
most of what overlap was meant to fix:

- Boundary bleed on list items → gone; lists are atomic.
- Context-poor chunks → gone; heading path is on the wire.

Recall@20 hybrid is 0.975. There are 39 of 40 queries recovered; the miss
budget for overlap to improve against is one query. If overlap is worth
revisiting, the signal will show up in **dense recall@5** (stuck at 0.750),
not in the hybrid overall.

### Reports on disk

| Path | What |
|---|---|
| `eval/report-qwen3-embedding-0.6b-baseline-prechunk.txt` | Before, 2026-04-18 |
| `eval/report-qwen3-embedding-0.6b-2026-04-19T20-01-22-331504860Z.txt` | After, 2026-04-19 |

Reproduce with:

```
mvn -pl wikantik-main exec:java \
    -Dexec.mainClass=com.wikantik.search.embedding.experiment.ExperimentCompare \
    -Dexec.args="<before.txt> <after.txt>"
```

---

## 9. Chunker rebuild — merge-forward floor raised (2026-04-23)

During the Phase 2 entity-extractor benchmark work it became clear that
chunk *count*, not chunk *size*, was the bottleneck on full-corpus batch
extraction: at ~39k chunks the projected extractor wall-clock was ~95 h
on the shipping model. Inspection of `ContentChunker.java` revealed two
advertised config keys (`target_tokens`, `min_tokens`) that were never
referenced in the class — dead knobs — and one lever,
`merge_forward_tokens`, that actually controls chunk consolidation.

### What changed

- Removed the dead `target_tokens` and `min_tokens` fields from
  `ContentChunker.Config` and from `wikantik.properties`.
- Raised `wikantik.chunker.merge_forward_tokens` default from **8 → 150**.
  Sections whose accumulated text is below this threshold are now held
  and merged into the next section rather than emitted immediately, so
  short sibling sections (typical wiki "Overview", "Introduction",
  "Notes" blocks) coalesce into chunks of reasonable size.
- Commit: `2acccf102 feat(kg-rag): phase 1-3 uplift`.

### Corpus impact

| | Before | After |
|---|---|---|
| `kg_content_chunks` rows | 39,264 | **23,256** (−41%) |
| `content_chunk_embeddings` rows | 39,264 | 23,256 |
| Mean tokens / chunk | 103 | **174** (+69%) |
| p50 tokens / chunk | 77 | 166 |
| p95 tokens / chunk | 261 | 335 |
| Max tokens / chunk | 1,963 | 1,963 (atomic blocks unchanged) |
| Embedding re-index wall-clock (qwen3-embedding-0.6b) | — | 6m 18s |

### Retrieval quality — live search top-10 diff

The retrieval harness was not re-run against the new chunks (the extractor
benchmark was the day's priority and the harness requires a full corpus
re-embed loop plus the 40-query evaluation pass). Instead, spot-check
comparison on live `/api/search` against two high-traffic queries, with
graph rerank disabled (mentions=0 after the rebuild cascaded them all):

**`"knowledge graph"`** — top 10:

| Rank | Old 39k chunks | New 23k chunks |
|---|---|---|
| 1 | WikantikKnowledgeGraphAdmin | InventionOfKnowledgeGraph |
| 2 | InventionOfKnowledgeGraph | WikantikKnowledgeGraphAdmin |
| 3 | KnowledgeGraphCore | KnowledgeGraphCore |
| 4 | KnowledgeGraphDogfooding | KnowledgeGraphVsRelationalDatabase |
| 5 | KnowledgeGraphVsRelationalDatabase | GraphRAG |
| 6 | GraphRAG | KnowledgeGraphsAndManagement |
| 7 | IndustrialKnowledgeGraphUseCases | IndustrialKnowledgeGraphUseCases |
| 8 | KnowledgeGraphsAndManagement | FederatedKnowledgeGraphs |
| 9 | KnowledgeGraphCompletion | KnowledgeGraphCompletion |
| 10 | FederatedKnowledgeGraphs | KnowledgeGraphConstructionPipeline |

Set overlap: **8/10**. Dropped: `KnowledgeGraphDogfooding`. Added:
`KnowledgeGraphConstructionPipeline`. Top-3 set preserved, positions
1–2 swapped (both highly relevant).

**`"GraphRAG"`** — top 10 essentially identical, set overlap 8/10 with
one substitution at position 8 (`AiFunctionCallingAndToolUse` →
`AiMemoryAndPersistence`).

### Why it's not a regression

- **BM25 is chunk-indexed in this harness but page-indexed in production
  Lucene.** The 04-19 +0.225 BM25 recall@5 gain was from atomic-list
  chunking giving lexical matches tighter scope; today's merge-forward
  move in the opposite direction (coarser chunks) would *lose* some of
  that BM25 gain in the harness. On the production path, Lucene indexes
  whole pages, so merge-forward has zero BM25 effect.
- **Dense on 174-token chunks is slightly less peaky than on 103-token
  chunks.** Chunks now containing multiple related sub-topics can win
  queries they previously missed; chunks narrowly winning on a focused
  topic sentence may slip a rank or two. Net: 80% top-10 overlap, slight
  topology shuffle, no new misses observed.
- **The chunker rebuild was motivated by extraction cost, not retrieval
  quality.** It trades a small dense-retrieval peakiness hit for a 2×
  reduction in chunk count → proportional reduction in extractor batch
  wall-clock. For the corpus we have, that's the right trade; the
  graph rerank layer that's about to get populated more than makes up
  for the dense-retrieval peakiness.

### If we want the harness number

Re-running the harness against the new chunks takes:

```
bin/trigger-rebuild-indexes.sh          # already done — 23k chunks live
# Drop and recreate the sandbox embeddings (qwen3's prior vectors are
# still keyed by the old chunk ids which were cascaded away by V011):
PGPASSWORD='…' psql -h localhost -U jspwiki -d jspwiki -c "DELETE FROM experiment_embeddings WHERE model_code='qwen3-embedding-0.6b'"
# Re-index + evaluate:
mvn -pl wikantik-main exec:java \
    -Dexec.mainClass=com.wikantik.search.embedding.experiment.ExperimentIndexer \
    -Dexec.args="qwen3-embedding-0.6b" \
    -Dwikantik.experiment.db.password='…'
mvn -pl wikantik-main exec:java \
    -Dexec.mainClass=com.wikantik.search.embedding.experiment.ExperimentEvaluator \
    -Dexec.args="qwen3-embedding-0.6b eval/retrieval-queries.csv \
                 eval/report-qwen3-embedding-0.6b-2026-04-23-postmerge.txt" \
    -Dwikantik.experiment.db.password='…' \
    -Dwikantik.experiment.wiki.user=testbot \
    -Dwikantik.experiment.wiki.password='…'
```

Pending: not blocking extraction work, but the right next retrieval-side
regression run to close the loop.

---

## 10. Evolution — from scratch to production

Condensed git-log narrative for anyone who needs to understand how each
piece got here.

| Date | Commit | What |
|---|---|---|
| 2026-04-14 | `1da3a7dce` | Initial `Chunk` record and minimal `ContentChunker` |
| 2026-04-14 | `64a8de3ff` | Heading-aware splitting with `heading_path` |
| 2026-04-14 | `185dd80cc` | Token budget, atomic blocks, merge-forward |
| 2026-04-14 | `f141524fe` | Explicit `mergeForwardTokens` Config field |
| 2026-04-14 | `9b411eeea` | `ContentChunkRepository` with diff apply + stats |
| 2026-04-15 | `c386f21b0` | Save-time `ChunkProjector` page filter |
| 2026-04-15 | `632737f58` | Prometheus metrics for chunker and rebuild |
| 2026-04-15 | `e8966adb` | Async page-save listener for incremental embedding reindex |
| 2026-04-16 | `a9a199041` | Stub `TextEmbeddingClient` + `EmbeddingKind` for Phase 1 |
| 2026-04-16 | `46ad1441e` | Caffeine dep + `TextEmbeddingClient` stub for Phase 4 |
| 2026-04-16 | `a9f2ae876` | `EmbeddingIndexService` — production chunk-embedding data layer |
| 2026-04-17 | `c0dabd53f` | `DenseRetriever` + placeholder `ChunkVectorIndex` |
| 2026-04-17 | `c169604ce` | `HybridFuser` for weighted RRF of BM25 and dense lists |
| 2026-04-17 | `6b6527572` | `PageAggregation` + `PageAggregator` |
| 2026-04-17 | `8717e533f` | `QueryEmbedderConfig`, `CircuitState`, metrics snapshot |
| 2026-04-17 | `4e3fe9921` | Hand-rolled CLOSED/OPEN/HALF_OPEN circuit breaker |
| 2026-04-17 | `4156d7a1f` | `QueryEmbedder` wraps embedding client with cache + timeout + breaker |
| 2026-04-17 | `e9bf62d1e` | `InMemoryChunkVectorIndex` for dense top-k |
| 2026-04-17 | `0825bb97b` | Ollama embedding client and model registry |
| 2026-04-18 | `5211ea391` | Retrieval experiment harness + first model-comparison reports |
| 2026-04-18 | `373a024a2` | `HybridConfig` with defaults matching the winning experiment |
| 2026-04-18 | `376cdb877` | Phase 3: hybrid retrieval core (PageAggregation, HybridFuser, DenseRetriever) |
| 2026-04-19 | `b6a86fba7` | Hybrid perf pass: parallelized embedding, incremental index, heading-aware context |
| 2026-04-19 | `c4447350d` | Release v1.1.6: hybrid retrieval, MCP access hardening, admin content ops |
| 2026-04-23 | `2acccf102` | KG-RAG Phase 1-3: unified embeddings, extractor pipeline, graph-aware rerank + chunker merge-forward 8 → 150 |
| 2026-04-23 | `c289fbdd7` | Standalone extract-CLI for Tomcat-less batch runs |

The two inflection points:

- **2026-04-18** — the experiment harness ran, qwen3-embedding-0.6b won,
  `HybridConfig` was frozen with SUM_TOP_3 aggregation and dense-heavy
  RRF. That's the moment "dense search" stopped being an experiment and
  started being the production search path.
- **2026-04-23** — the Phase 3 graph rerank layer added mentions as a
  new input signal on top of hybrid, and the chunker floor was raised
  to make whole-corpus entity extraction operationally affordable. That
  turns the harness's page-ranking story from "what's our best hybrid
  score" into "what's our best hybrid score *plus* graph-derived
  proximity boost" — and re-running the harness after the extractor
  batch populates mentions is the next regression checkpoint.
