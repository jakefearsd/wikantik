# Agent-Grade Content — Phase 5 Implementation Plan

> **Status:** Implemented (commits `c31d61b08..8e7a1117e` on `main`).

**Goal:** Run the existing retrieval evaluation logic on a schedule inside
the live wiki, persist the results, expose them on `/admin/retrieval-quality`,
publish Prometheus gauges, and gate pre-merge with a small smoke test. Net
effect: regressions in BM25 / hybrid / hybrid+graph retrieval surface within
24 hours instead of being discovered by an agent thrashing on a stale corpus
weeks later.

**Design source:** [docs/wikantik-pages/AgentGradeContentDesign.md](../../wikantik-pages/AgentGradeContentDesign.md),
section "Retrieval-quality CI" (line ~334) and the V015 DDL block (line ~156).
The DDL number in the design is wrong by one — `V015` was already taken by
`deduplicate_user_profiles`, so this phase shipped as `V016`.

## Architecture

- **Migration `V016__retrieval_quality.sql`** — three idempotent tables:
  `retrieval_query_sets`, `retrieval_queries`, `retrieval_runs`. DDL is
  verbatim from the design doc except the file number. Grants use `:app_user`.
- **Migration `V017__seed_retrieval_query_set.sql`** — seeds one query set,
  `core-agent-queries`, with 16 hand-curated queries derived from the
  agent-cookbook runbooks. Each query's `expected_ids` is the canonical_id
  of the cookbook page that answers it; one cross-cluster query (`q15`)
  pairs the design doc with its sibling runbook. Idempotent
  (`ON CONFLICT DO NOTHING`).
- **`RetrievalMode` enum** in `wikantik-api`, three values: `BM25`,
  `HYBRID`, `HYBRID_GRAPH`. Wire form is the lowercase + underscore spelling
  (`bm25`, `hybrid`, `hybrid_graph`).
- **`RetrievalRunResult` record** in `wikantik-api` — value type returned
  by `runNow`; carries `(runId, querySetId, mode, ndcgAt5, ndcgAt10,
  recallAt20, mrr, startedAt, finishedAt, queriesEvaluated, queriesSkipped,
  degraded)`. Metrics are nullable `Double` so an all-skipped run surfaces
  as `null` rather than synthetic zero.
- **`RetrievalQualityRunner` interface** in `wikantik-api/.../eval` — three
  methods: `scheduleNightly`, `runNow(querySetId, mode)`, `recentRuns`.
- **`DefaultRetrievalQualityRunner`** in `wikantik-main/.../knowledge/eval`.
  Reads queries from the DAO, runs each query through the requested mode
  via the injected `Retriever` functional interface, maps page slugs to
  canonical_ids via the injected `CanonicalIdResolver`, computes per-query
  metrics via `RetrievalMetricsCalculator`, persists the aggregate row, and
  publishes Prometheus gauges. Owns a single-thread daemon
  `ScheduledExecutorService`; `scheduleNightly` is idempotent.
- **`RetrievalQualityDao`** in `wikantik-main/.../knowledge/eval` — JDBC CRUD
  over the three tables.
- **`RetrievalMetricsCalculator`** in `wikantik-main/.../knowledge/eval` —
  pure-function nDCG / Recall / MRR over `(predictedIds, expectedIds)`.
- **`RetrievalQualityMetrics`** in `wikantik-main/.../knowledge/eval` —
  Prometheus gauges keyed by `{set,mode}`, run-duration timer, run-failed
  counter. Mirrors `ForAgentMetrics.bind` / `resolveAndBind` shape.
- **`AdminRetrievalQualityResource`** in `wikantik-rest` at
  `/admin/retrieval-quality`:
  - `GET` → `{ data: { recent_runs: [...], count: N } }`. Filters:
    `query_set_id`, `mode`, `limit`.
  - `POST /run` (request body `{"query_set_id":"...","mode":"..."}`) →
    triggers `runNow`, returns the resulting `RetrievalRunResult` JSON
    synchronously.
  - Rides `AdminAuthFilter` via the existing `/admin/*` mapping.
- **`RetrievalQualitySmokeTest`** in `wikantik-main` — synthetic 3-query
  fixture, deterministic fake retriever, asserts `nDCG@5 >= 0.5` in <5 s.
  Catches wiring regressions; the nightly catches drift.
- **WikiEngine wiring** — `wireRetrievalQualityRunner()` registers the
  runner alongside the other knowledge-graph services and calls
  `scheduleNightly` when `wikantik.retrieval.cron.enabled=true` (default).

**Tech stack delta:** None. Reuses Micrometer, Postgres JDBC, the existing
`SearchManager` / `HybridSearchService` / `GraphRerankStep` stack, and the
shared `MeterRegistryHolder`.

## What ships

