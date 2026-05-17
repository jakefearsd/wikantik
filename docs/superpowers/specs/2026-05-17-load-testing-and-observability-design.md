# Load-testing capability + host observability — design

- **Date:** 2026-05-17
- **Status:** Approved (brainstorm complete; implementation plan pending)
- **Author:** Jake Fear (with Claude)

## 1. Motivation

A monitoring audit found the "Wikantik — Overview" Grafana dashboard had four
empty panels (Page activity, Hybrid retrieval, Searches/s, Agent & admin
traffic). Investigation showed two causes: (a) the deployed build lagged the
dashboard, and (b) the ad-hoc `crawl_*.sh` scripts drove the wrong endpoints
(SPA shell routes, unauthenticated MCP) — they never exercised the instrumented
code paths.

That exposed a real gap: **there is no repeatable way to load-test Wikantik and
observe how the system and its host respond.** The existing observability
overlay scrapes only the app's own `/metrics`; nothing watches the docker1 host
or the containers. We want a tool usable on every release.

## 2. Goals / non-goals

**Goals**

- A repeatable, parameterised load generator that drives the *instrumented*
  endpoints and is safe to point at any environment.
- Host, container, and PostgreSQL observability so strain can be *attributed*
  during a load run.
- Offered load and system/host response on a shared timeline.
- High transparency into the vector-search path — a known performance
  blindspot.
- A self-verifying mode usable as a per-release dashboard smoke gate.

**Non-goals**

- Replacing integration tests. This is performance/observability tooling.
- Continuous synthetic monitoring. Runs are operator-initiated.
- Breakpoint capacity certification beyond a basic `stress` profile.

## 3. Architecture overview

One capability, three parts, delivered together:

- **Part B — Host & infra observability.** Three exporters added to the
  (opt-in) observability overlay + a new Grafana dashboard.
- **Part A — k6 load harness.** A k6 project plus a `bin/` wrapper.
- **Part C — Integration.** k6 metrics remote-written into Prometheus, plus
  Grafana run annotations, so offered load and host strain share an axis.

Three dashboards result, each a distinct lens:

| Dashboard                       | Lens                                      |
| ------------------------------- | ----------------------------------------- |
| Wikantik — Overview (existing)  | App: JVM, HTTP, wiki/hybrid/agent metrics |
| Wikantik — Host & Infra (new)   | Host, containers, PostgreSQL, vector path |
| (load-test row on Host & Infra) | Offered load from k6                      |

The observability overlay stays opt-in behind `WIKANTIK_OBSERVABILITY=1`; the
app and DB run unaffected when it is off.

## 4. Part B — Host & infra observability

### 4.1 Exporters

Added as services to `docker-compose.observability.yml`:

| Exporter          | Image                                           | Scope                                                                                                                                                                        | Requirements                                                   |
| ----------------- | ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| node_exporter     | `quay.io/prometheus/node-exporter`              | docker1 host: CPU by mode, load avg, RAM/swap, disk I/O + space, network, file descriptors, context switches                                                                 | `pid: host`; read-only mounts of `/proc`, `/sys`, `/`          |
| cAdvisor          | `gcr.io/cadvisor/cadvisor`                      | per-container CPU%, memory working set vs limit, network, block I/O                                                                                                          | read-only mounts of `/`, `/var/run`, `/sys`, `/var/lib/docker` |
| postgres_exporter | `quay.io/prometheuscommunity/postgres-exporter` | `pg_stat_*`: connections vs `max_connections`, commit/rollback rate, cache hit ratio, tuple ops, locks/deadlocks, DB size; plus a custom query for the embedding table/index | a `wikantik_exporter` DB role                                  |

These run only with the overlay enabled. A down exporter surfaces as a Prometheus
`up == 0` target and never affects the app or DB.

### 4.2 Prometheus scrape config

`docker/prometheus/prometheus.yml` gains three jobs — `node`, `cadvisor`,
`postgres` — targeting the new services on the shared compose network. The
existing `wikantik` and `prometheus` jobs are unchanged.

