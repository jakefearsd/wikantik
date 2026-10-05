# Wikantik Operations Handbook

This handbook is for administrators who run Wikantik. It covers deployment (container, remote and bare metal), tuning, the health and metrics endpoints, backups, the operator scripts under `bin/` (including remote deployment, load testing and the entity extractor), and Knowledge Graph administration.

---

## 1. System Configuration & Deployment

Wikantik supports two primary deployment strategies: a bare-metal Tomcat 11 installation and a fully containerized Docker architecture. 

### 1.1 Configuration

Configuration is documented in full elsewhere — this handbook does not
duplicate it. For the container `.env` file, every `WIKANTIK_*`/container env
var, and how each maps to a `wikantik.*` property, see
[DockerDeployment.md](DockerDeployment.md). For the complete, generated
reference of every `wikantik.*` property with its default and type, see
[ConfigurationReference.md](../ConfigurationReference.md).

### 1.2 Containerized Deployment (Recommended for Production)
The production Docker stack (`docker-compose.yml` plus `docker-compose.prod.yml`) and its persistent state are described once, in [DockerDeployment.md](DockerDeployment.md#services) (services) and [DockerDeployment.md](DockerDeployment.md#2-data-persistence) (volumes and bind mounts). Operator notes that are not in that guide:

- `wikantik-work` also holds the ontology TDB2 store (`${wikantik.workDir}/ontology-tdb2`), which grows unboundedly: copy-on-write B+Trees never shrink on their own, so the nightly rebuild plus per-save incremental sync only ever add (measured about 1.35 GB/day in production; it once reached 94.6 GB before being cleared). A weekly compaction pass (`wikantik.ontology.compaction.interval.hours`, default 168) reclaims space without a full rebuild. The directory is always safe to delete: `OntologyWiringHelper` calls `coordinator.rebuildIfEmpty()` on every startup, so a missing or empty store rebuilds on the next boot.

**Rollback:** `bin/remote.sh deploy` rolls back automatically when the post-deploy health poll fails, and `bin/remote.sh rollback` does it by hand; see [DockerDeployment.md](DockerDeployment.md#roll-back). There is no CI deploy pipeline; the developer box drives all production changes.

### 1.2.1 Pull-based updates (cloud VM targets)

docker1's `bin/remote.sh deploy` streams the image over ssh
(`docker save | ssh docker load`) — a LAN-bandwidth assumption. A cloud VM
(AWS/GCP — see [CloudDeployment.md](CloudDeployment.md)) has its own GHCR
registry access instead, so two pull-based paths exist:

- **From the dev box, over ssh** — `bin/remote.sh deploy --pull TAG`
  rsyncs the compose files + `.env` to the remote as usual, then has the
  remote run `docker pull ghcr.io/jakefearsd/wikantik:TAG` + retag to
  `wikantik:latest` instead of the save/load transfer. Everything else
  (deploy lock, health-poll, auto-rollback on failure) is identical to a
  normal `deploy`. Point it at a second target without touching docker1's
  `remote.env` via the `REMOTE_ENV_FILE` override:
  ```bash
  REMOTE_ENV_FILE=remote-aws.env bin/remote.sh deploy --pull 2.3.8
  ```
- **On the VM itself, no ssh round-trip** — `wikantik-update` (installed by
  cloud-init at `/usr/local/bin/wikantik-update`, source
  `deploy/bin/wikantik-update.sh`, config at `/etc/wikantik-update.conf`):
  ```bash
  ssh ubuntu@<vm-ip>
  sudo wikantik-update 2.3.8
  ```
  Flow: a non-blocking `flock` on `${WIKANTIK_REPO_DIR}/.update.lock` (a
  concurrent invocation — e.g. a cron-driven update racing a human one —
  fails fast with exit 2) → `docker login` if `GHCR_USER`/`GHCR_TOKEN` are
  configured → `docker pull` the target image → tag the currently-running
  image `wikantik:rollback` → back up `.env` to `.env.bak` (manual-recovery
  convenience only) and rewrite `WIKANTIK_IMAGE` → `docker compose up -d` →
  poll `HEALTH_URL` (default `http://localhost:8080/api/health`) every 3s up
  to `HEALTH_TIMEOUT` (default 180s). On failure, it restores the previous
  `WIKANTIK_IMAGE` value (captured in memory, not from `.env.bak`),
  force-recreates just the `wikantik` service, and prints the last 50
  `wikantik` log lines before exiting 1. `--dry-run` prints every step
  without touching Docker or the filesystem.

Both paths implement the same retag-then-swap-then-health-poll-then-
auto-rollback discipline as a normal `deploy`; pick `remote.sh --pull` to
drive a cloud VM from your existing docker1 workflow, or `wikantik-update`
to run the upgrade directly on the box (e.g. from a cron job or a CI
runner with only registry access, no ssh key to the dev box).

### 1.3 Bare-Metal Deployment
Deploying to a bare-metal server runs Wikantik as the ROOT context of a local
Tomcat 11 instance against a local PostgreSQL. It is the path for development,
manual testing, and single-host installs (production runs the container — §1.2).
The full step-by-step guide is
[PostgreSQL.md](PostgreSQL.md); the essentials:

1. **Database** — `sudo -u postgres bin/db/install-fresh.sh` creates the database,
   the `wikantik` app role, and applies every migration. **Set `DB_MIGRATE_PASSWORD`**
   so it also runs `bin/db/create-migrate-user.sh`, which provisions the dedicated
   `migrate` role with the privileges migrations need (`CREATEROLE` + `pg_monitor`
   for V031, plus ownership of the schema). Skipping this leaves migrations to fail
   later as an under-privileged role; see [DatabaseMigrations.md](DatabaseMigrations.md).
2. **Build** — `mvn clean install -DskipTests -T 1C`.
3. **Deploy** — `bin/deploy-local.sh` downloads Tomcat (first run), materialises
   config from the templates, deploys the WAR, runs migrations, starts Tomcat.
4. **Iterate** — `bin/redeploy.sh` is the fast path (swap WAR + restart only).

**Config is write-once.** `deploy-local.sh` materialises each templated config
file (`ROOT.xml`, `wikantik-custom.properties`, `setenv.sh`, `conf/server.xml`,
`conf/context.xml`) on first deploy and **does not overwrite it afterward** (the
server/context guards overwrite only while the file is still stock). So changes to
the git-tracked templates — including the perf knobs in §1.5 — **do not reach an
existing install automatically.** Apply them by hand to the deployed file, or
delete the deployed file and re-run `deploy-local.sh` to re-render it. This is
deliberate (it protects the DB password in `ROOT.xml`), but it means a long-lived
bare-metal box can silently drift from the tuned defaults.

### 1.4 Health, metrics and request correlation

The `wikantik-observability` module ships three runtime capabilities in every
environment. Monitoring itself (scraping, dashboards, alerting) belongs to the external
**jakemon** stack: a Grafana Alloy agent on each host pushes metrics and logs to the
central Prometheus, Loki and Grafana on host `docker2`. There is no in-repo
observability stack.

**Health: `GET /api/health`.** `HealthServlet` runs the registered checks and returns
JSON with an overall `status` and a `checks` map: `EngineHealthCheck` (engine
initialised), `DatabaseHealthCheck` (the JNDI DataSource is reachable) and
`SearchIndexHealthCheck` (the Lucene index is available). The overall status is `UP`,
`DEGRADED` or `DOWN`; the HTTP status is 200 for `UP` and `DEGRADED` and 503 for `DOWN`
(or when no checks are registered because the engine has not started).

```bash
curl http://localhost:8080/api/health | jq
```

**Metrics: `GET /metrics`.** `MetricsServlet` serves Prometheus text format. Both
`/api/health` and `/metrics` sit behind `InternalNetworkFilter`, which allows only
loopback (`127.0.0.0/8`, `::1`) and the RFC 1918 ranges (`10.0.0.0/8`, `172.16.0.0/12`,
`192.168.0.0/16`); any other client gets 403 with `{"error":"Forbidden"}`. The check uses
`request.getRemoteAddr()`, which in the container is the real client address after
`RemoteIpValve` has applied `PROXY_REMOTE_IP_HEADER`. The backpressure filter exempts both
paths and the rate limiter exempts `/api/health`, so monitoring never sees a false outage.

```bash
curl http://localhost:8080/metrics | grep '^wikantik_'
```

**Request correlation: `X-Request-Id`.** `RequestCorrelationFilter` reads an incoming
`X-Request-Id` header (for example from a reverse proxy) or generates a UUID, returns it
as a response header, and puts `requestId`, `method`, `uri`, `remoteAddr` and `userAgent`
into the Log4j2 `ThreadContext`, so every log line for the request carries the same key.
In the container, the console appender (`docker/config/log4j2-docker.xml`) writes
structured JSON using the ECS template with an added `application=wikantik` field, which
is what the jakemon agent collects. The cross-product telemetry contract (metric prefix,
log envelope, `correlation_id`) is documented on the live wiki page `SimpleAgilityTelemetryContract`;
check a running instance against it with:

```bash
bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik
```

Logging configuration is covered in [LoggingConfig.md](LoggingConfig.md).

### 1.5 Performance & concurrency tuning

The reference target is the 16-core / 32 GB docker1 host. These are the knobs
that matter for throughput and overload behavior, with the reasoning behind each
value.

**Where each knob lives, per deployment path.** In the **container**, most are
environment variables consumed by `docker/entrypoint.sh` (which writes them into
the generated `ROOT.xml`), and the Tomcat connector lives in
`docker/config/server.xml`. On **bare-metal**, the same values come from the
git-tracked templates in `wikantik-war/src/main/config/tomcat/` that
`bin/deploy-local.sh` materialises: the connector in `Tomcat-server.xml.template`
(`conf/server.xml`), the DBCP pool in `Wikantik-context.xml.template` (`ROOT.xml`),
the backpressure cap documented in `setenv.sh.template` (`bin/setenv.sh`), and the
dense-retrieval/HNSW knobs in `wikantik-custom-postgresql.properties.template`.
**Caveat:** these templated files are write-once — `deploy-local.sh` will not
overwrite a config that already exists, so template changes do **not** reach an
existing bare-metal install. Apply them to the deployed file by hand, or delete it
and re-run `deploy-local.sh`. (Full bare-metal guide:
[PostgreSQL.md](PostgreSQL.md).)

**Dense retrieval**

| Setting | Value | Why |
|---|---|---|
| `WIKANTIK_DENSE_BACKEND` (`wikantik.search.dense.backend`) | `lucene-hnsw` | In-process Lucene HNSW ANN; replaced the brute-force scan that was ~60 % of search CPU. Alternatives: `inmemory` (exact brute force, the rollback), `pgvector` (server-side, for split-DB topologies). |
| `wikantik.search.dense.lucene.m` / `.ef_construction` / `.ef_search` | 16 / 64 / 100 | HNSW graph degree, build beam width, query candidate pool. Match the pgvector index; held parity within 0.02 nDCG@5 of brute force. |

Rollback is a one-line flip to `inmemory` (rebuilds from the same `bytea` column);
the index is held in RAM and rebuilt on boot from `content_chunk_embeddings`.

**Admission control & concurrency**

| Setting | Value | Why |
|---|---|---|
| `WIKANTIK_MAX_INFLIGHT_REQUESTS` (backpressure semaphore) | **390** | **Must be below Tomcat `maxThreads`** — the `BackpressureFilter` holds permits on worker threads, so a cap ≥ `maxThreads` can never fire (the old default of 700 was inert). 390 sheds `503 + Retry-After` when ~390 of 400 threads are busy, reserving a few to fast-serve the rejections. `0`/negative disables the filter. |
| Tomcat `maxThreads` (`server.xml`) | 400 | Capped deliberately — bumping to 600 oversubscribed this CPU-bound 16-core host (context-switch overhead beat the gain). |
| Tomcat `acceptCount` | 200 | Connection queue behind the worker pool. |

`/api/health` and `/metrics` bypass the semaphore so monitoring never sees a
false outage; `wikantik_backpressure_rejected_total` counts the shed.

**Public-surface rate limiting** (`RateLimitFilter`, `wikantik-observability`)

A two-tier per-IP sliding-window limiter (algorithm in `wikantik-http`'s
`SlidingWindowRateLimiter`) fronts the public HTTP surface, protecting the
single-host box from compute-amplification abuse. It is distinct from
backpressure: backpressure sheds by *concurrency* (in-flight threads); this sheds
by *rate* (requests/second per client IP). The client IP is the real caller —
Tomcat's `RemoteIpValve` resolves `CF-Connecting-IP` behind Cloudflare. Default-on;
it disables itself only when **both** per-client limits are set ≤ 0.

| Setting (env var) | Default | Applies to | Why |
|---|---|---|---|
| `WIKANTIK_RATELIMIT_DEFAULT_PERCLIENT` | **25** req/s | `/api/*`, `/id/*`, `/export/*` (default tier) | Generous per-client ceiling; no global cap (the backpressure semaphore bounds the aggregate). |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PERCLIENT` | **3** req/s | the expensive paths below | Tighter per-client ceiling for compute-heavy work (dense retrieval, SPARQL materialization). |
| `WIKANTIK_RATELIMIT_EXPENSIVE_GLOBAL` | **10** req/s | the expensive paths below | Single-host **global** cap across all clients — one abuser can't monopolise the retrieval/ontology CPU. |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PATHS` | `/api/bundle,/api/search,/sparql` | — | CSV path prefixes routed to the expensive tier. |
| `WIKANTIK_RATELIMIT_EXEMPT_CIDRS` | *(empty)* | — | CSV IPv4 CIDRs exempt from all limits. Loopback is **always** exempt, and the exact path `/api/health` is never limited. |

On a limit hit the filter returns `429` + `Retry-After: 1`, emits a `SecurityLog`
line, and increments `wikantik_ratelimit.rejected_total{tier=default|expensive}`.
The filter is ordered **after** `RequestMetricsFilter`, so rejected requests are
still counted in the request metrics.

**Database connection pool** (DBCP, in the generated `ROOT.xml`)

| Setting | Value | Why |
|---|---|---|
| `maxTotal` | 90 | Pressed just under Postgres `max_connections` (100, default). Was the throughput ceiling until per-request DB hits were cached; **PgBouncer** is the lever to grow past this. |
| `maxWaitMillis` | 5000 (5 s) | How long a request waits for a connection before failing. Cut from 10 s once the pool stopped being the bottleneck — a long wait now signals real trouble, so fail fast and free the thread. |
| `maxIdle` | 30 | Idle connections kept warm. Same value in both the container (`docker/entrypoint.sh`) and the bare-metal template (`Wikantik-context.xml.template`) — there is no dev/prod split. |

**Per-request caches** (short-TTL Caffeine; each removed a DB connection from the
hot path that caused pool exhaustion under load)

| Cache | TTL | Why |
|---|---|---|
| API-key verify (`ApiKeyService`) | 60 s | Removed 2 DB connections per authenticated MCP/tools request; `revoke()` evicts immediately so revocation stays instant. |
| User lookup (`JDBCUserDatabase.findByLoginName`) | 60 s | Removes the per-request basic-auth DB read; evicted on save/rename/delete. |
| KG mention related-pages (`MentionIndex`) | 5 min | Per-search KG join; relationships change slowly. |

**Diagnosing concurrency stalls.** If latency climbs while CPU stays moderate,
threads are blocking on a shared resource, not computing. Capture worker-thread
state under load and look at what they wait on:

```bash
# 5 dumps a few seconds apart while a load test runs
for i in 1 2 3 4 5; do
  docker exec repo-wikantik-1 jcmd 1 Thread.print > dump_$i.txt; sleep 6
done
# count workers parked acquiring a DB connection (pool exhaustion)
grep -c 'GenericObjectPool.borrowObject' dump_3.txt
# the most-contended monitor (lock hotspot)
grep -oE 'waiting to lock <0x[0-9a-f]+>' dump_3.txt | sort | uniq -c | sort -rn | head
```

Cross-reference host CPU from jakemon's Prometheus
(`100*(1-avg(rate(node_cpu_seconds_total{instance="docker1",mode="idle"}[2m])))`).
The full diagnostic chain and methodology live in
[ScalingCharacterization.md](../developer/ScalingCharacterization.md) and
[LoadTesting.md](../developer/LoadTesting.md).

### 1.6 API write-path limits

`PageResource` (`PUT`/`POST /api/pages`) enforces two operator-tunable limits,
both set via a `wikantik-custom.properties` override — neither has a
container env var:

| Property | Default | Behavior |
|---|---|---|
| `wikantik.api.maxPageBytes` | `262144` (256 KiB) | Maximum UTF-8 byte size of a page body. A larger body is rejected before it reaches the storage layer with **413 Payload Too Large**, naming the actual size, the limit, and the property to raise it. |
| `wikantik.api.write.requireExpectedVersion` | `false` | Opt-in optimistic-concurrency guard. When `true`, a `PUT` that omits `expectedVersion` in its JSON body is rejected with **400 Bad Request** instead of silently overwriting a concurrent edit. Off by default for backward compatibility with clients that don't send it. |

### 1.7 System-page registry

`SystemPageRegistry` decides which page names count as system/template pages
(menu fragments, help pages, CSS theme pages) — write-protected from the MCP
`update_page` tool by default, and excluded from KG extraction. Two
properties extend the defaults without a code change:

| Property | Default | Behavior |
|---|---|---|
| `wikantik.systemPages.extraPatterns` | *(empty)* | Comma-separated regexes; page names matching any of them are additionally treated as system pages, on top of the built-in set. |
| `wikantik.systemPages.mcpEditable` | `About` | Comma-separated **exact** page names that stay editable via the MCP `update_page` tool despite being system pages. An explicit empty value locks every system page against MCP writes, including `About`. Destructive operations (delete, rename) stay blocked for all system pages regardless of this setting. |

This is the mechanism behind letting MCP edit `About` (CHANGELOG 2.3.5): to
open a second page to MCP curation, add its exact name to
`wikantik.systemPages.mcpEditable`, e.g.
`wikantik.systemPages.mcpEditable = About,Welcome`.

### 1.8 Cluster taxonomy — declaration & enforcement

A cluster exists iff exactly one page declares it: `type: hub` plus a scalar
`cluster: <path>` in frontmatter. Non-hub pages may belong to one or more
clusters (`cluster:` is scalar-or-list there); the first entry is the primary
and drives breadcrumbs, JSON-LD placement, and sidebar location.

- **Duplicate-declaration enforcement ships dark.**
  `wikantik.cluster_declaration.enforcement.enabled` (`wikantik-custom.properties`
  override, in `StructuralSpinePageFilter`) defaults to **`false`**. While off,
  two hub pages can both declare the same `cluster:` path and both saves
  succeed — the conflict only shows up as a WARNING on `/admin/drift`. Turning
  it on makes a duplicate declaration a save-time **422**, so confirm
  `/admin/drift` reports zero duplicate declarations before flipping it —
  otherwise every hub page sharing that duplicate becomes un-saveable until
  one of them is renamed off the path.
- **Bulk rename:** `POST /admin/clusters/rename?from=<path>&to=<path>[&confirm=true]`
  (`AdminClusterResource`) rewrites the `cluster:` frontmatter of every member
  page. Omitting `confirm=true` (or passing `confirm=false`) returns the
  rewrite **plan** without applying it — that's the expected response, not an
  error. A `to` path another hub already declares is refused with `409`
  before any write. The same operation is available as the `rename_cluster`
  tool on `/wikantik-admin-mcp`.

### 1.9 The checkout and production are different corpora

`docs/wikantik-pages/` in the repository and the production page store are different
corpora, not two copies of one. Production holds pages the repository lacks.
`bin/remote.sh pages-push` writes the repository tree onto the remote but does not
reconcile the two, and `pages-pull` cannot: it fails `Permission denied` on
container-owned pages and silently returns a partial corpus, which is worse than none
because every unread page then looks missing from production.

Production is authoritative for content; the checkout is a mirror. Derive corpus-wide
plans from the live index (the `list_clusters` and `list_pages_by_filter` tools on
`/knowledge-mcp`), never from the repository, and measure the gap with
`CorpusDivergenceCli` (in `wikantik-extract-cli`), which compares the repository corpus
with a live wiki's `/api/structure/sitemap`. It exits 2 when it refuses because the
snapshot it was given was incomplete, and 1 when divergence is found under `--check`.

---

## 2. Backup & Disaster Recovery

See **[BackupAndRecovery.md](BackupAndRecovery.md)** for the complete guide: the 3-2-1 topology
(live data → docker1 tiered snapshots → off-box NAS archive), the trust model (NAS pulls, docker1
holds no NAS credentials), the full restore procedure, the `bin/backup/verify-restore.sh` restore
drill, and the exact jakemon alert expressions to configure.

---

## 3. Administrative Scripts (`bin/`)

The `bin/` directory contains operational scripts to manage the Wikantik lifecycle and Knowledge Graph (KG).

### `bin/container.sh`
The primary wrapper around `docker compose` for the container stack.
- `build`, `up -d`, `down`: Standard stack orchestration.
- `backup [TIER]`: Triggers an ad-hoc backup inside the prod sidecar.
- `restore PATH`: Restores DB and content from a snapshot path.
- `psql`: Opens an interactive PostgreSQL shell in the DB container.
- `smoke-test`: Spins up the test stack (base + `docker-compose.test.yml` overlay, project `wikantik-test`, port 18080) to verify health checks before tear down.
- `migrate [--status]`: Runs `bin/db/migrate.sh` inside the live `wikantik` container.

Environments are `dev` (default), `prod`, `test` and `base`; each subcommand accepts `--help`.

```bash
bin/container.sh build                          # build the image
bin/container.sh up -d                          # start the dev stack
bin/container.sh logs -f                        # tail wikantik
bin/container.sh psql -- -c '\dt'               # list DB tables
bin/container.sh -e prod up -d                  # production stack with backup sidecar
bin/container.sh smoke-test                     # ephemeral up/health/down on test ports
```

### `bin/deploy-local.sh`
Handles bare-metal Tomcat deployments.
- Renders `context.xml` and properties templates using `.env`.
- `--upgrade-tomcat`: Performs an in-place upgrade, preserving managed configs and data directories safely.

### `bin/kg-rebuild.sh`
Orchestrates the full content and Knowledge Graph rebuild pipeline across multiple phases.
- Phase 1: Rebuild chunks and Lucene index.
- Phase 2: Reindex embeddings.
- Phase 3: Optional reset (`--reset-kg`) to prune AI-inferred states or a complete destructive wipe (`--purge-kg`).
- Phase 4: Forward requests to `bin/kg-extract.sh` to extract mentions and proposals.

**Resume flags** (skip completed phases when resuming mid-pipeline):

| Flag | Skips |
|------|-------|
| `--skip-chunks` | Phase 1 (chunk + Lucene rebuild) |
| `--skip-embeddings` | Phase 2 (embedding reindex) |
| `--skip-extract` | Phase 4 (entity extraction) |
| `--dry-run` | Everything — prints the plan without executing |

```bash
# Resume after a failed embedding phase (chunks already rebuilt):
bin/kg-rebuild.sh --skip-chunks --reset-kg -- --ollama-model qwen2.5:1.5b-instruct --concurrency 6
```

### `bin/kg-extract.sh`
Fires the standalone entity-extractor CLI against the database to generate Knowledge Graph nodes and proposals.
- Supports tuning parameters like `--max-pages`, `--ollama-model`, and `--concurrency`.
- Recompiles the `wikantik-extract-cli.jar` transparently if Java source files have changed.

### `bin/kg-policy.sh`
Admin CLI for managing the Knowledge Graph cluster inclusion/exclusion policies.
- Controls what namespaces are analyzed by the extractor (system pages are automatically excluded).
- Commands include `list`, `set`, `explain`, and `purge`.

### `bin/kg-judge.sh`
Triggers ad-hoc Knowledge Graph judge runs against the local deployment.
- `--proposal-id UUID`: Synchronously judge one proposal.
- `--status`: Evaluate pending queue depth.

### Remote container deployment over ssh

`bin/remote.sh` is the single entry point for deploying and administering Wikantik on a
remote host. It wraps `bin/container.sh` on the remote and adds image transfer
(`docker save | ssh 'docker load'`), page rsync and a deploy lock. Configuration lives in
`remote.env` at the repository root (copy from `remote.env.example`; gitignored); the
production container config is a gitignored `.env.prod`, which `remote.sh` ships to the
remote as `.env` in preference to the dev `.env`. Every state-changing subcommand accepts
`--dry-run`.

```bash
bin/remote.sh --help                          # subcommand list
bin/remote.sh bootstrap                       # first-time remote setup
bin/remote.sh deploy                          # local build, ssh push, up -d, health-poll
bin/remote.sh status                          # container ps + health + disk
bin/remote.sh pages-push docs/wikantik-pages  # rsync pages to remote (no --delete by default)
bin/remote.sh rollback                        # re-promote the :rollback image
```

**Cut and deploy a release.** Two wrappers capture the routine sequence:

```bash
bin/cut-release.sh X.Y.Z       # version bump, CHANGELOG, tag, push; the tag triggers release.yml
bin/deploy-release.sh X.Y.Z    # pull the published image, then bin/remote.sh deploy --skip-build
```

`cut-release.sh` does not build, so run a green `bin/run-tests.sh --all` first. That is the
complete gate (unit plus all default IT modules plus the opt-in Authentik SCIM full-loop),
and a release is that full-loop's checkpoint. When `release.yml` is green,
`deploy-release.sh` swaps the image. The database volume and the page bind mount persist
across the swap and the entrypoint applies pending migrations, so an upgrade is an image
swap. The first deploy is the exception: see [DockerDeployment.md](DockerDeployment.md).
Production page content lives at `${WIKANTIK_PAGES_DIR}` on the remote as a bind mount, so
`deploy` never carries content; see [section 1.9](#19-the-checkout-and-production-are-different-corpora).
For cloud VM targets see [CloudDeployment.md](CloudDeployment.md).

**Gotchas from the first docker1 deploy:**

- The `db` service runs `pgvector/pgvector:pg18`. The pg18+ image stores data under a
  version-specific subdirectory and needs the volume at `/var/lib/postgresql`; mounting the
  old `/var/lib/postgresql/data` makes the image refuse to start. Re-check the mount path
  when you bump the major version, and keep the container's major version level with your
  local one: `pg_dump` restores forward across versions, not backward.
- The deploying OS user must be in the `docker` group on the target.
  `bin/remote.sh bootstrap` checks the binaries and daemon reachability (`docker info`) and
  prints the fix (`sudo usermod -aG docker <user>`, then a fresh login) if either fails.
- Initialising the database from a dump is a manual sequence, not something
  `remote.sh deploy` does (it runs a full `up -d`, and the entrypoint would migrate an empty
  schema). To stand up a fresh host from a backup, use `bin/dr-restore.sh <host>`, which
  automates image and snapshot transfer, the database restore and a smoke test; see
  [BackupAndRecovery.md](BackupAndRecovery.md).

### Load testing: `bin/loadtest.sh`

`bin/loadtest.sh <smoke|load|stress>` runs the k6 harness in `loadtest/` against the
instrumented endpoints. Install [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/)
and copy `loadtest/loadtest.env.example` to `loadtest/loadtest.env` first.

| Option | Effect |
|--------|--------|
| `--verify` | Scrape `/metrics` before and after, and fail if a target dashboard panel did not move. `/metrics` is internal-only, so run it from inside the network or pass `--metrics-url http://localhost:8080/metrics`. |
| `--writes` | Add the authenticated create/edit/delete and login cycle (`--write-vus N` sets its concurrency). |
| `--admin`, `--admin-vus N` | Add the administrative-actions scenario over `/admin/*` (needs admin credentials). |
| `--duration D`, `--vus N` | Override run length and peak VUs (load and stress profiles). |
| `--dry-run` | Print the k6 command without running it. |

When `K6_PROMETHEUS_RW_SERVER_URL` is set in `loadtest.env`, k6 remote-writes its own
metrics into jakemon's Prometheus so offered load and host response share a timeline. A
fresh stack has no `testbot` user or API key, so seed them once with
`loadtest/seed-loadtest-data.sh` (see `loadtest/README.md`). Methodology is in
[LoadTesting.md](../developer/LoadTesting.md).

### Run the entity extractor: `bin/kg-extract.sh`

`bin/kg-extract.sh` runs the per-page entity-extraction pipeline against the local
PostgreSQL, reading the JDBC URL and password from the deployed `ROOT.xml` (or from
`PG_JDBC_URL`, `PG_USER`, `PG_PASSWORD`). With no flags it uses the Ollama model
`gemma4-assist:latest` at concurrency 2 with no judge.

```bash
bin/kg-extract.sh --max-pages 50 --dry-run --report reports/smoke.json   # smoke run
bin/kg-extract.sh --report reports/extract-$(date +%Y%m%d).json          # full run
bin/kg-extract.sh --jar-help                                             # the jar's full flag list
```

If the pending-proposal queue gets unwieldy and a clean restart is the right call,
snapshot the pending proposals first, then delete them:

```bash
PGPASSWORD=… pg_dump -h localhost -U wikantik -d wikantik \
    --data-only --table=kg_proposals --column-inserts \
    --where="status = 'pending'" \
    > backups/kg_proposals_pending_$(date +%Y%m%d).sql

PGPASSWORD=… psql -h localhost -U wikantik -d wikantik -c \
    "DELETE FROM kg_proposals WHERE status = 'pending';"
```

Wipes like this are never landed in `V*.sql` migrations; they are operator one-shots.

### `bin/remote.sh` — remote admin subcommands

The full table of subcommands (run `bin/remote.sh --help` or `bin/remote.sh <cmd> --help` for details):

| Subcommand | Purpose |
|------------|---------|
| `bootstrap` | First-time remote setup: verify Docker, create remote dirs, rsync compose + scripts + `.env`. |
| `deploy [--skip-build] [--health-timeout=N] [--pull TAG]` | Build locally, push image over ssh, `up -d` on remote, health-poll `/api/health`, auto-rollback on failure. `--pull TAG` skips the local build **and** the `docker save \| ssh docker load` transfer — the remote runs `docker pull` + retag directly instead, for a target with its own registry access (e.g. a cloud VM). Implies `--skip-build`. |
| `rollback` | Re-promote `wikantik:rollback` → `wikantik:latest`, force-recreate the service. |
| `up` / `down` / `restart` | Pass-through to `container.sh -e prod` on the remote. |
| `status` | One-screen summary: `ps`, `/api/health` status, disk free, pages + backup size, last 10 log lines. |
| `logs [-f] [SERVICE]` | Tail logs (defaults to `wikantik`). |
| `shell [SERVICE]` | Interactive shell in a remote container (default `wikantik`). |
| `psql [-- ARGS]` | `psql` pass-through in the `db` container. |
| `migrate [--status]` | Ad-hoc migration run (or list applied versions). |
| `pages-push LOCAL_DIR [--mirror]` | rsync local pages → remote. `--mirror` opts in to `--delete` (with confirmation). |
| `pages-pull LOCAL_DIR` | rsync remote pages → local (read-only, never deletes locally). |
| `backup-trigger [TIER]` | Invoke the prod backup sidecar (default: `daily`). |
| `backup-pull [DATE]` | rsync a backup snapshot from the remote to the dev box. |
| `restore REMOTE_PATH` | Sidecar restore + service restart (acquires deploy lock). |

Global flags: `--dry-run` (print commands instead of running), `-h` / `--help`.

> **Load testing and load characterization:** see **[docs/developer/LoadTesting.md](../developer/LoadTesting.md)** and `bin/loadtest.sh`.
> **Backup & recovery:** see **[docs/admin/BackupAndRecovery.md](BackupAndRecovery.md)** for the full 3-2-1 topology, restore procedure, and quarterly drill.

---

## 4. Maintenance & operator scripts

The `bin/` directory contains a number of operational tools beyond the main deploy/KG scripts. Most are safe to run repeatedly (they either dry-run by default or prompt before destructive steps). None are called from Maven or CI — they are operator-only tools.

| Script | Purpose | Safety |
|--------|---------|--------|
| `bin/kg-cleanup-node-types.sh` | One-shot interactive cleanup of legacy `node_type` values that predate the vocabulary gate. Reads credentials from `ROOT.xml`. | Idempotent SQL updates; prompts before running. |
| `bin/kg-chunker-stats.sh` | Inspect chunk-size distribution for the page corpus without touching the database. Pure in-memory re-chunk + prefilter eval. | Read-only. |
| `bin/kg-judge-experiment.sh` | Sample pending `kg_proposals` and judge each with both a no-op and a live judge (ollama or claude). Writes a side-by-side JSON report. | Read-only report; does not modify proposals. |
| `bin/run-embedding-experiment.sh` | End-to-end driver for the retrieval experimentation harness: index with multiple models, score BM25/dense/hybrid, compare. Requires `kg_content_chunks` populated. | Writes to experiment tables only. |
| `bin/run-experiment-local.sh` | Thin wrapper around `run-embedding-experiment.sh` that sources credentials from `ROOT.xml` and `test.properties`. | Same as above. |
| `bin/smoke-wiki.sh [BASE_URL]` | Functional smoke test: health UP, a page renders, changes feed populated, search returns a hit. Called by `bin/dr-restore.sh` on DR completion. Exit 0 = all checks passed. | Read-only. |
| `bin/curl-probe.sh <duration> <prefix>` | External real-user latency probe: samples three endpoints once per second, logs `(timestamp, endpoint, HTTP code, latency)` to `<prefix>.log`. | Read-only. |
| `bin/trigger-rebuild-indexes.sh [status]` | Kick off the async Lucene + `kg_content_chunks` rebuild via the admin API. Prerequisite for `run-embedding-experiment.sh`. | Triggers a rebuild; idempotent (409 if already running). |
| `bin/deploy-marketing.sh [--dry-run]` | Publish the static marketing site (`marketing/`) to the nginx docroot on the `cloudflare` host. Prompts for sudo password interactively. | Requires manual confirmation for the privileged copy step. |
| `bin/db/audit-retention.sh [--status\|--dry-run]` | Enforce `audit_log` retention: pre-create upcoming monthly partitions; archive-then-drop partitions older than `AUDIT_RETENTION_MONTHS` (default 84 = 7 years). | `--dry-run` touches nothing. The drop phase requires `AUDIT_ARCHIVE_DIR` to be set. |
| `bin/db/audit-retention-install-timer.sh` | Install and enable the `wikantik-audit-retention.timer` systemd timer (monthly). | Idempotent; prompts for sudo. |
| `bin/db/one-shots/` | Environment-specific one-off data fixups. Each file is a standalone script or SQL file intended to be run once per environment (not migrations). Current scripts: `2026-05-20-backfill-chunk-embeddings.sh` (backfill `content_chunk_embeddings`), `2026-06-08-normalize-kg-node-types.sql` (normalize `kg_nodes.node_type` onto the 9-class entity vocabulary), `reconcile_page_canonical_ids.sh` (reconcile `page_canonical_ids`), `reset_judge_timeout_abstains.sh`, `reset_node_judge_verdicts.sh`, `backfill-agent-default-owner.sql`. | Review individually before running; most are idempotent but data-modifying. |
| `bin/tests/test-audit-retention.sh` | Pure-filesystem unit tests for `audit-retention.sh` using stubbed `psql`/`pg_dump`/`pg_restore`. No real PostgreSQL required. | Read-only test harness. |
| `bin/tests/test-backup.sh` | Tests for `backup.sh` and `nas-pull.sh` manifest + metrics emission. Stubbed `pg_dump`/`psql`/`rsync`/`curl` — no real PG or ssh. | Read-only test harness. |
| `bin/tests/test-remote.sh` | Smoke tests for `bin/remote.sh` in `--dry-run` mode with a fake `remote.env`. No real ssh or docker. | Read-only test harness. |
| `bin/tests/test-container.sh` | Tests for `bin/container.sh`'s test-env / smoke-test compose invocation (base + overlay, `-p wikantik-test`, port 18080) using stubbed `docker`/`curl`; two real `docker compose config` checks, nothing started. | Read-only test harness. |

**`WIKANTIK_SEED_DEV_USERS` and `-e base`**
- Setting `WIKANTIK_SEED_DEV_USERS=true` in `.env` causes the entrypoint to ensure the default admin (admin/admin123, must-change-on-first-login) exists via `bin/db/seed-users.sql`. Fresh databases get the same flagged admin from migrations V002+V039 regardless. **Never set in production.**
- Running `bin/container.sh -e base` starts the stack with the base compose only (no overlays) — useful for debugging compose variable substitution or running the stack without the dev or prod overlay.

---

## 5. Knowledge Graph Administration

The Knowledge Graph holds LLM-extracted entities (nodes) and typed relations between them (edges). It is administered at `/admin/knowledge-graph` in the UI and through the admin MCP tools (`propose_knowledge`, `list_proposals`, `review_proposals`, `curate_nodes`, `curate_edges`). It is separate from the Page Graph, whose edges are real wikilinks; see [PageGraphVsKnowledgeGraph](../wikantik-pages/PageGraphVsKnowledgeGraph.md).

### 5.1 How nodes and edges are written

Nothing projects page frontmatter or body links into the Knowledge Graph on save. The old graph projector was retired (migration V012 purged the `links_to` edges it had written). Today there are three write paths:

1. **Extraction proposes.** On page save, `AsyncEntityExtractionListener` runs off the save thread and files entity and relation **proposals** in `kg_proposals` (and chunk mentions). It never writes `kg_nodes` or `kg_edges` itself, skips an edge pairing already recorded in `kg_rejections`, and applies a per-page rate limit. `bin/kg-extract.sh` does the same in batch.
2. **A judge or a human decides.**
   - The judge (`JudgeRunner`, on a schedule unless disabled; trigger a run or judge one proposal with `bin/kg-judge.sh`) records a machine verdict. An approved `new-edge` or `new-node` proposal is materialised by `KgMaterializationService` at tier `machine` with provenance `ai-inferred`; a hard rejection of an edge also records it in `kg_rejections`.
   - A human approves through `tryApprove` (REST `/admin/knowledge-graph` proposal handlers and the `review_proposals` tool). That promotes the rows to tier `human`, and for a `new-edge` also writes the relation back into the source page's frontmatter (the relationship type becomes a frontmatter key listing the target; saved as author "Knowledge Admin").
   - A human rejection retracts anything the proposal had materialised and records the rejection, so the same proposal is refused when resubmitted (`propose_knowledge` refuses a previously rejected triple).
3. **Direct curation.** `curate_nodes`, `curate_edges` and the admin UI write nodes and edges immediately with human provenance (`human-authored` for node upserts, `human-curated` for edges). Edge writes pass the write-time SHACL gate, which refuses a non-conformant edge, and mixed page-to-entity edges are refused.

Provenance values are `human-authored`, `human-curated`, `ai-inferred` and `ai-reviewed`.

### 5.2 Proposals

Agents submit `new-node`, `new-edge`, `new-property` or `modify-property` proposals through `propose_knowledge` (`new-edge` data is `{source, target, relationship}`; `new-node` is `{name, node_type, properties}`). Only `new-node` and `new-edge` proposals are materialised into the graph; review them in `/admin/knowledge-graph` or with `list_proposals` / `review_proposals`.

### 5.3 Node and edge curation

- **Edge types** are a closed vocabulary enforced by the `kg_edges_relationship_type_check` constraint (V027, extended by V030): `related_to`, `part_of`, `contains`, `is_a`, `instance_of`, `requires`, `enables`, `uses`, `produces`, `replaces`, `precedes`, `extends`, `implements`, `alternative_to`, `contrasts_with`, `compatible_with`, `mitigates`, `defines`, `applies_to`, `located_in`, `generalizes`. Anything else is rejected by the database.
- **Which pages feed the graph** is controlled by the cluster inclusion policy; see [KgInclusionPolicy.md](KgInclusionPolicy.md).
- **Orphans:** `list_orphaned_kg_nodes` finds degree-0 entities.

### 5.4 Embeddings & Advanced Quality Tools
Wikantik leverages a unified embedding model for hybrid search and structural similarity.
- **Merge Candidates:** `GET /admin/knowledge-graph/nodes/{name}/similar` (surfaced in the node-explorer's node-detail panel) finds structurally and semantically similar nodes; `POST /admin/knowledge-graph/nodes/merge` merges two, updating corresponding edges and frontmatter references.
- **Pages Without Frontmatter:** Accessible under Content Embeddings, used to flag pages that have zero footprint in the semantic graph.