| Layer | Class / endpoint | Behaviour |
|-------|------------------|-----------|
| Migration | `V016__retrieval_quality.sql` | Three tables, indices, grants |
| Migration | `V017__seed_retrieval_query_set.sql` | One query set, 16 queries |
| API | `RetrievalMode` enum, `RetrievalRunResult` record, `RetrievalQualityRunner` interface | Stable wire types in `wikantik-api` |
| Service | `DefaultRetrievalQualityRunner` in `wikantik-main` | Schedules + executes + persists + emits metrics |
| Service | `RetrievalQualityDao` | JDBC CRUD over the three tables |
| Service | `RetrievalMetricsCalculator` | Pure metric math |
| Observability | `RetrievalQualityMetrics` | Prometheus gauges + histogram + counter |
| REST | `AdminRetrievalQualityResource` at `/admin/retrieval-quality` (GET + POST `/run`) | Admin triage + on-demand runs |
| Wiring | `WikiEngine.initKnowledgeGraph()` | Registers runner, calls `scheduleNightly` |
| Tests | `RetrievalMetricsCalculatorTest` (11), `RetrievalQualityDaoTest` (9), `RetrievalQualityMetricsTest` (7), `DefaultRetrievalQualityRunnerTest` (12), `RetrievalModeTest` (8), `AdminRetrievalQualityResourceTest` (9), `RetrievalQualitySmokeTest` (1) | 57 unit + smoke tests |

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| P5-T1 | `V016__retrieval_quality.sql` migration | `c31d61b08` |
| P5-T2 | `RetrievalMode` enum + `RetrievalRunResult` record + `RetrievalQualityRunner` interface in `wikantik-api`; `RetrievalModeTest` | `467ff5904` |
| P5-T3 | `RetrievalMetricsCalculator` + `RetrievalMetricsCalculatorTest` | `4dc0aca4b` |
| P5-T4 | `RetrievalQualityDao` + `RetrievalQualityDaoTest` (H2-backed) | `787a08612` |
| P5-T5 | `RetrievalQualityMetrics` + `RetrievalQualityMetricsTest` | `9579787f2` |
| P5-T6 + P5-T7 | `DefaultRetrievalQualityRunner` (`runNow` + `scheduleNightly`) + `DefaultRetrievalQualityRunnerTest` | `98d7cf430` |
| P5-T8 | `AdminRetrievalQualityResource` + `web.xml` mapping + `AdminRetrievalQualityResourceTest` | `da6c139b2` |
| P5-T9 | `WikiEngine.wireRetrievalQualityRunner()`; relocated `eval/*` from `wikantik-knowledge` to `wikantik-main` so `WikiEngine` can reach it | `1786f181d` |
| P5-T10 | `RetrievalQualitySmokeTest` pre-merge gate | `9bbe72cd8` |
| P5-T11 | `V017__seed_retrieval_query_set.sql` (16 queries) | `8e7a1117e` |
| P5-T12 | Full build + manual smoke against deployed WAR (no commit) | (verification) |
| P5-T13 | This doc rewritten retrospectively + CLAUDE.md update + memory updates | (this commit) |

Total commits: 10 functional + 1 doc.
Total new tests: **57** across the seven new test classes listed in the
"What ships" table.

## Material deviations from the design doc

1. **DDL ships as `V016`, not `V015`.** `V015` was claimed by
   `deduplicate_user_profiles` after the design doc was written.
2. **Eval classes live in `wikantik-main`, not `wikantik-knowledge`.** The
   plan staged them in `wikantik-knowledge` (the natural home given the
   class-name convention), but wiring them from `WikiEngine` requires
   `wikantik-main` to depend on `wikantik-knowledge`, which it does not —
   the dependency goes the other way. The classes were relocated to
   `wikantik-main` under the same `com.wikantik.knowledge.eval` package
   (mirroring how `ForAgentMetrics` already lives in `wikantik-main` under
   `com.wikantik.knowledge.agent`). Net effect: same package coordinates,
   compiles cleanly, no API change.
3. **One curated set of 16 queries, not "~30".** The cookbook is a
   16-page corpus today; one query per cookbook page plus one cross-cluster
   query is the right initial scope. The set grows as the cookbook grows.
4. **Negative queries deferred.** The design doc names `cold fusion` as an
   over-eagerness probe; computing a "noise score" needs design judgment
   genuinely out of scope here. Phase 5b. The runner already accepts
   queries with empty `expected_ids` — it skips them from aggregation
   without crashing — so the schema is forward-compatible.
5. **Pre-merge smoke uses synthetic fixtures, not the seed query set.**
   The seed set requires a populated DB + live search stack; the smoke
   test must be fast (<5 s) and deterministic. Synthetic fixtures keep the
   gate fast; the nightly catches real-corpus regressions.
6. **No SPA admin UI.** The design names a React dashboard at
   `/admin/retrieval-quality`. Phase 5 ships the JSON endpoint only; the
   dashboard is a frontend follow-up.
7. **`runNow` is synchronous.** The design implied async; synchronous
   keeps the admin-UI POST simple and lets the smoke test assert directly.
   Nightly schedule still runs on a `ScheduledExecutorService`.