### 4.3 PostgreSQL monitoring role

A new numbered migration `bin/db/migrations/V031__monitoring_role.sql`:

- Idempotently creates a `wikantik_exporter` LOGIN role and grants it the
  built-in `pg_monitor` role (full `pg_stat_*` visibility without superuser).
- DDL only. The role password is supplied at apply time through a psql
  variable, the same mechanism the existing migrations use for `:app_user`
  grants — **no secret is committed**. The password is sourced from the
  observability env.
- Verified as a no-op on re-apply (`DO $$ … IF NOT EXISTS`).

`postgres_exporter` connects with this role via a `DATA_SOURCE_NAME` built from
the observability env.

### 4.4 New dashboard — "Wikantik — Host & Infra"

`docker/grafana/dashboards/wikantik-host.json`, auto-provisioned (the existing
`provider.yml` provisions the whole dashboards directory, so a second file is
picked up with no provisioning change). Rows:

- **Scrape health** — `up{}` stat panels for every job, so a missing exporter
  is obvious at a glance.
- **Host** — CPU utilisation stacked by mode; load average 1/5/15; memory
  used/available/swap; disk I/O throughput, IOPS, and `%util`; disk space;
  network rx/tx; open file descriptors.
- **Containers** — per-container CPU%, memory working set vs limit, network,
  block I/O — focused on the `wikantik` and `db` containers.
- **PostgreSQL** — active vs idle connections against `max_connections`;
  transaction rate; cache hit ratio; tuple throughput; locks/deadlocks; DB
  size.
- **Hybrid / Vector search** — see §6.

## 5. Part A — k6 load harness

### 5.1 Layout

```
loadtest/
  wikantik-load.js        # k6 entry: options (scenarios/thresholds), setup, default, teardown
  lib/config.js           # env parsing, profile definitions, defaults
  lib/endpoints.js        # request helpers: viewPage, search, mcpCall, toolsCall, writeCycle
  lib/metrics.js          # scrape /metrics, parse Prometheus text, verify diff + PASS/FAIL table
  lib/slugs.js            # pull live page slugs from the target, fallback to bundled list
  metrics-parse.test.mjs  # node --test over the Prometheus-text parser
  loadtest.env.example    # credentials template (real loadtest.env is gitignored)
  README.md
bin/loadtest.sh           # wrapper: --help, k6-installed check, loads creds, profile -> k6 run
```

Each `lib/` module has a single responsibility and a small interface;
`metrics.js` holds the only nontrivial logic and is a pure parser, unit-tested
standalone.

### 5.2 Profiles

`bin/loadtest.sh <profile>` where profile ∈ `smoke | load | stress`:

- **smoke** (default) — ~2 min, ~5 VUs, low RPS; guarantees every instrumented
  endpoint is hit at least once. The per-release gate; pairs with `--verify`.
- **load** — sustained, ramping VUs (ramp-up / hold / ramp-down); `--duration`
  and `--vus` overridable. Steady-state performance testing.
- **stress** — staged ramp past expected capacity until thresholds break;
  surfaces the breakpoint.

Each profile sets k6 `thresholds` (`http_req_failed` rate, p95 latency); a
breached threshold makes k6 exit non-zero (99).

### 5.3 Traffic model

The mix drives the **instrumented** paths the ad-hoc crawler missed. Read mix,
weighted:

- **~55% page view** — `GET /api/pages/{slug}` (fires `PAGE_REQUESTED`); a
  slice via `GET /wiki/{slug}?format=md` to exercise `WikiPageFormatFilter`.
- **~25% search** — `GET /api/search?q={term}` (drives the `/api/search` timer
  and the hybrid query embedder).
- **~15% MCP** — `POST /knowledge-mcp` and `/wikantik-admin-mcp`, real
  JSON-RPC (`tools/list`, `tools/call`) with a Bearer key.
