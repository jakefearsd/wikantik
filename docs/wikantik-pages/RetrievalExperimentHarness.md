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
