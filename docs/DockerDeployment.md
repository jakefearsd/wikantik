# Wikantik Deployment with Docker

How to deploy Wikantik as a Docker Compose stack — locally and on a remote
host — and how to upgrade it for each release.

The repository ships the real deployment artifacts; this guide explains how
to drive them. Do not hand-write compose files.

- `docker-compose.yml` + `docker-compose.{dev,prod,test}.yml` — the stack
- `docker/entrypoint.sh` — renders container config from env vars at start
- `bin/container.sh` — local `docker compose` wrapper
- `bin/remote.sh` — ssh-driven remote deploy/admin
- `bin/cut-release.sh`, `bin/deploy-release.sh` — release/upgrade wrappers

## Application Endpoints

| Path | Description |
|------|-------------|
| `/` | React SPA (reader, editor, search) |
| `/page-graph` | Page Graph viewer (wikilink edges) |
| `/knowledge-graph` | Knowledge Graph viewer (LLM-extracted entities) |
| `/admin/` | Admin panel (user, content, and security management) |
| `/api/` | REST API (pages, attachments, search, history, knowledge graph) |
| `/wiki/{slug}?format=md\|json` | Raw content for crawlers and RAG ingestion |
| `/api/changes?since=…` | Incremental change feed for sync pipelines |
| `/wikantik-admin-mcp` | Admin MCP server (writes + analytics + verification stamping) — 25 tools |
| `/knowledge-mcp` | Knowledge MCP server (hybrid retrieval + Knowledge Graph + structural-spine + agent-projection) — 16 tools |
| `/tools/*` | OpenAPI 3.1 tool server (OpenWebUI-compatible) — 2 tools |
| `/api/health` | Application health checks |
| `/metrics` | Prometheus-compatible metrics (IP-restricted via `InternalNetworkFilter`) |

## 1. Configuration — the `.env` file

The stack reads its configuration from a single `.env` file in the deployment
directory; Docker Compose loads it both for variable substitution and as the
`wikantik` service's `env_file`. The container `entrypoint.sh` renders
`wikantik-custom.properties`, `ROOT.xml`, and `wikantik-mcp.properties` from
these variables **on every start** — so configuration changes mean editing
`.env` and redeploying, never editing files inside a running container.

Copy `.env.example` and fill it in. Key variables:

| Variable | Purpose |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Container Postgres credentials. `POSTGRES_PASSWORD` must not be left at `CHANGEME`. |
| `WIKANTIK_BASE_URL` | External base URL — sitemap, canonical links, IndexNow. |
| `WIKANTIK_PAGES_DIR` | **Host** path bind-mounted as the page tree (prod overlay). |
| `BACKUP_DIR` / `BACKUP_RETENTION_DAYS` | Host backup path + retention for the sidecar. |
| `MCP_ACCESS_KEYS` | Comma-separated MCP bearer keys (DB-backed `api_keys` also work). |
| `MAIL_SMTP_*` / `MAIL_FROM` | SMTP; leave `MAIL_SMTP_HOST` empty to disable email. |
| `WIKANTIK_HOST_PORT` | Published host port (default `8080`). |

### Performance / search-backend tuning (optional)

All have safe defaults baked into `ini/wikantik.properties`. Set them in `.env`
only when you want to override; the container `entrypoint.sh` injects each one
into `wikantik-custom.properties` on start. All are runtime-configurable
without an image rebuild — flip in `.env`, then `bin/remote.sh deploy --skip-build`
for a ~30 s restart.