- **~5% tools** — `GET /tools/*` (lights up `wikantik_tools_requests_total`).

Page slugs are pulled live from the target during `setup()` via `/api/changes`,
falling back to a bundled list — never stale, works against any target.

### 5.4 Opt-in writes

`--writes` adds a separate low-rate scenario: `POST /api/auth/login` →
`PUT /api/pages/LoadTest/k6-{vu}-{iter}` (create then edit) → `DELETE`. All
pages are scoped under `LoadTest/` and deleted in `teardown` unconditionally,
even if the run aborts. Default runs are read-only and safe to point at prod.

### 5.5 `--verify` (metrics-delta gate)

`setup()` scrapes `/metrics` into a baseline; `teardown()` scrapes again, diffs,
prints a PASS/FAIL table; any target metric that did not move throws, producing
a non-zero exit.

| Panel                      | Metric                                                                                                     | Pass condition |
| -------------------------- | ---------------------------------------------------------------------------------------------------------- | -------------- |
| Page activity              | `wikantik_page_views_total`                                                                                | Δ > 0          |
| Searches/s                 | `http_server_requests_seconds_count{uri="/api/search"}`                                                    | Δ > 0          |
| Hybrid retrieval           | `wikantik_search_hybrid_embedder_calls_total`                                                              | Δ > 0          |
| Hybrid retrieval           | `wikantik_search_hybrid_vector_index_size`                                                                 | present, > 0   |
| Agent & admin traffic      | `http_server_requests_seconds_count{uri=~"/knowledge-mcp\|/wikantik-admin-mcp"}`                           | Δ > 0          |
| Page activity (`--writes`) | `wikantik_page_edits_total`, `wikantik_page_deletes_total`, `wikantik_auth_logins_total{result="success"}` | Δ > 0          |

**Reachability constraint.** `/metrics` is `InternalNetworkFilter`-blocked from
external IPs. `--verify` therefore only works run from inside the network. The
harness takes a separate `--metrics-url` (default `BASE_URL/metrics`) so load
can be driven at a public URL while metrics are scraped at an internal one
(e.g. run on docker1, drive `https://wiki.jakefear.com`, scrape
`http://localhost:8080/metrics`). When the metrics endpoint is unreachable,
`--verify` fails fast with a clear message rather than a false negative.

### 5.6 Auth & secrets

No secrets are embedded. `bin/loadtest.sh` loads, in order: a gitignored
`loadtest.env` at the repo root, then falls back to `test.properties` for local
admin credentials. Required keys: `BASE_URL`, `LOADTEST_ADMIN_USER`,
`LOADTEST_ADMIN_PASS`, `LOADTEST_MCP_KEY` (and the tool-server key for `/tools/*`).
A profile that needs a missing credential fails fast, naming the key. A `401`
from MCP is surfaced as a **warning** in smoke output so an unscoped or stale
key is not mistaken for success.

## 6. Vector-search transparency (cross-cutting)

Wikantik's *query-time* vector search runs in-memory in the JVM
(`InMemoryChunkVectorIndex`); PostgreSQL/pgvector is the persistence and
bootstrap side. Transparency therefore spans three layers, surfaced together in
the **Hybrid / Vector search** row of the Host & Infra dashboard:

- **Embedder hot path (app).** Existing metrics — `wikantik_search_hybrid_embedder_calls_total`
  by result, cache hit/miss, breaker transitions and rejections, circuit state.
  Plus **one new metric**: a `wikantik.search.hybrid.embedder.latency` Timer
  recorded around the query-embed call and registered through
  `HybridMetricsBridge`. The embedder today exposes only counts, no latency
  distribution — that is the key missing signal for "is query embedding slow".
  This is the **only app-code change** in this spec.
- **In-memory index (JVM).** `wikantik_search_hybrid_vector_index_size`
  correlated with JVM heap and GC from the Overview dashboard's registry.
