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

### 1.14 Enabling work management

Work management (projects, a board, epics/stories/tasks, comments and history, at `/work`) is off by default.

The user-facing walkthrough (first product, project, board, change, deployment, agent) is [WorkManagement.md](../user/WorkManagement.md); the Work home links to it.

1. Set `wikantik.work.enabled=true` in `wikantik-custom.properties` and restart. `/api/capabilities` then reports `"work": true`.
2. Migration `V074__work_management_core.sql` is applied by `bin/db/migrate.sh` like any other (`bin/redeploy.sh` runs it).
3. An Admin creates the first product at `/work/products` and the first project at `/work` ("New project"), then adds members under the project's Settings tab. Only members and Admins see a project; everyone else gets "Not found".

**Before deploying a release that adds the project key `DEPLOYMENTS` to the reserved keys,** check production has no such project: `SELECT key FROM work_project WHERE upper(key) = 'DEPLOYMENTS';` must return no rows (rename it first if it does). Migration `V084` (item type and labels) is applied by `bin/db/migrate.sh` as usual.

To turn it off, set the property back to `false` (or remove it) and restart: `/api/work/*` returns 404 and the navigation entry disappears. The data is kept, and returns when the flag is turned back on.

### 1.15 Agents and work queues

An agent is a registered identity (a coding agent, for instance) that takes work from a queue, claims it, and hands it on. Only an Admin creates queues and registers agents, and only an Admin sees logins and key ids. This needs work management on (section 1.14); migrations `V076`, `V077` and `V078` are applied by `bin/db/migrate.sh` (`bin/redeploy.sh` runs it).

**Create a queue.** Open `/work/agents` (Admin only; "Agents & queues" on the Work home) and choose "New queue": a key, a name and an optional description. The key must match `^[a-z][a-z0-9-]{1,29}$`: it starts with a lower-case letter, then letters, digits and hyphens, 2 to 30 characters in all (`2fa` and `-ops` are refused, `ops-2` is fine). Items are put in a queue from the item form or the item page's Assignee control ("Queue · name"); a queue is one assignment target, so an item is for a person or a queue, never both. Work in a queue is `ready` or `review` until an agent claims it: an unclaimed item in a queue cannot be `in_progress` (a move, edit or hand-off that would make it so is refused with "Work in a queue is ready or in review until an agent claims it."; the board's In progress column and the hand-off form do not offer it).

**Edit or retire a queue.** On `/work/agents`, "Edit" changes a queue's name and description (its key never changes) and "Retire" ends it. A retired queue is no longer offered when assigning an item or registering an agent, but nothing already in it is touched: items stay in the queue, stay claimable by the agents that already serve it, and keep their history. To stop agents taking that work, move the items or remove the queue from the agent.

**Register an agent and copy its token.** A lead of any project, or a wiki administrator, registers agents. On `/work/agents` choose "Register agent": a name (for example `codex@docker2`; it must match `^[a-z0-9][a-z0-9._-]{0,39}(@[a-z0-9][a-z0-9.-]{0,39})?$`, so lower case, up to 40 characters before an optional `@host` of up to 40 more), optional vendor and runtime, and the queues it serves. **Whoever registers the agent is its sponsor**: the person who stands behind what it does. The page then shows the agent's token **once**; copy it into the agent's environment before choosing "Done", because only its hash is stored and the page forgets it. The token starts with `wka_`, is not an API key, and works on `/api/work/*` and `/work-mcp` and nowhere else. Treat it as a secret. To get another, use "Rotate key": the old token stops working on the very next request, the new one is shown once, and claims the agent holds are untouched. "Retire" ends an agent for good: its token is revoked and every claim it holds is released with the reason `agent retired`. Rotating and retiring are for the agent's sponsor or an administrator, and each asks first.

**The sponsor is a ceiling, never a source.** An agent has only what it was given (membership of a project, a role there, queues), and it can never do or see more than its sponsor could at that moment. This is looked up on every request, not copied: if the sponsor is demoted to viewer in a project the agent is read-only there; if the sponsor is removed from the project the agent gets the same 404 as a stranger, though its member row stays; when the sponsor's standing comes back the agent works again with nothing re-entered. A sponsor who is a wiki administrator limits nothing. An agent with no sponsor, or whose sponsor's account no longer exists, is suspended everywhere: its token still verifies, and it is no actor (REST 401 `Invalid API key`; on `/work-mcp` the tool answers "This endpoint needs a work API key."). The Agents page shows "Suspended: no sponsor" or "Suspended: its sponsor no longer has an account", and a project's member list shows "No effect: …" beside an agent row that currently does nothing. If the user directory cannot be read, agent requests answer 503 ("Service temporarily unavailable"; on `/work-mcp`, "Work is unavailable right now.") and nothing changes: retry.

