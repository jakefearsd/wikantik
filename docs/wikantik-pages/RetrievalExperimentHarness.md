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

Per-model report (e.g. `eval/report-bge-m3.txt`):

```
Retrieval evaluation — model: bge-m3  dim=1024
Date: 2026-04-18T…
Queries: 40

Overall:
  retriever  recall@5  recall@20  MRR
  bm25         0.550     0.775   0.524
  dense        0.x       0.x     0.x
  hybrid       0.x       0.x     0.x

Per-category:
  <7 categories × 3 retrievers>

Per-query (rank of ideal_page; 0 = miss):
  <40 rows>
```

BM25 baseline is fixed: **recall@5=0.550, recall@20=0.775, MRR=0.524** (40
queries, 7 categories). Dense and hybrid get filled in per model.

`ExperimentCompare` consolidates overalls:

```
model                        retriever  recall@5  recall@20  MRR
nomic-embed-v1.5             bm25         0.550     0.775    0.524
nomic-embed-v1.5             dense        0.x       0.x      0.x
nomic-embed-v1.5             hybrid       0.x       0.x      0.x
bge-m3                       …
qwen3-embedding-0.6b         …
```

---

## 6. Related files

| Path | Purpose |
|---|---|
| `bin/trigger-rebuild-indexes.sh` | Populate `kg_content_chunks` (gitignored — embeds testbot creds) |
| `bin/run-embedding-experiment.sh` | End-to-end driver |
| `eval/experiment-embeddings.sql` | Sandbox DDL (not a migration) |
| `eval/retrieval-queries.csv` | 40-query, 7-category ground truth |
| `wikantik-main/src/main/java/com/wikantik/search/embedding/experiment/` | `ExperimentIndexer`, `ExperimentEvaluator`, `ExperimentCompare`, `Bm25Client`, `ExperimentDb`, `QueryCorpus`, `ReciprocalRankFusion`, `CosineSimilarity`, `VectorCodec` |
| `wikantik-main/src/main/java/com/wikantik/search/embedding/` | Production-side client + config (feature-flagged off) |

After a model is chosen, the sandbox table is dropped, a pgvector column sized
to the winning dimension is added, the `wikantik.search.hybrid.enabled` flag is
wired through `SearchManager`/REST, and the experiment code stays in place for
future regression runs.

---

## 7. Chunker improvement results (2026-04-19)

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
