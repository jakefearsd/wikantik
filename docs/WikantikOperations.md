# Wikantik Operations Handbook

This document is the definitive handbook and runbook for Wikantik Administration. It is designed to serve both new and experienced administrators in managing system configuration, deployment strategies, script execution, and knowledge graph operations.

---

## 1. System Configuration & Deployment

Wikantik supports two primary deployment strategies: a bare-metal Tomcat 11 installation and a fully containerized Docker architecture. 

### 1.1 Configuration via `.env`
Configuration for both bare-metal and container environments relies heavily on environment variables, typically managed via a `.env` file at the project root. Key variables include:
- `POSTGRES_PASSWORD`: The database access password.
- `POSTGRES_USER` & `POSTGRES_DB`: (Defaults to `wikantik`).
- `MCP_ACCESS_KEYS`: Security tokens required for accessing MCP endpoints.
- `MAIL_SMTP_PASSWORD`: Credentials for email dispatch.

### 1.2 Containerized Deployment (Recommended for Production)
The production Docker environment runs three critical services defined in `docker-compose.yml` and `docker-compose.prod.yml`:
1. `wikantik`: The primary application running on Tomcat 11/JDK 21.
2. `db`: PostgreSQL 18 database (`pgvector/pgvector:pg18` — the `vector` extension is required).
3. `backup`: An Alpine-based container that executes cron jobs for scheduled backups.

**Persistent Volumes (prod):**
- `pgdata`: Named volume holding the PostgreSQL cluster.
- **Pages + attachments**: Host bind-mount from `WIKANTIK_PAGES_DIR` (e.g. `/srv/wikantik/pages`) — not a named volume; `rsync` is the source of truth for content, independent of container lifecycle.
- `wikantik-work` & `wikantik-logs`: Named volumes holding the Lucene index and logs. Regeneratable, not backed up.
- `wikantik-profiling`: Named volume (prod overlay) holding JFR profiling recordings at `/var/wikantik/profiling`. Non-critical, not backed up.

**Rollback:**
Deployments are driven from the developer box via `bin/remote.sh deploy`. Before swapping the image, the script tags the running image as `wikantik:rollback`. If the post-deploy `/api/health` poll (`GET http://<host>:8080/api/health`) does not return 200 within the health timeout, `bin/remote.sh deploy` automatically re-promotes the `:rollback` image and force-recreates the service. Manual rollback at any time: `bin/remote.sh rollback`. There is no CI deploy pipeline — the developer box drives all production changes.

### 1.3 Bare-Metal Deployment
Deploying to a bare-metal server runs Wikantik as the ROOT context of a local
Tomcat 11 instance against a local PostgreSQL. It is the path for development,
manual testing, and single-host installs (production runs the container — §1.2).
The full step-by-step guide is
[PostgreSQLLocalDeployment.md](PostgreSQLLocalDeployment.md); the essentials:

1. **Database** — `sudo -u postgres bin/db/install-fresh.sh` creates the database,
   the `jspwiki` app role, and applies every migration. **Set `DB_MIGRATE_PASSWORD`**
   so it also runs `bin/db/create-migrate-user.sh`, which provisions the dedicated
   `migrate` role with the privileges migrations need (`CREATEROLE` + `pg_monitor`
   for V031, plus ownership of the schema). Skipping this leaves migrations to fail
   later as an under-privileged role — see the troubleshooting in the guide.
2. **Build** — `mvn clean install -Dmaven.test.skip -T 1C`.
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
[PostgreSQLLocalDeployment.md](PostgreSQLLocalDeployment.md).)

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

**Database connection pool** (DBCP, in the generated `ROOT.xml`)

| Setting | Value | Why |
|---|---|---|
| `maxTotal` | 90 | Pressed just under Postgres `max_connections` (100, default). Was the throughput ceiling until per-request DB hits were cached; **PgBouncer** is the lever to grow past this. |
| `maxWaitMillis` | 5000 (5 s) | How long a request waits for a connection before failing. Cut from 10 s once the pool stopped being the bottleneck — a long wait now signals real trouble, so fail fast and free the thread. |
| `maxIdle` | 30 (prod) / 10 (dev) | Idle connections kept warm. |

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
[ScalingCharacterization.md](ScalingCharacterization.md) and
[LoadTesting.md](LoadTesting.md).

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
- `smoke-test`: Spins up `docker-compose.test.yml` to verify health checks before tear down.

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

### `bin/remote.sh` — remote admin subcommands

