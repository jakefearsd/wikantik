# Wikantik Operations Handbook

This handbook is for administrators who run Wikantik. It covers deployment (container, remote and bare metal), tuning, the health and metrics endpoints, backups, the operator scripts under `bin/` (including remote deployment and load testing), and embeddings administration.

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

**Rollback:** `bin/remote.sh deploy` rolls back automatically when the post-deploy health poll fails, and `bin/remote.sh rollback` does it by hand; see [DockerDeployment.md](DockerDeployment.md#roll-back). There is no live CI deploy pipeline: the developer box drives all production changes (the legacy `ci-cd.yml` and `staging-deploy.yml` workflows are manual-dispatch only and dormant, with no self-hosted runner registered; see [CI.md](../developer/CI.md)).

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
3. **Deploy** — `bin/deploy-local.sh` downloads Tomcat and the PostgreSQL JDBC driver
   (42.7.14, from Maven Central) on the first run, materialises config from the
   templates, deploys the WAR, runs migrations, starts Tomcat.
4. **Iterate** — `bin/redeploy.sh` is the fast path (swap WAR + restart only).

**Config is write-once.** `deploy-local.sh` materialises each templated config
file (`ROOT.xml`, `wikantik-custom.properties`, `wikantik-mcp.properties`, `log4j2.xml`,
`setenv.sh`, `conf/server.xml`, `conf/context.xml`) on first deploy and **does not overwrite it afterward** (the
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
it disables itself only when **all three** limits (default per-client, expensive
per-client and expensive global) are set ≤ 0.

| Setting (env var) | Default | Applies to | Why |
|---|---|---|---|
| `WIKANTIK_RATELIMIT_DEFAULT_PERCLIENT` | **25** req/s | `/api/*`, `/id/*`, `/export/*` (default tier) | Generous per-client ceiling; no global cap (the backpressure semaphore bounds the aggregate). |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PERCLIENT` | **3** req/s | the expensive paths below | Tighter per-client ceiling for compute-heavy work (dense retrieval, SPARQL materialization). |
| `WIKANTIK_RATELIMIT_EXPENSIVE_GLOBAL` | **10** req/s | the expensive paths below | Single-host **global** cap across all clients — one abuser can't monopolise the retrieval/ontology CPU. |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PATHS` | `/api/bundle,/api/search,/sparql` | — | CSV path prefixes routed to the expensive tier. |
| `WIKANTIK_RATELIMIT_EXEMPT_CIDRS` | *(empty)* | — | CSV IPv4 CIDRs exempt from all limits. Loopback is **always** exempt, and the exact path `/api/health` is never limited. |

On a limit hit the filter returns `429` + `Retry-After: 1`, emits a `SecurityLog`
line, and increments `wikantik_ratelimit_rejected_total{tier="default"|"expensive"}`.
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
`update_page` tool by default. Two
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
reconcile the two, and `pages-pull` alone cannot: it only lists what the remote
holds today. (Before 2026-11-09 it also failed `Permission denied` on container-owned
pages and silently returned a partial corpus; the page-directory permissions under
"Gotchas from the first docker1 deploy" fix that.)

Production is authoritative for content; the checkout is a mirror. Derive corpus-wide
plans from the live index (the `list_clusters` and `list_pages_by_filter` tools on
`/knowledge-mcp`), never from the repository, and measure the gap with
`CorpusDivergenceCli` (in `wikantik-extract-cli`), which compares the repository corpus
with a live wiki's `/api/structure/sitemap`. It exits 2 when it refuses because the
snapshot it was given was incomplete, and 1 when divergence is found under `--check`.

### 1.9.1 Auditing access-rule readings (`AclShapeAuditCli`)

`AclShapeAuditCli` (`wikantik-extract-cli`, #104) reads every page's access rules with the one reader
(`AccessRules.read`) and prints, per page, the reading (`Rules` or `Locked` with reasons), the ACL each of the two
replaced readers enforced (old text scan, old render path), the new ACL and a `changed` flag, then a summary listing the
locked, changed and ESCAPED pages. ESCAPED (`\[{ALLOW ...}]`) is the one shape the new reader reads more openly than the
old text scan, so read that list. Exit `0` clean, `1` a page is locked, `2` a page could not be fetched (named in the
report) or the list could not be read, `64` bad usage.

```bash
CP="wikantik-extract-cli/target/classes:$(cat classpath.txt)"      # or the extract-cli jar with dependencies
# a local directory of *.md files
java -cp "$CP" com.wikantik.extractcli.AclShapeAuditCli --dir docs/wikantik-pages [--format text|json]
# a live wiki (an administrator; at most ~20 requests/s). Credentials come from the environment, never the command line:
export WIKANTIK_AUDIT_TOKEN=...                                    # bearer API key, or:
export WIKANTIK_AUDIT_USER=... WIKANTIK_AUDIT_PASSWORD=...         # HTTP Basic
java -cp "$CP" com.wikantik.extractcli.AclShapeAuditCli --url https://wiki.wikantik.com --format json
```

**Prefer `--dir` on a byte-exact copy of the corpus.** `--url` cannot see the raw file: the API returns the frontmatter
as parsed fields, which the tool re-dumps as YAML, so quoting and line layout differ and a directive inside a
frontmatter value can be invented or hidden. `--url` is also refused when it embeds `user:password@`, or is plain
`http://` to anything but localhost/127.0.0.1 (credentials would travel in the clear); an empty or nonexistent `--dir`
is `REFUSED` (exit 2) rather than a clean empty audit.

`--url` reads `GET /api/pages` then `GET /api/pages/{name}`, which returns the frontmatter as parsed fields, so the text is
rebuilt from them; a page the server itself locks is reported locked with the server's reasons. Findings and decisions
are recorded in `docs/superpowers/specs/2026-11-01-acl-shape-audit.md`.

---

### 1.10 Releasing a deleted account's login

Hard-deleting an account records its login in the `deleted_logins` table. Old content by that login shows as
"former member", and the login cannot be registered again (self-registration, admin create, SCIM create, SSO
first sign-in and rename all refuse it; matching ignores case), so a new account cannot inherit the old one's edits
and comments. Accounts deleted before this table existed (V065) have no tombstone.

To deliberately reuse a name, delete its row as the migrate/superuser role (the application role is granted only
`SELECT, INSERT` on the table):

```bash
psql -h localhost -U postgres -d wikantik -c "DELETE FROM deleted_logins WHERE login_name = 'alice';"
```

The identity cache holds the tombstone set for up to 60 seconds, so the released name may keep showing "former member"
until it refreshes (or the app restarts). Once released, the name's old content attributes to its next holder.

### 1.11 Collaborative editing (MultiEdit)

Real-time multi-user editing (design: `docs/MultiEdit.MD`, user guide: `docs/user/CollaborativeEditing.md`) ships
**dark**: `wikantik.collab.enabled` defaults to `false`. Do not turn it on for everyone until the default-on checklist
in MultiEdit.MD section 15 is ticked. To pilot it, enable it on one instance (`-Dwikantik.collab.enabled=true` or
`wikantik-custom.properties`) and restart. Turning it off again refuses new joins (503); editors already in a draft
can still publish or discard it.

**Drafts and retention.** Each page has at most one shared draft (`collab_session` and its `collab_update`,
`collab_meta_op` and `collab_snapshot` rows). A draft with unpublished changes is kept until someone publishes or
discards it, or until retention removes it. `wikantik.collab.draft.retentionDays` (default `0` = keep forever) runs at
most hourly and removes every stored draft nobody has edited for longer than that, unless people are in it. Retention
and purge never publish. Choose a value before the default flips; drafts are small, but they hold unpublished text.

**Listing and purging drafts (admin).** `GET /admin/collab/drafts` lists every stored draft, oldest edit first (session id,
page, status, creator login and display name, created and last-edit times, whether it is live, its participant count,
and whether a publish is pending). `DELETE /admin/collab/drafts/{id}`
removes one: every participant is evicted with reason `purged` and sees "An administrator removed this shared draft, including its unpublished changes"; the
answer is 204, 404 `unknown`, 423 `publish-in-progress`, or 503 `store` (the draft is kept). The delete is
CSRF-checked like every admin write. There is no admin UI for this yet (Phase 4).

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -s -u "${login}:${password}" http://localhost:8080/admin/collab/drafts | jq .
```

**Audit entries** (`/admin/audit`, target type `page`, the page name as target id, the session id in `detail`):

| Event | Category | Actor | When |
|-------|----------|-------|------|
| `collab.session.open` | CONTENT | the creator | a page's draft is created |
| `collab.join` | CONTENT | the joiner | a participant joins (detail: `participantId`) |
| `collab.publish` | CONTENT | the publisher | a publish writes a version (detail lists the contributors' author keys) |
| `collab.discard` | CONTENT | the discarder | the draft is reset to the published page |
| `collab.evict` | CONTENT | the evicted author | a participant is removed (detail: `reason`, e.g. lost edit permission) |
| `collab.purge` | ADMIN | the administrator | `DELETE /admin/collab/drafts/{id}` |
| `collab.expire` | ADMIN | system | retention removed a draft |
| `collab.orphaned` | CONTENT | system | a publish left unpublished changes and no participant with `edit`; the draft waits in the drafts list for someone with `edit` or an admin purge |

**Metrics** (`/metrics`; every labelled series is registered at 0 up front):

| Meter | Meaning |
|-------|---------|
| `wikantik_collab_push_total{result=accepted\|rebase\|rejected}` | pushes; a high `rebase` share means heavy contention, `rejected` should stay near 0 |
| `wikantik_collab_push_latency_seconds` | push latency, with a percentile histogram (alert on p95) |
| `wikantik_collab_publish_total{result=published\|unchanged\|conflict\|refused\|failed\|store}` | publishes |
| `wikantik_collab_external_merges_total` | REST/MCP/rename/watcher writes merged into a live draft |
| `wikantik_collab_resync_total` | 410 resyncs (a client fell behind the in-memory ring and reloaded) |
| `wikantik_collab_sessions_active`, `wikantik_collab_participants_active` | gauges |

**Rate limits.** `/api/collab/*` has its own per-IP bucket, `WIKANTIK_RATELIMIT_SESSION_PERCLIENT` (default 100
requests/s), on top of the per-participant `wikantik.collab.pushesPerSecond` (default 20). Several editors behind one
NAT share the per-IP bucket; size it from the load figure below. Check the Cloudflare bot and WAF rate rules for
`/api/collab/*` before enabling in prod: a typing editor sends about one push a second and holds one long-poll open.

**Load scenario.** `loadtest/collab-load.js` (k6, see `loadtest/README.md`) drives N editors over M pages against a
**local** collab-enabled deployment, never prod. First measured figure (2026-11-07; local Tomcat, single node, 15
editors and 5 listeners over 5 pages for 2 minutes): no failed or throttled pushes, client push latency p95 6.3 ms,
publish p95 44 ms, about 1.3 requests/s per editor from the script. A real editor also holds a long-poll, so budget about
2 requests/s per active editor: the default per-IP bucket of 100/s covers about 50 active editors behind one address.
One account holds at most 4 seats in a session, which bounds the script's editors per page.

### 1.12 Private clusters

A private cluster hides every page in it from everyone except administrators and the groups you name. Use it for
content that must stay out of search engines and agent retrieval, such as host pages and incident runbooks.

Settings (`wikantik-custom.properties`; both ship blank, so a fresh install is unaffected):

```properties
wikantik.access.privateClusters = home-lab-infrastructure, remote-host-management
wikantik.access.privateClusters.viewers =
```

`privateClusters` lists cluster paths. `privateClusters.viewers` lists groups allowed in addition to Admin.

- A page is private when any of its `cluster:` memberships is a listed cluster or a sub-cluster of one. The cluster's
  hub page is private too. The cluster comes from the page's stored frontmatter, so a new page is private from its
  first save.
- Every page action (view, edit, comment, rename, delete, upload) needs Admin or a viewer group. The rule sits in
  `DefaultAuthorizationManager` (gate class `com.wikantik.auth.PrivateClusterGate`), so it applies on every surface.
- A page's own `[{ALLOW ...}]` rules can only narrow access further. They cannot re-open a private page.
- Public pages that link to a private page keep the link. Readers outside the viewer set get a 404 on it.

**After changing either setting:** restart Tomcat (the lists are read at startup), then rebuild the ontology so pages
that became private leave the public SPARQL/export store:

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -s -u "${login}:${password}" -X POST http://localhost:8080/admin/ontology/rebuild
curl -s -u "${login}:${password}" http://localhost:8080/admin/ontology/status | jq .
```

**Consequences.**

- The admin MCP (`/wikantik-admin-mcp`) bypasses page permissions, so writers such as jakemon's runbook publisher keep
  working, and an admin can still read the pages there.
- The knowledge MCP and every public surface hide private pages from callers who are not viewers: pages, `?format=`,
  search, sitemap and news sitemap, feeds, `/api/changes`, the Page Graph, structure, ontology/SPARQL, bundle and
  briefing.
- Page history and diff follow the cluster of the **latest** version. A page moved out of a private cluster exposes
  its older versions.

**Verify as anonymous** (no credentials; replace the host and page names):

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://wiki.wikantik.com/wiki/<PrivatePage>          # expect 404
curl -s -o /dev/null -w '%{http_code}\n' 'https://wiki.wikantik.com/wiki/<PrivatePage>?format=md'  # expect 404
curl -s https://wiki.wikantik.com/sitemap.xml | grep -c 'Incident'                                   # expect 0 (use a name from your private clusters)
```

### 1.13 Content dates backfill

The sitemap `<lastmod>`, the news sitemap window and JSON-LD `dateModified` use a **content-changed date**, stored in
`page_content_dates` (migration `V072`), not the save time. A save advances it only when the body changes
substantially: the word-level difference (added plus removed words) reaches `min(minPercent of the old word count,
minWords)`, or the page gains a heading. Small edits accumulate against the last baseline. Frontmatter-only edits
never count. Feeds, ETags and `/api/changes` keep the real save time. Rule: `com.wikantik.seo.ContentChangeRule`.

Settings: `wikantik.seo.contentChange.minPercent` (default `5`) and `wikantik.seo.contentChange.minWords`
(default `100`).

The table starts empty. Until a page has a row, its date falls back to its save time. After the first deploy that
carries `V072`, run the backfill once; it replays each page's version history oldest to newest with the same rule:

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -s -u "${login}:${password}" -X POST 'http://localhost:8080/admin/seo/content-dates/backfill?force=false'
curl -s -u "${login}:${password}" http://localhost:8080/admin/seo/content-dates/status | jq .
```

`POST` answers 202 and runs in the background; a second `POST` during a run answers 409. Status reports `state`
(`idle`, `running`, `done`, `failed`), `total`, `done`, `failed` and `startedAt`. Without `force`, pages that already
have a date are skipped, so an interrupted run can be started again. `force=true` recomputes every page.

**Verify:** `https://wiki.wikantik.com/sitemap.xml` should show varied `<lastmod>` dates instead of a few shared ones.

## 2. Backup & Disaster Recovery

See **[BackupAndRecovery.md](BackupAndRecovery.md)** for the complete guide: the 3-2-1 topology
(live data → docker1 tiered snapshots → off-box NAS archive), the trust model (NAS pulls, docker1
holds no NAS credentials), the full restore procedure, the `bin/backup/verify-restore.sh` restore
drill, and the exact jakemon alert expressions to configure.

---

## 3. Administrative Scripts (`bin/`)

The `bin/` directory contains operational scripts to manage the Wikantik lifecycle.

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

- **Page files written by the app are `root`-owned and mode `0640`.** The container runs
  Tomcat as root and `catalina.sh` sets `umask 0027`, so every page the wiki saves (editor,
  MCP, renames) becomes unreadable to the deploying user, which breaks `pages-pull` and any
  `grep` over the page store. The fix keeps the files private to that user: make the
  directory tree group-owned by the deploying user and setgid, so new files inherit the group
  and come out `root:<user> 0640`. Run it once per host, and again if the pages directory is
  ever recreated:

  ```bash
  P="${WIKANTIK_PAGES_DIR}"   # e.g. /home/<user>/wikantik/pages
  sudo chgrp -R <user> "$P" && sudo chmod -R g+rX "$P" && sudo find "$P" -type d -exec chmod g+s {} +
  ```

  Applied to docker1 on 2026-11-09. Setting `UMASK=0022` in the image would also work but
  makes the pages readable by every account on the host.

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

The `bin/` directory contains a number of operational tools beyond the main deploy scripts. Most are safe to run repeatedly (they either dry-run by default or prompt before destructive steps). None are called from Maven or CI — they are operator-only tools.

| Script | Purpose | Safety |
|--------|---------|--------|
| `bin/chunker-stats.sh` | Inspect chunk-size distribution for the page corpus without touching the database. Pure in-memory re-chunk. | Read-only. |
| `bin/run-embedding-experiment.sh` | End-to-end driver for the retrieval experimentation harness: index with multiple models, score BM25/dense/hybrid, compare. Requires `kg_content_chunks` populated. | Writes to experiment tables only. |
| `bin/run-experiment-local.sh` | Thin wrapper around `run-embedding-experiment.sh` that sources credentials from `ROOT.xml` and `test.properties`. | Same as above. |
| `bin/smoke-wiki.sh [BASE_URL]` | Functional smoke test: health UP, a page renders, changes feed populated, search returns a hit. Called by `bin/dr-restore.sh` on DR completion. Exit 0 = all checks passed. | Read-only. |
| `bin/curl-probe.sh <duration> <prefix>` | External real-user latency probe: samples three endpoints once per second, logs `(timestamp, endpoint, HTTP code, latency)` to `<prefix>.log`. | Read-only. |
| `bin/trigger-rebuild-indexes.sh [status]` | Kick off the async Lucene + `kg_content_chunks` rebuild via the admin API. Prerequisite for `run-embedding-experiment.sh`. | Triggers a rebuild; idempotent (409 if already running). |
| `bin/deploy-marketing.sh [--dry-run] [--marketing-only] [--site-only] [--build-site]` | Publish the static marketing site (`marketing/`) and, if it has been built by `bin/site.sh`, the generated code-health site (under `/site`) to the nginx docroot on the `cloudflare` host. `bin/deploy-site.sh` is a thin shim for `--site-only`. Prompts for the sudo password interactively. | Requires manual confirmation for the privileged copy step; `--dry-run` changes nothing. |
| `bin/db/audit-retention.sh [--status\|--dry-run]` | Enforce `audit_log` retention: pre-create upcoming monthly partitions; archive-then-drop partitions older than `AUDIT_RETENTION_MONTHS` (default 84 = 7 years). | `--dry-run` touches nothing. The drop phase requires `AUDIT_ARCHIVE_DIR` to be set. |
| `bin/db/audit-retention-install-timer.sh` | Install and enable the `wikantik-audit-retention.timer` systemd timer (monthly). | Idempotent; prompts for sudo. |
| `bin/db/one-shots/` | Environment-specific one-off data fixups. Each file is a standalone script or SQL file intended to be run once per environment (not migrations). Current scripts: `2026-05-20-backfill-chunk-embeddings.sh` (backfill `content_chunk_embeddings`), `reconcile_page_canonical_ids.sh` (reconcile `page_canonical_ids`), `backfill-agent-default-owner.sql`, `null_out_empty_user_attributes.sh` (null out `users.attributes` where it is the empty string), `dedupe_display_names.sh` (make display names unique and presentable and give accounts with no stored uid one; reports stored uids that cannot appear in a mention token and display names equal to another account's login, count only; dry run by default, `--apply` to commit; `PSQL_COMMAND='bin/remote.sh psql --'` runs it against the production container). | Review individually before running; most are idempotent but data-modifying. |
| `bin/tests/test-dedupe-display-names.sh` | Offline guard for `bin/db/one-shots/dedupe_display_names.sh`: a stub `psql` proves the dry run rolls back, `--apply` commits, the apply setting is switched on before the SQL, and the report selects no login or email column. | Read-only test harness. |
| `bin/tests/test-audit-retention.sh` | Pure-filesystem unit tests for `audit-retention.sh` using stubbed `psql`/`pg_dump`/`pg_restore`. No real PostgreSQL required. | Read-only test harness. |
| `bin/tests/test-backup.sh` | Tests for `backup.sh` and `nas-pull.sh` manifest + metrics emission. Stubbed `pg_dump`/`psql`/`rsync`/`curl` — no real PG or ssh. | Read-only test harness. |
| `bin/tests/test-remote.sh` | Smoke tests for `bin/remote.sh` in `--dry-run` mode with a fake `remote.env`. No real ssh or docker. | Read-only test harness. |
| `bin/tests/test-container.sh` | Tests for `bin/container.sh`'s test-env / smoke-test compose invocation (base + overlay, `-p wikantik-test`, port 18080) using stubbed `docker`/`curl`; two real `docker compose config` checks, nothing started. | Read-only test harness. |

**`WIKANTIK_SEED_DEV_USERS` and `-e base`**
- Setting `WIKANTIK_SEED_DEV_USERS=true` in `.env` causes the entrypoint to ensure the default admin (admin/admin123, must-change-on-first-login) exists via `bin/db/seed-users.sql`. Fresh databases get the same flagged admin from migrations V002+V039 regardless. **Never set in production.**
- Running `bin/container.sh -e base` starts the stack with the base compose only (no overlays) — useful for debugging compose variable substitution or running the stack without the dev or prod overlay.

---

## 5. Embeddings Administration

The Knowledge Graph was removed; what remains of its admin surface is the embedding index and LLM call monitoring, at **Admin › Embeddings** (`/admin/embeddings`). The **Content embeddings** tab shows index health (chunks and pages embedded, model, dimension), the frontmatter backfill and the list of pages without frontmatter; the **LLM activity** tab shows about the last hour of in-flight and recent LLM calls. Rebuilds are triggered from the same page or with `bin/trigger-rebuild-indexes.sh`. See [AdminPanel.md](AdminPanel.md) and [IndexRebuild.md](IndexRebuild.md).