| Variable | Default | Maps to property | Purpose |
|---|---|---|---|
| `WIKANTIK_DENSE_BACKEND` | `inmemory` | `wikantik.search.dense.backend` | `inmemory` or `pgvector`. In-memory dense scan (with Vector API SIMD) is the right call on a single-host deploy; `pgvector` becomes the win when you split the DB to its own host (architectural scaling lever). See [the pgvector design spec](../docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md). |
| `WIKANTIK_DENSE_EF_SEARCH` | `100` | `wikantik.search.dense.pgvector.ef_search` | Only used when `WIKANTIK_DENSE_BACKEND=pgvector`. HNSW recall/latency knob; higher = better recall, more CPU. |
| `WIKANTIK_LUCENE_DIRECTORY` | `nio` | `wikantik.search.lucene.directory.kind` | Lucene index backend: `nio` (NIOFSDirectory — read syscall + buffer copy) or `mmap` (MMapDirectory — page-cache-served, but pays Java 21's per-access MemorySession overhead under high concurrency). On hardware where the OS page cache already serves Lucene reads, `nio` wins; on disk-bound deploys, `mmap` is Lucene's recommended default. |
| `WIKANTIK_VERSIONING_CACHE_SIZE` | `100` | `wikantik.versioningFileProvider.cacheSize` | Page-properties cache size in `VersioningFileProvider`. Default `100` was small relative to a 12K-page corpus (load testing showed 56 % hit rate). Set to `5000` for a typical wiki (~5 MB heap cost, 99 %+ hit rate). `0` = single-entry, `-1` = disabled. |

For a remote deploy driven by `bin/remote.sh`, keep the production values in a
gitignored **`.env.prod`** at the repo root — `remote.sh` ships it to the
remote as `.env`, preferring it over the dev `.env` so a prod deploy never
disturbs local-dev config.

Knowledge-Graph / hybrid-retrieval backends (Ollama endpoint, model tags) are
*not* set by the entrypoint — they fall back to the baked-in defaults in
`ini/wikantik.properties` (`ollama` backend at `inference.jakefear.com:11434`).
The container needs network reach to that endpoint, or a `wikantik-custom.properties`
override, only if KG / hybrid retrieval is in use.

## 2. Data persistence

Three classes of state, with deliberately different storage:

- **PostgreSQL** (users, groups, policy grants, Knowledge Graph, API keys,
  page metadata, history) — the `db` service, in the named volume
  `<project>_pgdata`.
- **Pages + attachments** — bind-mounted from the host (`WIKANTIK_PAGES_DIR`)
  under the prod overlay, so `rsync` is the source of truth for the page tree
  independent of container lifecycle. Mounted at `/var/wikantik/pages`.
- **Work + logs** — named volumes (`wikantik-work`, `wikantik-logs`);
  regeneratable, no operator interest in their contents.

Both the DB volume and the page bind-mount **survive container replacement**,
which is what makes a release upgrade a plain image swap (§3).

> **PostgreSQL 18+ data directory.** The `db` service uses
> `pgvector/pgvector:pg18`. From pg18 the official Postgres Docker images
> store the cluster under a version-specific subdirectory and expect the data
> volume mounted at `/var/lib/postgresql` — **not** the older
> `/var/lib/postgresql/data`. Mounting the old path makes the image refuse to
> start. If you bump the Postgres major version, re-check this mount path and
> the backup sidecar image (`pg_dump` must be ≥ the server version). Keep the
> container's Postgres major version in step with your local dev Postgres:
> `pg_dump` restores forward across versions, not backward.

## 3. Deploying

### Local / single-host

`bin/container.sh` wraps `docker compose` over the four compose files:

```bash
bin/container.sh -e prod up -d      # base + docker-compose.prod.yml
bin/container.sh logs -f
bin/container.sh -e prod down
```

Environments: `dev` (default), `prod` (backup sidecar), `test` (alt ports).

### Remote host — first deploy

`bin/remote.sh` drives a remote Docker host over ssh. Configuration:
`remote.env` (ssh user/host + remote paths — copy from `remote.env.example`)
and a gitignored `.env.prod` (container config, §1).

The deploying OS user on the remote **must be in the `docker` group** —
`sudo usermod -aG docker <user>`, then a fresh login. `bin/remote.sh
bootstrap` verifies both that Docker is installed and that the daemon is
reachable, and tells you this fix if not.

The first deploy initialises state, so it is a manual sequence — *not*
`remote.sh deploy`, which does a full `up -d` that would migrate an empty
schema:

```bash
# 1. Verify Docker + create remote dirs + ship compose/scripts/.env
bin/remote.sh bootstrap

# 2. Transfer the released image (user chose SSH transfer over a GHCR pull)
docker pull ghcr.io/jakefearsd/wikantik:X.Y.Z
docker tag  ghcr.io/jakefearsd/wikantik:X.Y.Z wikantik:latest
docker save wikantik:latest | ssh REMOTE_HOST 'docker load'

# 3. Start ONLY the database
bin/remote.sh up -d db

# 4. Initialise the DB from a dump — see §5

# 5. Push the page tree
bin/remote.sh pages-push docs/wikantik-pages

# 6. Start the app + backup sidecar (entrypoint migrates the restored schema)
bin/remote.sh up -d

# 7. Verify
curl http://REMOTE_HOST:8080/api/health
```

### Remote host — routine release upgrades

Once a host is running, every later upgrade is an image swap: the DB volume
and the page bind-mount persist, and the entrypoint applies any pending
schema migrations on start. Two wrappers capture the happy path as one
`bash` run each:

```bash
# Cut the release (run a green build first — the script does not build).
# Pushing the tag triggers .github/workflows/release.yml, which builds and
# publishes ghcr.io/jakefearsd/wikantik:X.Y.Z + creates the GitHub Release.
bin/cut-release.sh X.Y.Z

# Once release.yml is green, deploy it: pull the image, then
# bin/remote.sh deploy --skip-build  (tag :rollback → save|load → up -d →
# health-poll → auto-rollback on failure).
bin/deploy-release.sh X.Y.Z
```

`bin/remote.sh rollback` re-promotes the previous image at any time. The one
case that is **not** a plain image swap is a Postgres major-version change
(e.g. pg18→pg19) — that needs a dump/restore or `pg_upgrade`.

### Backups

The prod overlay runs a `postgres:18-alpine` backup sidecar: scheduled
`pg_dump` + `pages.tar.gz` into `${BACKUP_DIR}`, tiered retention (daily /
weekly / monthly; 30-day daily default). The procedure is encoded in
`docker/backup/backup.sh` + `docker/backup/restore.sh`.

```bash
bin/remote.sh backup-trigger [daily|weekly|monthly]   # ad-hoc backup
bin/remote.sh backup-pull [DATE]                      # fetch a snapshot locally
bin/remote.sh restore REMOTE_PATH                     # sidecar restore + restart
```

### Monitoring

Monitoring is handled by the external **jakemon** stack — a Grafana Alloy agent on each host pushing metrics and logs to a central Prometheus + Loki + Grafana on host `inference`. The wikantik container exposes `/metrics`, which jakemon scrapes. There is no in-repo observability stack.

## 4. External services

The compose stack bundles PostgreSQL + pgvector; everything else is external:

| Service | Status | Endpoint |
|---|---|---|
| **PostgreSQL + pgvector** | Bundled (`db` service) | `db:5432` (internal) |
| **Ollama** (embeddings, extraction, judge) | External, optional | `ini/wikantik.properties` default `http://inference.jakefear.com:11434` |
| **Anthropic API** (alternative extractor backend) | External, optional | `ANTHROPIC_API_KEY` env var |
| **TLS terminator / reverse proxy** | External, optional | `server.xml` reads `X-Forwarded-*` |

The baked-in default for `wikantik.search.embedding.backend` and
`wikantik.knowledge.extractor.backend` is `ollama` — the container must reach
the Ollama endpoint for KG / hybrid-retrieval features. Set both to `disabled`
(via a `wikantik-custom.properties` override) if you do not run those.

## 5. Coexisting bare-metal and container stacks

The two install paths can run side by side on one machine. The base
`docker-compose.yml` reads `WIKANTIK_HOST_PORT` (default `8080`), and
`docker-compose.test.yml` publishes on `18080:8080` + `15432:5432`:

```bash
WIKANTIK_HOST_PORT=18080 docker compose \
    -f docker-compose.yml -f docker-compose.test.yml \
    -p wikantik-test up -d --build
```

`-p wikantik-test` namespaces all volumes (`wikantik-test_pgdata`, …) so
prod / dev state is untouched. Tear down with `… -p wikantik-test down -v`.

## 6. Initialising the database from an existing dump

Used by the first remote deploy (§3 step 4), and for migrating a bare-metal
install into a container.

1. **Dump the source DB.** Match the DB name/user from the source
   `ROOT.xml` (or `.env`):
   ```bash
   PGPASSWORD=… pg_dump -h SRC_HOST -U SRC_USER -d SRC_DB \
       --no-owner --no-privileges -f /tmp/wikantik-dump.sql
   ```
   Use a `pg_dump` whose version is ≥ the source server. The container DB
   must be the **same Postgres major version** as the source (restores go
   forward, not backward).
2. **Start only the `db` container** so the app never connects to a
   half-initialised schema: `bin/remote.sh up -d db` (or
   `bin/container.sh -e prod up -d db` locally). Wait for it to report
   healthy.
3. **Clear the image's seed schema.** `docker/db/001-init.sql` seeds a
   skeleton `users`/`roles`/`groups` schema on first DB init; drop it so the
   restore is clean:
   ```bash
   docker exec -i <db-container> psql -U $POSTGRES_USER -d $POSTGRES_DB \
     -v ON_ERROR_STOP=1 -c \
     "DROP SCHEMA public CASCADE; CREATE SCHEMA public;
      GRANT ALL ON SCHEMA public TO $POSTGRES_USER;
      GRANT ALL ON SCHEMA public TO public;"
   ```
4. **Restore the dump:**
   ```bash
   cat /tmp/wikantik-dump.sql | docker exec -i <db-container> \
     psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -q
   ```
   The dump recreates the `vector` extension, all tables, data, and the
   populated `schema_migrations` table.
5. **Push the pages** (`bin/remote.sh pages-push docs/wikantik-pages`) and
   **start the app** (`bin/remote.sh up -d`). The entrypoint's `migrate.sh`
   sees `schema_migrations` already populated and applies only genuinely new
   migrations.
6. **Verify:** `curl http://HOST:8080/api/health` → `status: UP`; log in;
   query a known page.

The reverse (container → bare-metal) is a sidecar backup
(`bin/remote.sh backup-trigger`) restored with standard `psql` + `tar`
against the bare-metal install.