- **pgvector storage (DB).** A postgres_exporter custom query
  (`docker/postgres-exporter/queries.yaml`) over `pg_stat_user_tables` /
  `pg_statio_user_indexes` for the embedding chunk table and its vector index:
  row count, table and index size, index scan rate, and heap/index block cache
  hit ratio — so load-time vector index behaviour is no longer a blindspot.

## 7. Part C — Integration

- **k6 → Prometheus remote-write.** k6 runs with
  `--out experimental-prometheus-rw`; Prometheus is started with
  `--web.enable-remote-write-receiver`. k6's own metrics — offered RPS, active
  VUs, client-observed latency percentiles, error rate — land in Prometheus
  under a `k6` job.
- **Load-test row.** The Host & Infra dashboard gets a top row plotting those
  k6 metrics, so offered load sits on the same time axis as host/container/DB
  strain — making it visible exactly where latency degrades and which resource
  saturated.
- **Run annotations.** `bin/loadtest.sh` posts a Grafana region annotation at
  run start and end (via the Grafana HTTP API, token from `loadtest.env`), so
  every dashboard shows a shaded "load test" band. Annotation posting is
  best-effort — a failure warns but never fails the run.

## 8. Error handling

- Exporters are overlay-only; the app/DB are unaffected when the overlay is off
  or an exporter is down (visible as `up == 0`).
- `bin/loadtest.sh`: k6 not installed → install hint, exit 2; `--dry-run`
  prints the `k6 run` command without executing; missing credentials → fail
  fast naming the key.
- `--verify`: unreachable metrics endpoint → fail fast (not a false negative).
- `--writes`: cleanup is idempotent and runs in `teardown` unconditionally.
- Grafana annotation failures are best-effort and only warn.

## 9. Testing

- `metrics-parse.test.mjs` — `node --test` over the Prometheus-text parser in
  `lib/metrics.js`, against a bundled sample-`/metrics` fixture.
- The embedder-latency Timer addition ships with a unit test asserting the
  meter is registered and records.
- The harness's own integration test: `bin/loadtest.sh smoke --verify` against
  a local deploy.
- Post-overlay-bringup smoke check: assert all scrape jobs report `up`
  (folded into `bin/container.sh smoke-test` or documented in the README).

## 10. Implementation phases

1. **Phase B — observability stack.** Exporters, Prometheus jobs, `V031`
   migration, `wikantik-host.json` (host/container/Postgres rows), pgvector
   custom query.
2. **Phase A — k6 harness.** `loadtest/` project, `bin/loadtest.sh`, profiles,
   traffic model, `--writes`, `--verify`, parser unit test.
3. **Phase C — integration.** Embedder-latency Timer, k6 remote-write,
   Prometheus receiver flag, the load-test dashboard row, Grafana annotations.

## 11. Files created / modified

**Created**

- `loadtest/` — k6 project (entry, `lib/`, parser test, env example, README)
- `bin/loadtest.sh`
- `docker/grafana/dashboards/wikantik-host.json`
- `docker/postgres-exporter/queries.yaml`
- `bin/db/migrations/V031__monitoring_role.sql`

**Modified**

- `docker-compose.observability.yml` — three exporter services
- `docker/prometheus/prometheus.yml` — three scrape jobs; remote-write receiver
  noted in compose flags
- `HybridMetricsBridge` + `QueryEmbedder` — embedder-latency Timer
- `.gitignore` — `loadtest.env`
- `docs/DockerDeployment.md` / `CLAUDE.md` — load-testing and host-dashboard
  notes
- `docs/wikantik-pages/News.md` — per the commit-log convention

## 12. Open items / future

- pgvector ANN query latency is not directly timed (query-time search is
  in-memory). The embedder Timer plus JVM heap/GC correlation is the proxy; a
  dedicated in-memory-search Timer could follow if the proxy proves
  insufficient.
- `pg_stat_statements` is not enabled in this spec; if per-query DB visibility
  is later wanted it is the natural follow-up.
- The `stress` profile finds a breakpoint but does not certify capacity; formal
  capacity planning is out of scope.