**Edit an agent.** "Edit" on `/work/agents` changes an agent's name (renaming is allowed and is audited as `renamed from <old>`), vendor, runtime and the queues it serves. It is for the agent's sponsor while they lead a project, or an administrator. The REST update (`PATCH /api/work/agents/{id}`) is partial: a field you leave out is unchanged. Taking a queue away from an agent does not touch claims it already holds; it only stops new work from that queue being offered. Queues themselves are created, renamed and retired by administrators only.

**Changing an agent's sponsor.** Nobody can make another person a sponsor; the person taking over does it themselves. A sponsor hands an agent on with "Release sponsorship" (`DELETE /api/work/agents/{id}/sponsor`): the agent is suspended and every claim it holds is released. Then a lead of a project the agent is in, or an administrator, chooses "Take over" (`PUT /api/work/agents/{id}/sponsor`). An administrator may take over any agent at any time; a lead may only take over an agent with no sponsor (released, or the sponsor's account is gone), otherwise the answer is 403 "This agent has a sponsor. Ask them to release it, or ask a wiki administrator." After a takeover the agent keeps its token and its member rows, and has access only where its new sponsor does; claims outside the new sponsor's reach are released in the same step. Every sponsor an agent has had is kept (`work_agent_sponsor_history`), so a past action can be tied to who stood behind the agent then. **If a sponsor's account is deleted** nothing needs doing at that moment: the agent is suspended by the next request and its claims are ended by the claim sweeper within a minute (reason `sponsor access removed`); have a lead or an administrator take it over.

**Upgrading from 2.5.23 with registered agents.** Migration `V080` drops the link between agents and API keys. Before upgrading a deployment that has phase 2 agents, retire them or revoke their keys: afterwards a key that was an agent's is an ordinary personal `work` key that acts as its owner. After the upgrade each surviving agent has no sponsor and no token: an administrator takes it over and rotates it to issue a `wka_` token. Rolling back to 2.5.23 after `V080` has run leaves that version's agent registry broken (it reads the dropped column): its Agents page and agent registration fail until you roll forward again. Projects, boards and items are unaffected.