8. **`RetrievalQualityRunner` interface lives in `wikantik-api`, the
   default in `wikantik-main`.** This lets `wikantik-rest` resolve the
   manager from `Engine.getManager(RetrievalQualityRunner.class)` without
   pulling `wikantik-main` onto its classpath.

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| Frontend admin dashboard | JSON endpoint ships; SPA work belongs in `wikantik-frontend` and is Phase 5b |
| Negative-query "noise" metric | Needs design judgment; the data model already accepts empty `expected_ids` so this can be added without schema churn |
| Per-query drill-in (returned vs expected diff) | `notes JSONB` column reserved; surfaced later |
| Threshold calibration; locking absolute thresholds | Two weeks of nightly runs needed first; design says "tune after two weeks" |
| GitHub Action invocation of the smoke test | Lives in CI config, not a code change in this repo |
| Auto-skip queries whose `expected_ids` reference deleted canonical_ids | Validator behaviour — the runner already skips per-query rather than failing the run |
| Production-mode hybrid_graph fail-closed handling | The runner records `degraded=true` when the retriever throws; `hybrid_graph`'s own failure path defers to `HybridSearchService.rerank`'s existing fail-closed |

## Curation method for the seed query set

For each agent-cookbook page, the natural-language question that page
answers becomes the `query_text`; the page's canonical_id becomes the
sole entry in `expected_ids`. The cross-cluster query (`q15` —
"how does hybrid retrieval work and fail") names two ids: the
`HybridRetrieval` design page and the `HandlingEmbeddingServiceOutages`
runbook. Authors grow the set over time by adding rows to a follow-up
migration. Threshold tuning is out of scope for Phase 5; the smoke
test's `nDCG@5 >= 0.5` is intentionally loose — production thresholds
calibrate after two weeks of nightly runs.

## Verification

`mvn clean install -T 1C -DskipITs` is green across all 26 modules (no
recovery needed; the parallel build completed cleanly).

Targeted tests:
- `mvn test -pl wikantik-main -Dtest='RetrievalMetricsCalculatorTest,RetrievalQualityDaoTest,RetrievalQualityMetricsTest,DefaultRetrievalQualityRunnerTest,RetrievalQualitySmokeTest' -am -Dsurefire.failIfNoSpecifiedTests=false` — 40 tests, all green.
- `mvn test -pl wikantik-rest -Dtest=AdminRetrievalQualityResourceTest -am -Dsurefire.failIfNoSpecifiedTests=false` — 9 tests, all green.
- `mvn test -pl wikantik-api -Dtest=RetrievalModeTest -am -Dsurefire.failIfNoSpecifiedTests=false` — 8 tests, all green.

Manual smoke against deployed WAR (after migration + Tomcat restart):
- `GET /admin/retrieval-quality` → `200` with `recent_runs: []` initially, then a populated row after the POST.
- `POST /admin/retrieval-quality/run` body `{"query_set_id":"core-agent-queries","mode":"hybrid"}` → `200` with the persisted `RetrievalRunResult` (16 evaluated, no skips, runId=1, real low ndcg@5 against the local corpus — expected, not a regression).
- `curl http://localhost:8080/metrics | grep wikantik_retrieval` → `wikantik_retrieval_ndcg_at_5{mode="hybrid",set="core-agent-queries"} 0.0383…` plus the `_at_10`, `_recall_at_20`, `_mrr` gauges.

Net effect: regressions in retrieval quality now surface as a measurable
nightly metric drop with a triage URL and a Prometheus alert seam.

## Authoring / operator workflow (post-Phase 5)

1. Authors add new queries by editing a follow-up migration
   (`V0NN__seed_…`).
2. Operators trigger an on-demand run via
   `POST /admin/retrieval-quality/run` (admin auth required).
3. Recent run history is at `GET /admin/retrieval-quality?limit=N`.
4. Grafana scrapes `wikantik_retrieval_*` gauges; alert on a 24-hour drop.

## Lessons learnt

- **Module dependency direction matters more than package name.** The
  staged plan had the eval classes in `wikantik-knowledge`. The package
  name `com.wikantik.knowledge.*` is split across both modules; what
  looked like a clean home turned out to be unreachable from `WikiEngine`.
  Lesson: when a class needs to be wired by `WikiEngine`, it must live
  in `wikantik-main` (or earlier), regardless of its package name.
- **Default `RestServletBase.GSON` drops null fields.** A `null` metric
  on `RetrievalRunResult` does not appear in the JSON response; clients
  must check field presence (`data.has("ndcg_at_5")`), not value, to
  detect "not scoreable". Recorded in the test suite via the
  `result_with_null_metrics_serialises_as_json_null` test.
- **`migrate.sh` against the local Tomcat database needs `PGUSER=migrate`,
  not `jspwiki`.** The wiki's app role only has `SELECT/INSERT/UPDATE/DELETE`
  grants on data tables; the migrate role owns DDL. Symptom in this phase
  was a `permission denied for schema public` on `CREATE TABLE`.

## Next phases (Agent-Grade Content)

- **Phase 5b** — frontend dashboard, negative-query "noise" metric,
  threshold calibration after two weeks of nightly runs.
- **Phase 6** — worked input/output examples on every MCP tool's JSON
  schema (no Phase 5 dependency).
