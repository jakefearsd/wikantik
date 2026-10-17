---
type: article
tags:
- docker
- deployment
- cloud-platforms
- backup
- self-hosting
canonical_id: 01KQ0P44PWCCMS8D7BBBM6HEZ5
cluster:
- cloud-platforms
- security/container-security
title: Docker Deployment
summary: 'Deploy Wikantik with Docker Compose: PostgreSQL + pgvector, .env configuration,
  volumes, the tiered backup sidecar, and restore.'
---
# Deploying Wikantik with Docker

> 🌐 **Product overview:** [Self-hosting & backup on wikantik.com](https://www.wikantik.com/enterprise/self-hosting-and-backup.html) — a plain-language walkthrough for readers and AI agents.

Wikantik is deployed as a Docker Compose stack: PostgreSQL with pgvector, the Wikantik application on Tomcat 11, and, in the production overlay, a backup sidecar. The repository ships the real artifacts (`Dockerfile`, `docker-compose*.yml`, `docker/`), so do not hand-write compose files. This page is a summary; the operator reference is `docs/admin/DockerDeployment.md` in the repository, with `docs/admin/BackupAndRecovery.md` for backups and `docs/admin/CloudDeployment.md` for the AWS/GCP VM overlay.

## 1. What Ships

**The image.** The `Dockerfile` is multi-stage. It builds the WAR (including the React frontend) in `maven:3.9-eclipse-temurin-25`, then runs it on `tomcat:11.0.27-jdk25-temurin` (Tomcat 11, Java 25). The WAR is unzipped as the ROOT context, so the wiki answers at `http://host:8080/`. The PostgreSQL JDBC driver is downloaded from Maven Central at build time (`ARG POSTGRES_JDBC_VERSION`, currently 42.7.14, which must match `<postgresql-jdbc.version>` in the root `pom.xml`). The image also carries the database migrations (`bin/db/`) and the default pages from `docs/wikantik-pages/`. Defaults: `CATALINA_OPTS="-Djava.security.egd=file:/dev/./urandom -XX:MaxRAMPercentage=70.0 --add-modules=jdk.incubator.vector"` and `LANG=en_US.UTF-8`. The container healthcheck is `curl -fsS http://localhost:8080/api/health`.

**The services.**

| Service | Image | Role |
|---------|-------|------|
| `db` | `pgvector/pgvector:pg18` | PostgreSQL with the `vector` extension: users, groups, policy grants, Knowledge Graph, embeddings, page metadata, `schema_migrations` |
| `wikantik` | `wikantik:latest` | Tomcat 11 / JDK 25 running the wiki |
| `backup` | `postgres:18-alpine` | Scheduled `pg_dump` and page-tree tarball (production overlay) |

The production overlay bundles no embedding service. Production embeds, and runs Knowledge Graph extraction, on the shared GPU inference host: set `WIKANTIK_EMBEDDING_BASE_URL` and `WIKANTIK_EXTRACTOR_BASE_URL` in `.env` to a name that resolves from the container (for example `http://inference.lan:11434`). If the embedding endpoint is unreachable, retrieval falls back to BM25.

**The compose files.** `docker-compose.yml` defines `db` and `wikantik`; `docker-compose.dev.yml`, `docker-compose.prod.yml` and `docker-compose.test.yml` are overlays, and `docker-compose.cloud.yml` is the cloud overlay. `bin/container.sh -e dev|prod|test|base` wraps `docker compose` over them (`build`, `up -d`, `logs -f`, `psql`, `migrate`, `backup`, `restore`), and `bin/remote.sh` drives a remote host over ssh.

**Quick start.**

```bash
cp .env.example .env                  # set POSTGRES_PASSWORD (never leave CHANGEME) and WIKANTIK_BASE_URL
bin/container.sh -e base build        # db + wikantik only, named volumes
bin/container.sh -e base up -d
curl -fsS http://localhost:8080/api/health
```

Use `-e prod` for a real host: it adds the backup sidecar, resource limits and the host bind mounts, so set `WIKANTIK_PAGES_DIR` and `BACKUP_DIR` first. `bin/container.sh smoke-test` brings up a throwaway test stack on port 18080 and checks `/api/health`.

On a fresh database the entrypoint prints the first-login banner: log in as `admin` / `admin123` and you are required to choose a new password.

## 2. Configuration

There are no `jspwiki_*` environment variables any more. Compose passes `.env` to the `wikantik` service, and on every start `docker/entrypoint.sh` renders three files from the container environment: `wikantik-custom.properties` (in Tomcat's `lib/`), `conf/Catalina/localhost/ROOT.xml` (the JNDI DataSource `jdbc/WikiDatabase`) and `wikantik-mcp.properties`. It then applies any pending database migrations and starts Tomcat. To change configuration, edit `.env` and redeploy; never edit files inside a running container.

| Variable | Default | Effect |
|----------|---------|--------|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `wikantik`, `wikantik`, `CHANGEME` | Database credentials (`POSTGRES_HOST` is `db` and `POSTGRES_PORT` is `5432` inside the stack) |
| `WIKANTIK_BASE_URL` | `http://localhost:8080/` | External URL: sitemap, canonical links, SSO callback |
| `WIKANTIK_PAGE_DIR`, `WIKANTIK_WORK_DIR`, `WIKANTIK_ATTACHMENT_DIR` | `/var/wikantik/pages`, `/var/wikantik/work`, `/var/wikantik/pages` | Page tree, work directory (search index), attachment store |
| `WIKANTIK_COOKIE_AUTHENTICATION` | `false` | Remember-me re-authentication across restarts |
| `WIKANTIK_SSO_ENABLED` and the other `WIKANTIK_SSO_*` | off | OIDC single sign-on; see [Google SSO](GoogleSSO) |
| `WIKANTIK_GENAI_MODE` | `full` | GenAI ceiling: `full`, `embeddings-only` or `none` |
| `WIKANTIK_KNOWLEDGE_ENABLED` | `true` | `false` skips the Knowledge Graph subsystem |
| `WIKANTIK_DENSE_BACKEND` | `lucene-hnsw` | Dense retrieval backend: `lucene-hnsw`, `inmemory` or `pgvector` |
| `WIKANTIK_EMBEDDING_BASE_URL` | `http://inference.jakefear.com:11434` | Embedding service. The shipped default does not resolve on every network, so set it to a name that resolves from the container (production uses the shared GPU inference host, e.g. `http://inference.lan:11434`); falls back to BM25 if unreachable |
| `WIKANTIK_EXTRACTOR_BACKEND` | `ollama` | Knowledge Graph extractor: `ollama`, `claude` (needs `ANTHROPIC_API_KEY`) or `disabled` |
| `WIKANTIK_MAX_INFLIGHT_REQUESTS` | `390` | Concurrency cap; excess requests get 503 with `Retry-After: 1` |
| `WIKANTIK_JVM_MAX_RAM_PERCENTAGE` | image default 70 | Heap share of the container memory limit (the production overlay sets 50 with a 3G limit) |
| `PROXY_REMOTE_IP_HEADER` | `CF-Connecting-IP` | Header carrying the real client IP; use `X-Forwarded-For` behind Caddy, nginx or a cloud load balancer |
| `MAIL_SMTP_HOST` (and `MAIL_SMTP_PORT`, `MAIL_SMTP_ACCOUNT`, `MAIL_SMTP_PASSWORD`, `MAIL_FROM`) | empty | Email is off until the host is set |
| `WIKANTIK_SCIM_TOKEN` | unset | SCIM bearer token; SCIM denies everything without it |
| `WIKANTIK_SEED_DEV_USERS` | `false` | Seeds the default admin; never set it in production |

MCP and `/tools/*` clients are authorized with database-backed API keys minted at `/admin/apikeys`; there is no environment variable that grants access. See [OpenWebUI Tool Server](OpenWebUIToolServer). The full variable list, with every default, is in `docs/admin/DockerDeployment.md`.

## 3. Data Persistence

User accounts and groups live in PostgreSQL (see [Relational User Database](RelationalUserDatabase)), not in files. Mount these so state survives container replacement:

- **PostgreSQL data:** the named volume `pgdata`, mounted at `/var/lib/postgresql` (the pg18 image layout).
- **Pages and attachments:** `/var/wikantik/pages`. The base compose uses the named volume `wikantik-pages`; the production overlay bind-mounts the host directory `WIKANTIK_PAGES_DIR` (default `/srv/wikantik/pages`).
- **Work and logs:** `wikantik-work` (`/var/wikantik/work`: the Lucene index, rebuilt at startup, and the ontology store) and `wikantik-logs` (`/var/wikantik/logs`). Both are regenerable and are not backed up.

## 4. Automated Backups

The production overlay runs a `postgres:18-alpine` sidecar with BusyBox `crond`. Its schedule is `docker/backup/crontab`, and each entry runs `docker/backup/backup.sh <tier>`:

| Tier | Schedule | Retention |
|------|----------|-----------|
| daily | 02:00 every day | 30 days (`BACKUP_RETENTION_DAYS`) |
| weekly | 03:00 on Sundays | 12 weeks |
| monthly | 04:00 on the 1st | 12 months |

Each run writes `${BACKUP_DIR}/<tier>/YYYY-MM-DD/` containing `db.sql.gz` (a `pg_dump` of the whole database), `pages.tar.gz`, `checksums.sha256` and `backup-status.json`, and updates a `LATEST` pointer. It also writes Prometheus textfile metrics such as `wikantik_backup_last_success_timestamp_seconds{tier}` into `BACKUP_METRICS_DIR`; those gauges are what the jakemon alerts watch (see [IncidentWikantikBackupStale](IncidentWikantikBackupStale)).

```bash
bin/container.sh -e prod backup weekly     # run a tier now
bin/remote.sh backup-trigger daily         # the same, on the remote host
bin/remote.sh backup-pull                  # fetch the newest daily snapshot
```

An off-box copy is pulled by `bin/backup/nas-pull.sh` on a separate host (a daily systemd timer); the full 3-2-1 design is in `docs/admin/BackupAndRecovery.md`.

## 5. Restoring from a Backup

1. Stop the application so nothing writes during the restore:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
   ```

2. Run `restore.sh` in the backup sidecar. It verifies the checksums first and aborts on a mismatch, drops and rebuilds the `public` schema, loads `db.sql.gz`, and restores the page tree:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml \
       exec backup /usr/local/bin/restore.sh /backups/daily/<YYYY-MM-DD>
   ```

3. Start the application again; the search index rebuilds on startup:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
   ```

`bin/container.sh -e prod restore /backups/daily/<YYYY-MM-DD>` runs step 2 only; stopping and starting the application stays with you. To stand up a whole new host from a snapshot use `bin/dr-restore.sh`, and to prove a snapshot restores cleanly without touching production use `bin/backup/verify-restore.sh`.

## 6. Releases and Rollback

Release images are published to GHCR as `ghcr.io/jakefearsd/wikantik:X.Y.Z`. `bin/deploy-release.sh X.Y.Z` pulls an image and runs `bin/remote.sh deploy --skip-build`, which tags the running image `:rollback`, ships the new one over ssh, runs `up -d`, polls `/api/health`, and re-promotes `:rollback` automatically if the poll fails. Schema migrations run on container start, so an upgrade is an image swap; only a PostgreSQL major-version change needs a dump and restore.

PostgreSQL is published only on the docker0 bridge in the production overlay (`DB_HOST_BIND`, default `172.17.0.1`), and `/api/health` and `/metrics` answer only to loopback and private-network clients.