**Admit the agent to a project.** An agent sees and does nothing in a project until it is a member: under the project's Settings tab, add the agent as `viewer` (reads) or `member` (reads and claims, comments, edits and hands off). An agent is never a `lead`, never an administrator, and gains no access from its sponsor or any group: the sponsor only limits it. Changing an agent member to `viewer` or removing it releases every claim it holds in that project (reason `access removed`) and the items return to their queue. Removing a **person**, or making them a viewer, also releases the claims of the agents they sponsor in that project when they are left with less than `member` standing there (reason `sponsor access removed`); the members list says so. Archiving the project releases every claim in it the same way, in the archive's own transaction (reason `project archived`, recorded as the archiver's action); the holder's next call on the item is refused as archived, and the archive confirmation says so. An agent may not move an item out of `draft`: a person moves a draft to Ready.

**When an item is claimable.** An agent can claim an item when it is a member of the item's project, serves the item's queue, and the item is `ready` or `review`, unclaimed and not blocked by an open item. The board shows such items as "Available to agents". A claim moves a `ready` item to In progress (a `review` item stays in review) and shows the agent and the time on the card ("name · until 14:05") and on the item page. The agent finishes by handing the item to a queue or a person with a note (hand-off), or releases it. Every ending of a claim is in the log: a release, a hand-off, a move or reassignment by someone else, losing access, retirement, an archive, or an expiry.

**Claim expiry and the sweep.** A claim lasts `wikantik.work.claim.expiryMinutes` minutes (default 240, allowed 1 to 10080; an invalid value is logged and replaced by 240). The agent renews by claiming the item again, which restarts the clock. A background pass (`WorkClaimSweeper`, every 60 seconds) returns lapsed claims to their queue and logs a `claim_expired` event. To change the lifetime, set `wikantik.work.claim.expiryMinutes` in `wikantik-custom.properties` and restart; the value is read at startup. The generated reference lists the key ([ConfigurationReference.md](../ConfigurationReference.md)).

**Reading the log.** `/work/log` ("Assignments and claims") lists, newest first, every assignment, hand-off, status change, claim, renewal, release, expiry and membership change in the projects you can see; `/work/<PROJECT>/log` (the project's "Assignments and claims" tab) shows one project. Each line names who did it (agents wear an "agent" badge) and ends with the time. How to read them:

- *assigned this to Queue · name* / *assigned this to person*: an item was put in a queue or given to a person.
- *claimed this (ready → in progress) · until 14:05*: an agent took it. *renewed its claim*: the same agent claimed it again; runs of renewals fold into one line unless you choose "Show every renewal".
- *handed this to name: in progress → review*: a hand-off, with the hand-off note under it.
- *released name's claim*: a deliberate release, with the person's note. *moved this item, ending name's claim*, *reassigned this item, ending …*, *removed name's access to this project, ending its claim*, *retired name, ending its claim* and *archived this project, ending name's claim* are consequences of someone else's action, not releases.
- *claim by name expired*: nobody renewed it; the item went back to its queue. An expiry is a system event (the row names the agent whose claim lapsed), so it is listed whatever the "Who" filter: under People, Agents and All alike.

Items waiting on a person show on the Work home under "Waiting on you", with who handed them over and the note.

**Autonomy levels.** Every item has an effective level that decides what an agent may do to it without a person. People are never restricted by it.

| Level | An agent may |
|---|---|
| `supervised` | Work the item and review changes. A person approves it as ready, merges and closes. |
| `delegated` (the default) | Also merge a change it reviewed and approved, and close the item. A person approves work as ready. |
| `autonomous` | Also approve work as ready (move it out of draft or refining). Each item an agent closes waits for a person to acknowledge it. |

The level is the item's own "Autonomy" field, else its story's or epic's, else the project's default (Settings tab); the product's ceiling (Products page, Admin only, default `delegated`) then caps it and never raises it. The item page states the effective level under the Policy fields. A member may make an item stricter; making it looser, and setting "Allow direct" or "Done means", takes a project lead; an agent sets none of them. A change of level applies to the next request: lowering an epic to `supervised` refuses an agent's next merge or close under it.

Two rules hold at every level and cannot be configured: an agent never reviews or merges a change it opened or put a commit on, and an item cannot be done while one of its changes is open or in review. Moving an item under another epic or to another product where agents may do more is a loosening too and takes a lead; a member may name a stricter level but not the one the item already inherits.

**Changes.** A change is a branch of work on an item, against the product the item targets (an item with no product has none). The item page's "Changes" panel lists them and lets a member open one, ask for a review, approve or send it back, record the merge, or abandon it; agents do the same over `/work-mcp` (`open_change`, `update_change`, `review_change`, `merge_change`) or `/api/work/changes/*`. Wikantik records what it is told; it does not talk to the code host and cannot stop a `git push`, so match the level with branch protection there.

- An approval is for the commit the change was at. A new commit clears it; an agent may merge only while its approval stands on the current commit, and the merge may produce a squash or merge commit.
- An agent merges only when its own approval stands on the current commit. A person may merge any change; the record says whether it was merged after review, without review, or directly.
- "Record a merged change" opens and merges in one step, for work that went straight to the base branch. An agent may do this only where a lead set "Allow direct" on the item or its epic, and never at `supervised`.
- A reviewing agent must hold the item: the author hands it to the reviewer queue with status `review`, the reviewer claims it, reviews, merges and closes (at `delegated`), or hands it to a person (at `supervised`).

**Done without you.** An item an agent closes at the `autonomous` level is flagged. It shows on the Work home and the project board under "Done without you" for the project's leads and for administrators, and on the item page, until a member of the project chooses "Acknowledge" (`POST /api/work/items/{key}/acknowledge`; an agent cannot). Reopening the item clears the flag. To let agents work an epic end to end, a lead sets the epic's Autonomy to `autonomous` and an administrator raises the product's ceiling to `autonomous`; without both, the ceiling holds the level at `delegated`.

**Metric.** `wikantik_work_gates_total{gate,outcome}` counts each gate an agent meets (`approve`, `close`, `review`, `merge`, `direct`, `deploy`; `allowed` or `refused`). It carries no project, product or item label.

### 1.16 Deployments, the deploy gate and the incident path

A deployment is one product version going to an environment (`production` unless named), carrying the items it ships. It lives on `/work/deployments` (linked from the Work home), `/api/work/deployments/*` and three `/work-mcp` tools (`request_deployment`, `get_deployment`, `update_deployment`).

**States.** `requested`, `approved`, `running`, then `verified` or `failed`; a verified or failed one can be marked `rolled_back`. One that is not running yet can be `rejected`: whoever requested it withdraws it, or someone who could approve it turns it down, and rejecting an approved one takes the approval back. It only moves forward: retry a failed or rejected deployment as a new one. Marking it verified needs the health and smoke-test output as evidence, which is stored and shown as plain text.

**Who may do what.**

| Step | Who |
|---|---|
| Request | A member (person or agent) of every project the deployment carries an item from. Every item must target the product and be `ready` or later (not a draft, not in refining, not cancelled). |
| Reject or withdraw | Whoever requested it, or anyone who may approve it; only before it is running. |
| Approve, product gate `human` (the default) | A wiki administrator, or a person who is lead of every carried project. Never an agent. |
| Approve, product gate `agent` | Also any member of every carried project, including the agent that requested it. |
| Mark running, verified, failed, rolled back | A member of every carried project, or an administrator. |

The gate is set per product on the Products page (Admin). Autonomy never opens it: under a `human` gate an agent can only request, inside an `autonomous` epic too. Deployments waiting for your approval are listed on the Work home.

**What a viewer sees.** A deployment belongs to a product, not a project, so it is seen through the items it carries. Someone who belongs to one carried project sees the deployment with that project's items only, and no count of the rest; someone who belongs to none gets the same "not found" as for a deployment that does not exist. A partial viewer can still tell that the deployment carries something they cannot see (they are told they may not approve or run it), and notes and evidence are free text written by the people running it.

**Done means in production.** A lead can set an epic's "Done means" to "In production". Its stories and tasks then cannot be closed, by anyone, until a deployment to `production` that carried them is verified (and not rolled back since), and was verified after the item's last merged change; the refusal is `awaiting_deployment`. A verified deployment to any other environment ships nothing. Moving such an item out of the epic (to another epic, or to none) takes a project lead, like any other loosening, and an agent can never do it. Other epics close at merged, as before. Either way an item page shows "Shipped in" with the verified deployments that carried it, and its history gains "shipped this in …".

**The deploy-script check.** `bin/work-deploy-gate.sh <deployment-id> <version>` asks `GET /api/work/deployments/{id}/gate?version=` and exits `0` only for a clear yes: the deployment exists, the credential can see it, it is for that version and for the environment being deployed to, and it is approved or already running. It exits `1` when the answer is no and `2` when it could not ask (a missing variable, a timeout, a status other than 200, an answer it cannot read). It prints one line and never the token.

- `WORK_API_URL`: the wiki's base URL. `WORK_API_TOKEN`: a `work` API key or a work agent's token whose owner or agent can see the deployment. `WORK_GATE_TIMEOUT`: seconds to wait, default 10. `WORK_DEPLOYMENT_ENVIRONMENT`: where this deploy goes, default `production`; a deployment requested for another environment is refused.
- The check is opt-in. Set `WORK_DEPLOYMENT_ID` and `bin/deploy-release.sh X.Y.Z` asks before it pulls anything and stops on anything but a yes. `bin/remote.sh deploy` always asks too, about the version it is actually about to ship (`bin/work-deploy-check.sh`): the tag with `--pull`, the image's own version label with `--skip-build`, the version in `pom.xml` for a local build. If it cannot tell the version it refuses, and if you also set `WORK_DEPLOYMENT_VERSION` and it is not the version being shipped it refuses. An image built locally carries no release label, so it cannot pass with `--skip-build`. With `WORK_DEPLOYMENT_ID` unset both scripts behave exactly as before, so an outage of work management never blocks a deploy that did not ask for the check.

**The incident path.** This is the one fully autonomous path, and it exists only where someone sets it up:

1. An administrator sets the product's autonomy ceiling to `autonomous` and, if agents are to release without a person, its deploy gate to `agent`.
2. A lead creates a standing epic (for example "Production incidents") with Autonomy `autonomous` and Done means "In production".
3. An on-call agent, admitted to the project, files a story under that epic (`create_item` with `parentKey`), approves it as ready, claims it, opens a change and hands it to a reviewer agent; the reviewer approves and merges; either requests a deployment carrying the story; it is approved (by the agent where the gate allows, else by a person), run and verified with evidence; the story is then closed.
4. Every story closed this way waits under "Done without you" until a person acknowledges it.

Nothing in Wikantik receives an alert: wiring a jakemon alert to an on-call agent belongs to the agent runtime, which is not built. This path is what that agent will use.

**Metric.** `wikantik_work_deployments_total{state}` counts deployments by the state they reached. No product label.

**Security log and metrics.** Grep the `SecurityLog` (where it is written is set in [LoggingConfig.md](LoggingConfig.md)) for:

- `MCP_WRITE tool=... action=... target=... author=...`: a write made through `/work-mcp` (claim, create, comment, update, hand-off, release), authored by the agent's name.
- `MCP_WORK_REFUSED tool=... item=... agent=... kind=...`: a write refused; `kind` is `needs_key`, `not_found`, `denied`, `validation`, or a conflict reason such as `stale` or `not_claimable`. A burst from one agent means it is misbehaving or misconfigured.
- `Work API access denied: invalid key, ip=...` and `Work API access denied: key id=... scope=... does not cover work, ip=...`: a bad key or a key of the wrong scope on `/api/work/*`.

Metrics on `/metrics` (instance-wide, no project, item or agent labels): `wikantik_work_claims_total{outcome}` with outcomes `claimed`, `renewed`, `refused`, `released` and `expired`, and the gauge `wikantik_work_claims_active`, plus the gauge `wikantik_work_sweeper_alive` (1 while the claim sweeper thread runs, 0 when it failed to start or has died; it is absent when work is off). A 0 means no claim will expire until the next restart: the log carries one ERROR ("work claim sweeper stopped — claims will not expire until restart", or "work claim sweeper failed to start" at startup); alert on it and restart (a normal shutdown does not set it). `released` counts every ending that is not an expiry, including the ones a move or a retirement causes, so it matches the log. The sweeper does two things each minute: it expires lapsed claims, and it ends claims whose holder's sponsor lost standing through a change work management was not told about (a wiki group, the administrator role, a deleted account), with the reason `sponsor access removed`. If the user directory does not answer during that second step, those claims are left as they are and one WARN is logged per outage.

**Admin audit trail.** Registry changes are written to the admin audit (`/admin/audit`, category Admin, outcome Success): `work.queue.create`, `work.queue.update`, `work.queue.retire`, `work.agent.register`, `work.agent.update` (detail `renamed from <old>` on a rename), `work.agent.rotate_key`, `work.agent.retire`, `work.agent.takeover` and `work.agent.sponsor_released`. The actor is the person who did it; the target is the queue key or agent name; the entry never holds a token. A failed audit write is logged and never undoes the change.

**Troubleshooting.**

- An agent gets 403 `{"error":"Access denied"}` on `/work-mcp` (401 `{"error":"Invalid API key"}` on `/api/work/*`): its token is unknown, malformed or revoked (rotated away, or the agent was retired). The filter refuses it before any tool runs. A token that **verifies** but has no actor behind it, because the agent has no sponsor or its sponsor's account is gone, gets REST 401 and, on `/work-mcp`, the tool answer "This endpoint needs a work API key." Check the agent's status on `/work/agents`; rotate to issue a fresh token, or take the agent over. A personal API key of another scope gets 403 "Key not authorized for work" on `/api/work/*`. `/work-mcp` ignores `mcp.access.allowUnrestricted` and the CIDR list on purpose.
- 429 `{"error":"Rate limit exceeded"}` with `Retry-After: 1`: one credential (an agent token or a personal `work` key) made more than `wikantik.work.api.rateLimit.perKey` requests in a second on `/api/work/*` (default 20; 0 turns it off). `/work-mcp` has its own per-credential limit (`mcp.ratelimit.perClient`).
- A database or user-directory outage answers **503** ("Service temporarily unavailable" on `/api/work/*`; "Work is unavailable right now." from a tool) rather than 401/403, and the failure is never cached: the agent should retry, and the same token works as soon as the service is back. A 401 or 403 therefore always means the credential or the agent behind it is refused.
- "This MCP session belongs to another API key" (403) on `/work-mcp`: one session serves one credential (an agent's token, or a personal key). The first to use a session id keeps it; a client that swaps credentials must open a new session (`initialize` again). The binding is dropped after 2 hours idle or on `DELETE`.
- `not_claimable: ...`: the message says why: another agent holds it (and until when), it is not ready or in review, it is blocked, or it is not in a queue the agent serves. Add the queue to the agent (Edit on `/work/agents`) or fix the item.
- Agents do not see an item at all (`Not found.`): the agent is not a member of the project, or its sponsor is not (the member list then shows "No effect: its sponsor is not a member of this project" beside the agent).
- A claim is stuck (the agent died and the expiry is hours away): any project member who can edit items (a `member` or `lead`; a `viewer` cannot) opens the item and chooses "Release claim"; the item goes back to its queue. An agent can release only its own claim.
- "A person moves a draft forward.": an agent tried to move a draft to another status. A person moves it to Ready. (An agent that tries to cancel a draft gets "A person cancels or moves a draft.") Agents create items as `draft` and a person moves them forward. (A person's own new task or story starts at `ready`.)
- "Work in a queue is ready or in review until an agent claims it." (400, field `status`): something tried to make an unclaimed queued item `in_progress`. Claim it, or hand it to a person.
- A hand-off or edit is refused as `stale`: the item changed; the agent re-reads it and retries.

**Running the integration tests after a change.** The IT modules resolve the WAR from `~/.m2`, not from `target/`. After changing code, run `mvn install -DskipTests` before `bin/run-tests.sh --module <m>`, or the suite runs the old WAR and fails in confusing ways. (`bin/run-tests.sh --parallel 4` builds first.)

### 1.17 Work notifications: email and webhooks

Work notifications fill each person's inbox (`/work/inbox`) from the event log, once every `wikantik.work.notify.intervalSeconds` (default 60). The inbox is on whenever work management is. Email and webhooks are separate switches, both off by default. Only the new assignee hears of an assignment, watchers hear of hand-offs, a mention beats a watch, and nobody hears of their own action. The first run starts from the newest existing event, so enabling notifications never notifies history.

**Before deploying V086.** Check production has no project keyed `INBOX` (the key is now reserved, for the inbox route): `SELECT key FROM work_project WHERE upper(key) = 'INBOX';` must return no rows; rename any project it finds first. V086 also rewrites `work_event` once to add the `tx` column, so on a large event log expect a short table lock while it runs.

**Before deploying V087 (find and saved filters).** Check production has no project keyed `FIND` (the key is now reserved, for the find route): `SELECT key FROM work_project WHERE upper(key) = 'FIND';` must return no rows; rename any project it finds first. The owner runs this; agents do not use production credentials. V087 adds one table (`work_saved_filter`) and is plain DDL applied by `bin/db/migrate.sh`.

**Keys** (all in `wikantik-custom.properties`; defaults and detail in the generated configuration reference):

| Key | Default | Meaning |
|---|---|---|
| `wikantik.work.notify.intervalSeconds` | `60` | Seconds between dispatcher runs (1 to 3600). |
| `wikantik.work.notify.baseUrl` | blank | Absolute base URL for links; blank falls back to `wikantik.baseURL`. Email stays off (with a WARN) when both are blank. |
| `wikantik.work.notify.email.enabled` | `false` | Mail notifications through the wiki's existing mail settings. |
| `wikantik.work.notify.email.detail` | `summary` | `summary` sends key, title, who did what and a link; `full` adds comment text (cut at 500 characters) to mail. |
| `wikantik.work.notify.email.intervalMinutes` | `5` | Minutes between email runs. |
| `wikantik.work.notify.email.dailyHour` | `8` | Hour (server time zone) after which a daily digest is sent. |
| `wikantik.work.notify.webhooks.enabled` | `false` | Whether leads may add webhooks and whether they are delivered. |

**Email.** Configure mail first ([SendingEmailFromTheWiki](SendingEmailFromTheWiki.md)), set `wikantik.work.notify.baseUrl` or `wikantik.baseURL`, then set `wikantik.work.notify.email.enabled=true`. Each person chooses each, daily or off in their inbox preferences; agents are never mailed. Only notices from the last 48 hours are mailed, so turning it on sends no history. Delivery is at least once: a crash between sending and recording can repeat one message. `summary` is the default because the relay is outside the wiki's control.

**Webhooks.** Set `wikantik.work.notify.webhooks.enabled=true`. A webhook address must be https and passes the egress guard on save, at every delivery and at every redirect hop: addresses that resolve to loopback, private, link-local, multicast or unique-local ranges are refused unless `-Dwikantik.connectors.egress.allowPrivate=true` is set (which also opens connectors to private addresses). The host is resolved once by the check and again at connect, the same posture as connector fetches. A webhook that fails for 24 hours is switched off and its lead is told in the inbox. A webhook pauses while its owner is not a lead of the project. When a webhook resumes (flag back on, re-enabled, owner a lead again, taken over) it starts from the newest event and sends no backlog.

**Run notifications on one node.** With more than one node, each would send each event.

**Monitoring.** `wikantik_work_dispatcher_alive` is 1 while the dispatcher thread runs; 0 means notifications have stopped (the cause is in the log at ERROR). `wikantik_work_notifications_total{channel,outcome}` counts inbox, email and webhook outcomes.

**A transaction left open delays dispatch.** The dispatcher only reads events older than the oldest open transaction anywhere in the database (`tx < pg_snapshot_xmin(pg_current_snapshot())`), so a long-running or `idle in transaction` session pauses notifications until it ends. Nothing is lost. Find it: `SELECT pid, state, xact_start, query FROM pg_stat_activity WHERE state = 'idle in transaction' ORDER BY xact_start;`.

**After a dump and restore, reset the cursors.** The cursors store transaction ids. A restore into a new cluster can leave them ahead of the new ids, and dispatch then stalls silently (no error, the inbox simply stops filling). Reset them to the newest existing event:

```sql
UPDATE work_notify_cursor c
   SET tx = e.tx, event_id = e.id
  FROM (SELECT tx, id FROM work_event ORDER BY tx DESC, id DESC LIMIT 1) e;
UPDATE work_webhook w
   SET cursor_tx = e.tx, cursor_event_id = e.id
  FROM (SELECT tx, id FROM work_event ORDER BY tx DESC, id DESC LIMIT 1) e;
```

The restore also copies `work_event.tx` from the old cluster, so the newest event by `tx` is the right place to resume. Events between the old cursor and that point are not notified.

### 1.18 Work attachments

Items and comments take file attachments (`/api/work/items/{key}/attachments`; the upload is `multipart/form-data`, the file in the part `file`). Schema V088 (`work_attachment`, `work_attachment_blob`); apply it with `bin/db/migrate.sh` as usual.

| Key | Default | Meaning |
|---|---|---|
| `wikantik.work.attachments.maxBytes` | `10485760` | Largest single file, in bytes (1 to 104857600; outside that range is logged and replaced by the default). Larger uploads get 413. |
| `wikantik.work.attachments.maxItemBytes` | `52428800` | Largest total of live attachments on one item; a value below the file limit is raised to it (logged). Over it is 413. |

- **The bytes live in PostgreSQL**, so they are in every database backup and the database grows with attachments. `SELECT pg_size_pretty(pg_total_relation_size('work_attachment_blob'));` shows their size.
- **Removal deletes the bytes** (the metadata row and the activity stay), but the disk space comes back through autovacuum (the data is TOAST storage), not at once.
- **Memory and temp space.** An upload holds up to the per-file limit in JVM heap (a few copies while it is read and stored), and the container spools a multipart request to Tomcat's temp directory up to the 100 MB `<multipart-config>` ceiling on `WorkItemResource` in `web.xml`, so keep `maxBytes` well below it. A download holds the whole file in heap. Both are bounded by `maxBytes` and the per-credential rate limit.
- **Serving.** Only PNG, JPEG, GIF and WebP are shown inline; everything else is forced to download. See [Security](Security.md#work-attachments).

### 1.19 Work code-host hook

Lets a product's code host (GitHub, or anything that can post the generic format) tell the wiki about pushes, pull requests and approvals, so change records fill themselves in. Receive only: the wiki never calls the code host and holds no token for it. Schema V089 (`work_product_hook`, `work_codehost_*`, columns on `work_change`); apply it with `bin/db/migrate.sh` as usual.

| Key | Default | Meaning |
|---|---|---|
| `wikantik.work.codehost.enabled` | `false` | Master switch for the inbound endpoint. Off, `/api/work/hooks/*` answers the same 404 as an unknown hook. `/api/capabilities` reports `workCodeHost` (work and this flag). |
| `wikantik.work.codehost.maxBodyBytes` | `1048576` | Largest body accepted (clamped to at least 1024), checked before the signature. A larger delivery is 413. |

**Turn it on.** 1. Set `wikantik.work.enabled` and `wikantik.work.codehost.enabled` to `true` and restart. 2. As a **wiki admin**, open `/work/products`, choose **Turn on** next to the product's code-host line (or `POST /api/work/products/{key}/hook`). The dialog (or the response) gives the **URL** and the **secret** once: copy both now, they are not shown again. Only a wiki admin can do this; a project lead cannot. 3. In GitHub, add a webhook to the repository: **Payload URL** `https://<wiki host>/api/work/hooks/<product key>/github`, **Content type `application/json`** (a form-encoded hook shows red at GitHub with 415), **Secret** the secret, and under individual events choose **Pushes**, **Pull requests** and **Pull request reviews**. GitHub sends a `ping` straight away: the wiki answers 204 and the product's line shows it as the last delivery. If the product has a repository URL (`repoUrl`) it should end in `owner/name`: a delivery for another repository is ignored (a product with none accepts any). Items need to target the product, and the item key goes in the branch name, pull request title or commit message.

**Rotate and turn off.** **New secret** (or `POST` again) issues a new secret and the old one stops working at once; update the hook at GitHub. **Turn off** (`DELETE`) refuses every delivery with the identical 404 until it is turned on again. Both are audited (`work.product.hook.*`) and appear in the product's history, without the secret.

**Read the signals.** One INFO line per delivery from `com.wikantik.rest.WorkHookResource`: `work codehost delivery product=<key> format=<f> event=<e> delivery=<id> outcome=<accepted|rejected|ignored> applied=<n> duplicate=<n> ignored=<n>`; never a body, signature or secret. The counter `wikantik_work_codehost_events_total{kind,outcome}` (outcome `rejected`, kind `delivery`, counts refused deliveries). Status codes at the code host: **204** accepted (also a ping, an event that does nothing, and a body that is not an event object); **404** the signature, secret, URL or switch is wrong (deliberately never more specific); **413** the body is over `maxBodyBytes` (a very large push can be: raise the key, the body is held in memory before it is checked); **415** a verified delivery was form-encoded (set the content type to JSON); **503** work or the database was unavailable (redeliver from GitHub's Recent Deliveries: re-applying a delivery changes nothing).

**The generic format** (`/api/work/hooks/<product>/generic`, header `X-Wikantik-Signature: sha256=<hex HMAC-SHA256 of the exact body>`) takes one event object or an array of up to 100. Fields: `kind` (`push`, `review_opened`, `review_reopened`, `review_ready`, `review_updated`, `review_approved`, `review_merged`, `review_closed`), `branch`, `at` (**required**, ISO-8601 with an offset: a delivery holding any event without a usable `at` is rejected whole, because arrival time is never used as event time), and as the kind needs: `repo` (`owner/name`), `defaultBranch`, `baseBranch`, `before`, `after`, `mergedSha`, `actor` (a username string or `{username,name,email}`), `commits` (`[{sha, message, at, distinct, author}]`) and `review` (`{number, url, title, headSha, draft, author}`).

```bash
SECRET=...    # the one shown when the hook was turned on
BODY='{"kind":"push","repo":"acme/app","branch":"main","after":"<40 hex>","at":"2026-01-10T10:00:00Z","commits":[{"sha":"<40 hex>","message":"PLAT-12 fix","author":{"name":"Ana","email":"ana@example.com"}}]}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "https://wiki.example.com/api/work/hooks/plat/generic" \
  -H 'Content-Type: application/json' -H "X-Wikantik-Signature: sha256=$SIG" --data-binary "$BODY"
```

**What it does and does not do.** The route is unauthenticated by design, protected by the signature and the per-IP rate limit (see [Security](Security.md#work-code-host-hook)). A delivery fills in or updates change records of items that target that product: one pull request is one change; lifecycle follows the code host's own timestamps; a merge is terminal; it **never changes an item's status, assignee or sprint**. People are matched by profile email (noreply addresses never), outside authors are named as they appear on the host. A merge nobody else approved, or a direct commit where direct commits are not allowed, is flagged for a lead to acknowledge (user guide: [WorkManagement](../user/WorkManagement.md)); a late approval can clear an unacknowledged flag.

**Known limits.** Number reuse (the same pull request number on one item after a merge) is not revived. A dismissed review is not seen. A force-push back to an older commit is ignored (the commit keeps its first report time). A commit first pushed to a branch with no item key is never counted as direct. At most 200 head reports and 200 lifecycle events are kept per change. Order guarantees exclude the item's own status (a closed item ignores events that would open a change), a push stamped after a merge arriving before it, and a close or reopen stamped after a merge arriving before it. A flag raised before a late approval clears stays in the history and leads already told are not retracted. Deliveries of one product are applied one at a time.

### 1.20 Work flow and delivery figures

Nothing to configure and nothing to do beforehand: the figures on a project's Flow tab and a product's delivery page are computed from the work tables on each request; no table, cache or Prometheus series carries them (`WorkFlowNoStoreRuleTest` keeps it so). Migration V090 adds `work_deployment.remediates_id` and three partial indexes and is applied by `bin/db/migrate.sh` as usual. How to read each figure: [WorkManagement](../user/WorkManagement.md) section 11.

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

The Knowledge Graph was removed; what remains of its admin surface is the embedding index and embedding call monitoring, at **Admin › Embeddings** (`/admin/embeddings`). The **Content embeddings** tab shows index health (chunks and pages embedded, model, dimension), the frontmatter backfill and the list of pages without frontmatter; the **Embedding activity** tab shows about the last hour of in-flight and recent embedding calls. Rebuilds are triggered from the same page or with `bin/trigger-rebuild-indexes.sh`. See [AdminPanel.md](AdminPanel.md) and [IndexRebuild.md](IndexRebuild.md).
