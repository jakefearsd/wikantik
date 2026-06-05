# Production Container Architecture

How a Wikantik production deployment is laid out and operated. This is the
topology + lifecycle view; for the step-by-step procedure see
[DockerDeployment.md](DockerDeployment.md), and for the GitHub Actions
side see [ci-cd-step-by-step.md](ci-cd-step-by-step.md).

## Topology

```
┌─────────────┐  cut-release.sh   ┌──────────────┐  deploy-release.sh  ┌────────────────────┐
│  Dev box    │ ───(git tag)────> │   GitHub      │ ──(docker save| ──> │  Production host   │
│             │                   │  Actions      │     ssh load)       │  (Docker)          │
│ build+test  │ <───image pull──── │  release.yml  │                     │  runs the stack    │
└─────────────┘   ghcr.io/...      └──────────────┘                     └────────────────────┘
```

There is **no CI deploy** and no self-hosted runner. The developer box cuts
a release (which `release.yml` builds and publishes to GHCR) and then drives
the deployment to the production host over ssh with `bin/remote.sh` /
`bin/deploy-release.sh`. The production host only runs containers.

## Container layout

The production host runs a Docker Compose stack — `docker-compose.yml` +
`docker-compose.prod.yml` (project name `repo` when deployed under
`bin/remote.sh`):

| Service | Image | Role |
|---------|-------|------|
| `db` | `pgvector/pgvector:pg18` | PostgreSQL + pgvector — users, groups, policy grants, Knowledge Graph, embeddings, page metadata, `schema_migrations` |
| `wikantik` | `wikantik:latest` (the released image) | Tomcat 11 / JDK 21 — the wiki application |
| `backup` | `postgres:18-alpine` | Scheduled `pg_dump` + page-tree tarball |
| *(jakemon)* | external | Grafana Alloy agent on this host scrapes `/metrics` and ships logs to the central Prometheus + Loki + Grafana on host `inference` — no in-repo observability stack |

The `wikantik` container's `entrypoint.sh` renders `wikantik-custom.properties`,
`ROOT.xml`, and `wikantik-mcp.properties` from `.env` on every start, then
runs `migrate.sh` (idempotent) before starting Tomcat.

## Data persistence

| Data | Location | Critical | Backed up | Rebuilds |
|------|----------|----------|-----------|----------|
| Wiki pages + attachments | host bind mount (`WIKANTIK_PAGES_DIR`) | **yes** | yes | no — this is the content |
| PostgreSQL cluster | named volume `pgdata` | **yes** | yes | no |
| Lucene search index | named volume `wikantik-work` | no | no | yes — rebuilt at startup |
| Application logs | named volume `wikantik-logs` | no | no | ephemeral |
| JFR profiling recordings | named volume `wikantik-profiling` (prod overlay) | no | no | non-critical; operator downloads via `/admin/profiling/jfr/recordings/{id}` |
| Metrics / log history | external (jakemon) | no | — | scrape target only; state held by jakemon |

The page tree is a **host bind mount** so `rsync` (`bin/remote.sh pages-push`)
is the source of truth for content, independent of container lifecycle. The
DB volume and the page mount both survive container replacement — which is
what makes a release upgrade a plain image swap.

## Release & deploy pipeline

1. `bin/cut-release.sh X.Y.Z` — version bump, CHANGELOG, tag, push.
2. The `v*.*.*` tag triggers `release.yml`, which builds and publishes
   `ghcr.io/jakefearsd/wikantik:X.Y.Z` and creates a GitHub Release.
3. `bin/deploy-release.sh X.Y.Z` — pulls that image and runs
   `bin/remote.sh deploy --skip-build`: tag the running image `:rollback`,
   `docker save | ssh 'docker load'`, rsync the compose files + `.env`,
   `up -d`, poll `/api/health`.

The container entrypoint applies any new schema migrations on start, so a
routine upgrade needs no manual DB step. The **first** deploy is different —
it initialises the DB from a dump and seeds the page tree; see
[DockerDeployment.md](DockerDeployment.md) §3 and §6.

## Rollback

- **Automatic** — if the post-deploy `/api/health` poll fails,
  `bin/remote.sh deploy` re-promotes the `:rollback` image and recreates the
  container.
- **Manual** — `bin/remote.sh rollback` re-promotes `:rollback` at any time.

Every deploy re-tags the outgoing image `wikantik:rollback` before swapping.

## Backups

The prod overlay runs the `backup` sidecar: scheduled `pg_dump` + a
`pages.tar.gz` of the page tree into `${BACKUP_DIR}` with SHA-256 checksums,
retained for `BACKUP_RETENTION_DAYS` (default 30). Operate it with
`bin/remote.sh backup-trigger`, `backup-pull`, and `restore`. Keep at least
one copy off-host — backups co-located with the wiki only protect against
software failure, not hardware loss.

## Disaster recovery

To rebuild on a fresh host: install Docker, `bin/remote.sh bootstrap`,
transfer the image, then restore the DB from the most recent `pg_dump` and
the page tree from the backup tarball (the DB-init-from-dump procedure in
[DockerDeployment.md](DockerDeployment.md) §6), and `up -d`. The Lucene
index rebuilds itself on first start.

## Security

- **PostgreSQL is published on the docker0 bridge only** — the prod overlay
  maps `${DB_HOST_BIND:-172.17.0.1}:5432:5432`, binding the port to the docker0
  bridge gateway by default so the jakemon monitoring agent's postgres-exporter
  can reach it via `host.docker.internal:5432`. It is **not** on `0.0.0.0`
  (not LAN-exposed) unless `DB_HOST_BIND` is explicitly overridden.
- `/metrics` is restricted to RFC 1918 / loopback by `InternalNetworkFilter`; scraped by the external jakemon agent, not exposed publicly.
- **MCP endpoints** require a bearer token / API key (`MCP_ACCESS_KEYS` plus
  the DB-backed `api_keys` table).
- Secrets live only in `.env` / `.env.prod` (gitignored) — never committed.
  Back the env file up separately from the repo.

## Health & verification

```bash
bin/remote.sh status                                  # health + ps + disk
curl -fsS http://HOST:8080/api/health | jq            # engine + database + searchIndex
```

The container healthcheck polls `http://localhost:8080/api/health`
(`start_period` 90s). A deploy is considered healthy when that endpoint
returns HTTP 200 with `status: UP`.