The full table of subcommands (run `bin/remote.sh --help` or `bin/remote.sh <cmd> --help` for details):

| Subcommand | Purpose |
|------------|---------|
| `bootstrap` | First-time remote setup: verify Docker, create remote dirs, rsync compose + scripts + `.env`. |
| `deploy [--skip-build] [--health-timeout=N]` | Build locally, push image over ssh, `up -d` on remote, health-poll `/api/health`, auto-rollback on failure. |
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

> **Load testing and load characterization:** see **[docs/LoadTesting.md](LoadTesting.md)** and `bin/loadtest.sh`.
> **Backup & recovery:** see **[docs/BackupAndRecovery.md](BackupAndRecovery.md)** for the full 3-2-1 topology, restore procedure, and quarterly drill.

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
| `bin/db/one-shots/` | Environment-specific one-off data fixups. Each file is a standalone script or SQL file intended to be run once per environment (not migrations). Current scripts: `2026-05-20-backfill-chunk-embeddings.sh` (backfill `content_chunk_embeddings`), `reconcile_page_canonical_ids.sh` (reconcile `page_canonical_ids`), `reset_judge_timeout_abstains.sh`, `reset_node_judge_verdicts.sh`, `backfill-agent-default-owner.sql`. | Review individually before running; most are idempotent but data-modifying. |
| `bin/tests/test-audit-retention.sh` | Pure-filesystem unit tests for `audit-retention.sh` using stubbed `psql`/`pg_dump`/`pg_restore`. No real PostgreSQL required. | Read-only test harness. |
| `bin/tests/test-backup.sh` | Tests for `backup.sh` and `nas-pull.sh` manifest + metrics emission. Stubbed `pg_dump`/`psql`/`rsync`/`curl` — no real PG or ssh. | Read-only test harness. |
| `bin/tests/test-remote.sh` | Smoke tests for `bin/remote.sh` in `--dry-run` mode with a fake `remote.env`. No real ssh or docker. | Read-only test harness. |

**`WIKANTIK_SEED_DEV_USERS` and `-e base`**
- Setting `WIKANTIK_SEED_DEV_USERS=true` in `.env` causes the entrypoint to ensure the default admin (admin/admin123, must-change-on-first-login) exists via `bin/db/seed-users.sql`. Fresh databases get the same flagged admin from migrations V002+V039 regardless. **Never set in production.**
- Running `bin/container.sh -e base` starts the stack with the base compose only (no overlays) — useful for debugging compose variable substitution or running the stack without the dev or prod overlay.

---

## 4. Knowledge Graph Administration

The Knowledge Graph captures structured entities (nodes) and their relationships (edges). Administered primarily via `/admin/knowledge/` in the UI and supported by Model Context Protocol (MCP) integrations.

### 4.1 Provenance & Graph Projector
Nodes and edges carry a `provenance` label:
- `human-authored`: Extracted from YAML frontmatter (e.g., `related: [PageName]`) and body links.
- `ai-inferred`: Suggested by AI but pending admin approval.
- `ai-reviewed`: AI proposals validated by an administrator.

The **Graph Projector** runs on every page save. It parses frontmatter to insert relationship edges and removes stale data. Missing target pages are created as "stub nodes".

### 4.2 Proposals & AI Integration
AI agents connected via MCP submit `new-node`, `new-edge`, or `modify-property` proposals through the `propose_knowledge` tool. 
- **Approval:** A newly accepted `new-edge` relation is written back to the source page's YAML frontmatter.
- **Rejection:** Rejections include reasons and prevent agents from submitting the exact same proposal again.

### 4.3 Node & Edge Curation
- **Stub Nodes:** Frequent review of stub nodes is encouraged. They represent missing pages or potential typos in YAML blocks.
- **Edge Types:** Standard conventions (e.g., `related`, `depends_on`, `implements`, `supersedes`) should be maintained across the wiki for querying clarity.

### 4.4 Embeddings & Advanced Quality Tools
Wikantik leverages a unified embedding model for hybrid search and structural similarity.
- **Merge Candidates:** Finds structurally and semantically similar nodes. Merging updates all corresponding edges and frontmatter references.
- **Missing Edges:** The system predicts missing relationships based on topology.
- **Low-Plausibility Edges:** Flags existing connections that seem structurally anomalous. 
- **Pages Without Frontmatter:** Accessible under Content Embeddings, used to flag pages that have zero footprint in the semantic graph.