# Retrieval Quality Dashboard and CI

The retrieval-quality subsystem runs a curated query set against the wiki's search
stack nightly, persists aggregate metrics to `retrieval_runs`, and publishes
Prometheus gauges so regressions are visible before they reach users or agents.

This document covers the operator surface: the admin dashboard, on-demand runs, the
metrics that are scraped, and how to read a regression.

For the underlying harness design, query-set construction, and metric interpretation,
see the wiki pages:

- **[RunningTheRetrievalQualityHarness](wikantik-pages/RunningTheRetrievalQualityHarness.md)** — how to invoke the harness manually, what the nightly scheduler adds.
- **[EvaluatingRetrievalQuality](wikantik-pages/EvaluatingRetrievalQuality.md)** — what nDCG, Recall@K, and MRR mean and how to build a Gold Set.

## Table of contents

1. [How it works](#how-it-works)
2. [Nightly schedule](#nightly-schedule)
3. [Retrieval modes](#retrieval-modes)
4. [Metrics and Prometheus gauges](#metrics-and-prometheus-gauges)
5. [Database persistence](#database-persistence)
6. [Admin UI walkthrough](#admin-ui-walkthrough)
7. [REST endpoint reference](#rest-endpoint-reference)
8. [Auth model](#auth-model)
9. [Reading a regression](#reading-a-regression)
10. [Troubleshooting](#troubleshooting)
11. [Cross-links](#cross-links)

---

## How it works

`DefaultRetrievalQualityRunner` is the production implementation of
`RetrievalQualityRunner`. For each run it:

1. Loads the named query set from `retrieval_queries` (keyed by `query_set_id`).
2. Executes each query through the requested retrieval mode via the injected
   `Retriever` seam.
3. Resolves result page slugs to `canonical_id`s via the structural index.
4. Computes per-query scores using `RetrievalMetricsCalculator` (nDCG@5, nDCG@10,
   Recall@20, MRR).
5. Aggregates across all queries in the set.
6. Writes a `retrieval_runs` row via `RetrievalQualityDao`.
7. Updates Prometheus gauges via `RetrievalQualityMetrics`.

Queries with empty `expected_ids` are skipped (counted as `queries_skipped`).
Queries where the retriever throws are also skipped, and the run is marked
`degraded = true`.

## Nightly schedule

The runner schedules itself on a single daemon thread at startup. The default run
hour is **03:00 UTC** (hardcoded in `DefaultRetrievalQualityRunner`; not currently
configurable via `wikantik.properties`).

The nightly job runs every (query_set_id, mode) pair sequentially. The currently
scheduled query set is `core-agent-queries` (V017 seed). Additional sets must be
added to the `runAllForNightly()` method or via a future migration + configuration
mechanism.

If any individual (set, mode) run fails, the error is logged at `WARN` and the
`wikantik_retrieval_run_failed_total` counter is incremented; the job continues with
the next pair.

## Retrieval modes

Three modes are evaluated, matching `com.wikantik.api.eval.RetrievalMode`:

| Wire name | Description |
|-----------|-------------|
| `bm25` | BM25 full-text only (Lucene). |
| `hybrid` | BM25 + dense (vector) fusion. |
| `hybrid_graph` | Hybrid + KG graph-proximity rerank. |

Each mode is run independently per query set. Dashboard cells are bucketed by
`(query_set_id, mode)`.

## Metrics and Prometheus gauges

`RetrievalQualityMetrics` registers the following with the shared Micrometer
`MeterRegistry` (exposed at `/metrics` in Prometheus text format):

| Metric name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `wikantik_retrieval_ndcg_at_5` | Gauge | `set`, `mode` | nDCG@5 for the latest run of this (set, mode) pair. `NaN` when no run has completed. |
| `wikantik_retrieval_ndcg_at_10` | Gauge | `set`, `mode` | nDCG@10 for the latest run. |
| `wikantik_retrieval_recall_at_20` | Gauge | `set`, `mode` | Recall@20 for the latest run. |
| `wikantik_retrieval_mrr` | Gauge | `set`, `mode` | MRR for the latest run. |
| `wikantik_retrieval_run_duration_seconds` | Timer | — | Wall-clock duration of each run (histogram). |
| `wikantik_retrieval_run_failed_total` | Counter | — | Cumulative failed runs (any mode). |

Gauges are lazily registered on first `recordRun()` call, keyed on
`(metric_name, set, mode)`. A null aggregate metric surfaces as `NaN` on the gauge —
a valid Prometheus value meaning "not scoreable" (e.g. all queries were skipped).

**Scrape target:** if `MeterRegistryHolder` has no bound `MeterRegistry` (e.g.
`wikantik-observability` is not on the classpath or the registry failed to initialize),
metrics will not be published. A `WARN` is logged at startup:
`No shared MeterRegistry — retrieval-quality metrics will NOT be scraped.`

**Sample Prometheus query to alert on nDCG@5 regression:**

```promql
wikantik_retrieval_ndcg_at_5{set="core-agent-queries", mode="hybrid"} < 0.80
```

The design document names an absolute drop > 5% in nDCG@5 as the alert threshold;
set your alerting rules accordingly.

## Database persistence

Three tables (V016):

**`retrieval_query_sets`** — named bundles:

```
id            VARCHAR(64)  PRIMARY KEY
name          VARCHAR(128) NOT NULL
description   TEXT
created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
```

**`retrieval_queries`** — individual evaluation queries:

```
query_set_id  VARCHAR(64)  NOT NULL REFERENCES retrieval_query_sets(id) ON DELETE CASCADE
query_id      VARCHAR(64)  NOT NULL
query_text    TEXT         NOT NULL
expected_ids  TEXT[]       NOT NULL
PRIMARY KEY (query_set_id, query_id)
```

`expected_ids` holds the `canonical_id`s of pages considered relevant for this query.
V017 seeds the `core-agent-queries` set.

**`retrieval_runs`** — one row per completed run:

```
run_id        BIGSERIAL    PRIMARY KEY
query_set_id  VARCHAR(64)  NOT NULL REFERENCES retrieval_query_sets(id)
started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
finished_at   TIMESTAMPTZ
mode          VARCHAR(32)  NOT NULL    -- 'bm25' | 'hybrid' | 'hybrid_graph'
ndcg_at_5     NUMERIC(5,4)
ndcg_at_10    NUMERIC(5,4)
recall_at_20  NUMERIC(5,4)
mrr           NUMERIC(5,4)
notes         JSONB
```

Indexes: `(query_set_id, started_at DESC)` and `(mode)`.

To inspect the last 10 runs directly:

```bash
PGPASSWORD=<password> psql -h localhost -U wikantik -d wikantik \
  -c "SELECT run_id, mode, ndcg_at_5, ndcg_at_10, recall_at_20, mrr, started_at \
      FROM retrieval_runs \
      WHERE query_set_id = 'core-agent-queries' \
      ORDER BY started_at DESC LIMIT 10;"
```

## Admin UI walkthrough

**Route:** `/admin/retrieval-quality`

The page shows a table of `(query_set_id, mode)` cells — one row per unique pair seen
in `retrieval_runs`. Each metric column (`ndcg_at_5`, `ndcg_at_10`, `recall_at_20`,
`mrr`) shows:

- The **latest run value** formatted to 3 decimal places (e.g. `0.875`).
- A **sparkline** chart of historical values for that cell (recent-first from the API,
  reversed to chronological order for the chart).

`—` is displayed when no numeric value is available for the latest run.

**Toolbar filters:**

- **Query set** — text field; filters by `query_set_id` (exact, case-sensitive match
  against the backend parameter).
- **Mode** — dropdown: `(any)` / `bm25` / `hybrid` / `hybrid_graph`.

Default limit is 30 runs per cell (fetched from the API; the most recent 30 runs
are returned and bucketed client-side).

**Run now button:** Each row has a **Run now** button. Clicking it calls
`POST /admin/retrieval-quality/run` with the row's `query_set_id` and `mode`,
then reloads the table. The button is disabled and shows `…` while the run is in
progress. Runs are synchronous from the API's perspective — the POST returns only
after the run completes and the result is persisted.

## REST endpoint reference

Protected by `AdminAuthFilter` (admin role required). Returns 503 when the
`RetrievalQualityRunner` is not registered (Knowledge Graph not initialised).

### `GET /admin/retrieval-quality`

Returns recent runs, optionally filtered.

Query parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query_set_id` | string | — | Filter to one query set. |
| `mode` | string | — | Filter to one mode (`bm25`, `hybrid`, `hybrid_graph`). Unknown mode → 400. |
| `limit` | integer | 50 | Clamped to 1–1000. |

Response:

```json
{
  "data": {
    "recent_runs": [
      {
        "run_id": 42,
        "query_set_id": "core-agent-queries",
        "mode": "hybrid",
        "ndcg_at_5": 0.875,
        "ndcg_at_10": 0.921,
        "recall_at_20": 0.963,
        "mrr": 0.812,
        "started_at": "2026-06-05T03:00:01Z",
        "finished_at": "2026-06-05T03:01:23Z",
        "queries_evaluated": 40,
        "queries_skipped": 2,
        "degraded": false
      }
    ],
    "count": 1
  }
}
```

`ndcg_at_5`, `ndcg_at_10`, `recall_at_20`, `mrr` are `null` when the run was
fully degraded (all queries skipped).

`degraded: true` means at least one query failed with a retriever exception; the
aggregate is computed over the remaining evaluated queries.

### `POST /admin/retrieval-quality/run`

Trigger an on-demand run. Synchronous — returns when the run is complete.

Request body:

```json
{
  "query_set_id": "core-agent-queries",
  "mode": "hybrid"
}
```

- Missing or blank `query_set_id` → 400.
- Unknown `mode` → 400.
- Unknown `query_set_id` → 400.
- Runner exception → 500.

Response on success: the same run object as in the `GET` response, wrapped in
`{ "data": { ... } }`.

## Auth model

Both endpoints require the `AllPermission` security permission enforced by
`AdminAuthFilter`. No finer-grained access: any admin can view history and trigger
on-demand runs.

The `RetrievalQualityRunner` and its DAO are part of the Knowledge Graph subsystem
(`wikantik-knowledge` module). If the Knowledge Graph failed to initialise (missing
JNDI DataSource, pgvector unavailable, etc.), the runner is `null` and all endpoints
return 503.

## Reading a regression

1. **Open the dashboard.** `/admin/retrieval-quality`. Look at the `hybrid` mode row
   for `core-agent-queries` — this is the primary signal.

2. **Check the sparkline.** A downward trend in `ndcg_at_5` or `mrr` over the last
   several nights indicates a real degradation. A single low data point may be noise
   (degraded run, a skipped query due to a missing page, etc.).

3. **Check `degraded`** in the raw API response. A `degraded: true` run means the
   retriever threw for at least one query; the score may be artificially high (fewer
   hard queries evaluated). Investigate the logs (`catalina.out`) for the error.

4. **Compare modes.** If `bm25` dropped but `hybrid` did not, the regression is in
   the Lucene index (e.g. after a re-index or schema change). If `hybrid` dropped but
   `bm25` did not, the regression is in the dense/embedding layer or the fusion
   weights.

5. **Trigger an on-demand run** immediately after a code change to get a pre-nightly
   data point. Use **Run now** in the UI or:

   ```bash
   curl -u admin:admin -X POST http://localhost:8080/admin/retrieval-quality/run \
     -H 'Content-Type: application/json' \
     -d '{"query_set_id":"core-agent-queries","mode":"hybrid"}'
   ```

6. **Design alert threshold:** an absolute drop > 5 percentage points in `ndcg_at_5`
   compared to the prior run for the same `(set, mode)` is the design-nominated
   threshold (see `RunningTheRetrievalQualityHarness.md`). Configure a Prometheus
   alert or a Grafana annotation using the `wikantik_retrieval_ndcg_at_5` gauge.

7. **Check the query set.** Stale `expected_ids` in `retrieval_queries` can cause
   phantom regressions when pages are renamed or deleted. Re-run the harness against
   the updated corpus and refresh the ground-truth targets in `retrieval_queries` as
   needed (see the retrieval harness runbook).

## Troubleshooting

**Dashboard shows 503**

The `RetrievalQualityRunner` is `null` — the Knowledge Graph subsystem did not
initialise. Check `catalina.out` for errors. Verify the JNDI `DataSource`, that
V016/V017 migrations ran (`bin/db/migrate.sh --status`), and that the
`wikantik.datasource` property is set.

**All metrics show `—` (no runs)**

No `retrieval_runs` rows exist yet. The nightly job fires at 03:00 UTC; trigger an
on-demand run to populate the table immediately.

**`degraded: true` on every run**

The retriever is failing for every query. Check:
- Is the dense backend (Lucene HNSW / pgvector / in-memory) initialised? Look for
  `DenseSearchService` startup messages in `catalina.out`.
- Is Ollama reachable and the configured embedding model loaded?
  (`wikantik.search.dense.backend`, `wikantik.search.dense.model`)
- Run a manual search: `curl http://localhost:8080/api/search?q=test`

**Prometheus gauges are `NaN`**

Either no run has completed since Tomcat started (gauges are lazily registered), or
the `MeterRegistry` is not bound. Look for
`No shared MeterRegistry — retrieval-quality metrics will NOT be scraped.`
in `catalina.out`. Ensure `wikantik-observability` is in the WAR and the
`/metrics` servlet is reachable.

**`queries_skipped` is high**

Pages in `expected_ids` may have been deleted or renamed since the query set was
built, causing `canonicalIdForSlug` to return empty. Update the `retrieval_queries`
rows to reflect the current `canonical_id`s, or regenerate the query set.

## Cross-links

- [docs/wikantik-pages/RunningTheRetrievalQualityHarness.md](wikantik-pages/RunningTheRetrievalQualityHarness.md) — manual invocation + nightly scheduler design.
- [docs/wikantik-pages/EvaluatingRetrievalQuality.md](wikantik-pages/EvaluatingRetrievalQuality.md) — metric definitions (nDCG, MRR, Recall@K) and Gold Set construction.
- [docs/KnowledgeGraphRerank.md](KnowledgeGraphRerank.md) — graph rerank configuration; affects `hybrid_graph` scores.
- [docs/KgInclusionPolicy.md](KgInclusionPolicy.md) — KG inclusion policy; excluding clusters affects the `hybrid_graph` rerank quality.
- `bin/db/migrations/V016__retrieval_quality.sql` — DDL for the three retrieval tables.
- `bin/db/migrations/V017__seed_retrieval_query_set.sql` — initial `core-agent-queries` seed.
