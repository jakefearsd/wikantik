---
type: article
related:
- About
- GoodMcpDesign
cluster: wikantik-development
tags:
- operations
- docker
- deployment
- backup
title: Wikantik on Docker
summary: Guide to deploying Wikantik with Docker Compose, including production setup,
  automated backups, restore procedures, and migration from bare-metal Tomcat
canonical_id: 01KQ0P44YXNZNQZDD0MBEJ5KYH
status: active
---

# Wikantik on Docker

This guide covers everything you need to deploy Wikantik using Docker Compose, from initial setup through production operations and disaster recovery. The repository ships the real artifacts (compose files, `docker/entrypoint.sh`, `bin/container.sh`, `bin/remote.sh`); this page explains how to drive them. Two companion pages go deeper: [Docker Deployment](DockerDeployment) (the full variable reference) and [Wikantik Backup and Recovery](WikantikBackupAndRecovery) (the 3-2-1 backup model and restore drill). The configuration keys are generated into [Wikantik Configuration Reference](WikantikConfigurationReference), and a cloud VM (AWS or GCP) is covered by [Wikantik Cloud Deployment](WikantikCloudDeployment). For the production host and the release pipeline, see [Host: docker1 — Wikantik Production](HostDocker1) and [Wikantik Release and Deploy Process](WikantikReleaseAndDeployProcess).

## Architecture Overview

The production stack (`docker-compose.yml` plus `docker-compose.prod.yml`) runs three services:

| Service | Image | Purpose |
|---------|-------|---------|
| **db** | `pgvector/pgvector:pg18` | PostgreSQL with the pgvector extension: users, groups, policy grants, API keys, the Knowledge Graph, embeddings, page metadata, and the `schema_migrations` ledger |
| **wikantik** | `wikantik:latest` (built from the `Dockerfile`, or the released `ghcr.io/jakefearsd/wikantik:X.Y.Z` image; Tomcat 11.0.27 / JDK 25) | Runs the wiki application |
| **backup** | `postgres:18-alpine` | Automated database dumps and page file archives (production overlay only) |

The base file alone starts only `db` and `wikantik`. All services share the compose project's private network, and `wikantik` connects to `db` by hostname. The `backup` container connects to `db` for `pg_dump` and mounts the same pages directory as `wikantik` to archive page files. The production overlay bundles no embedding service: `wikantik` embeds on the shared GPU inference host (`WIKANTIK_EMBEDDING_BASE_URL` in `.env`, for example `http://inference.lan:11434`), and if the embedder is unreachable, retrieval falls back to BM25. Port 8080 is the wiki's published port (`WIKANTIK_HOST_PORT`, default 8080). The production overlay also publishes PostgreSQL on `${DB_HOST_BIND:-172.17.0.1}:5432` (the docker0 bridge, so it is not LAN-exposed) for the external metrics exporter, and the dev overlay publishes the database on 15432 and a debug port on 5005.

### What lives where

| Data | Container path | Storage | Can rebuild? | Backed up? |
|------|---------------|---------|-------------|------------|
| Wiki pages (.md + .properties) | `/var/wikantik/pages` | Prod overlay: host directory `WIKANTIK_PAGES_DIR` (default `/srv/wikantik/pages`); base file: volume `wikantik-pages` | **NO** | **YES** |
| File attachments | `/var/wikantik/pages` | same as pages | **NO** | **YES** |
| PostgreSQL data | `/var/lib/postgresql` (the pg18 image keeps its data under a version-specific subdirectory here) | volume `pgdata` (compose prefixes the project name, for example `repo_pgdata`) | **NO** | **YES** |
| Lucene search index | `/var/wikantik/work` | volume `wikantik-work` | YES (auto-rebuilds on startup) | No |
| Ontology TDB2 store | `/var/wikantik/work` | volume `wikantik-work` | YES (rebuilds on startup) | No |
| Application logs | `/var/wikantik/logs` | volume `wikantik-logs` | N/A | No |

The three items that cannot be rebuilt (pages, attachments, database) are all covered by the automated backup system.

## File Layout

```
docker/
  config/
    server.xml              Tomcat HTTP connector, RemoteIpValve, access log
    catalina.properties     Tomcat classpath and security settings
    log4j2-docker.xml       Console (ECS JSON) + rolling file logging for Docker
  db/
    001-init.sql            Creates the pgvector extension (first startup only); schema comes from migrations
  backup/
    backup.sh               Runs pg_dump + tar with tiered retention
    restore.sh              Guided restore with checksum verification
    crontab                 Schedules daily/weekly/monthly backups
  docker-compose.embeddings.yml   Standalone CPU embedder used by bin/deploy-local.sh and the IT phase
  entrypoint.sh             Generates config files from environment variables, runs migrations
docker-compose.yml          Base services: db + wikantik
docker-compose.dev.yml      Dev overrides: bind mounts, debug port, no backup
docker-compose.prod.yml     Prod overrides: resource limits, backup service
docker-compose.test.yml     Test overrides: alternate ports, namespaced volumes
docker-compose.cloud.yml    Cloud-VM overlay (see Wikantik Cloud Deployment)
Dockerfile                  Production: multi-stage Maven build inside container
Dockerfile.dev              Dev: Tomcat only, WAR bind-mounted from host
.env.example                Template for all environment variables
bin/container.sh            Local docker compose wrapper (-e dev|prod|test|base)
bin/remote.sh               ssh-driven remote deploy and administration
```

## Environment Variables

All configuration is driven by environment variables in a `.env` file. Copy the template to get started:

```bash
cp .env.example .env
```

Then edit `.env` with your values. The most important ones:

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_DB` | `wikantik` | Database name |
| `POSTGRES_USER` | `wikantik` | Database user |
| `POSTGRES_PASSWORD` | `CHANGEME` | **Change this!** Database password |
| `WIKANTIK_BASE_URL` | `http://localhost:8080/` | Public URL of the wiki |
| `WIKANTIK_PAGES_DIR` | `/srv/wikantik/pages` | **Host** directory bind-mounted as the page tree (production overlay) |
| `WIKANTIK_PAGE_DIR` | `/var/wikantik/pages` | Page storage path inside the container |
| `WIKANTIK_WORK_DIR` | `/var/wikantik/work` | Work directory (Lucene index, caches) |
| `WIKANTIK_ATTACHMENT_DIR` | `/var/wikantik/pages` | Attachment storage path |
| `WIKANTIK_HOST_PORT` | `8080` | Published host port |
| `MCP_RATE_LIMIT_GLOBAL` | `500` | MCP requests/second (all clients) |
| `MCP_RATE_LIMIT_PER_CLIENT` | `50` | MCP requests/second (per client) |
| `MAIL_SMTP_HOST` | (empty) | SMTP server for email notifications; leave empty to disable email |
| `MAIL_SMTP_PORT` | `587` | SMTP port |
| `MAIL_SMTP_ACCOUNT` | (empty) | SMTP username |
| `MAIL_SMTP_PASSWORD` | (empty) | SMTP password |
| `MAIL_FROM` | (empty) | From address for emails |
| `BACKUP_RETENTION_DAYS` | `30` | Days to keep daily backups |
| `BACKUP_DIR` | `./backups` | Host path for backup files (the value in `.env.example`; the production overlay's own fallback is `/srv/wikantik/backups`) |

The container also reads the performance, search-backend, SSO, GenAI-mode and proxy variables (`WIKANTIK_MAX_INFLIGHT_REQUESTS`, `WIKANTIK_DENSE_BACKEND`, `WIKANTIK_SSO_*`, `WIKANTIK_GENAI_MODE`, `PROXY_REMOTE_IP_HEADER`, and others); they are tabulated in section 1 of [Docker Deployment](DockerDeployment).

**Important:** The `.env` file contains secrets (database password, SMTP credentials). It is excluded from Git by `.gitignore`. Keep a copy of this file somewhere safe outside the repository. For a remote deploy driven by `bin/remote.sh`, keep the production values in a gitignored `.env.prod`; `remote.sh` ships it to the host as `.env`, preferring it over the local dev `.env`.

**Authorizing an MCP client is not an `.env` setting.** There is no environment
variable that grants a bearer token — `McpAccessFilter` fails closed (503) unless
it sees a DB-backed API key or a CIDR allowlist match. Log into the running
container as an admin and mint a key at `/admin/apikeys` with scope `mcp_admin`,
`mcp_read`, `tools`, or `all`; see [McpIntegration](McpIntegration) for the
full authentication and authorization mechanism.

## How the Entrypoint Works

The `docker/entrypoint.sh` script runs every time the wikantik container starts. It generates three configuration files from environment variables and then applies database migrations:

1. **`wikantik-custom.properties`** — Wiki settings: base URL, page directory, PostgreSQL JDBC datasource name, SMTP config, and `wikantik.use.external.logconfig = true` so the baked `log4j2-docker.xml` is active
2. **`ROOT.xml`** — Tomcat context with a single JNDI DataSource (`jdbc/WikiDatabase`) pointing to the PostgreSQL container
3. **`wikantik-mcp.properties`** — MCP server name/title/version and rate limits. This single properties file configures **both** MCP endpoints (`/wikantik-admin-mcp` and `/knowledge-mcp`); the filename is retained from the original module name for backward compatibility. It does not carry access keys — MCP authorization is DB-backed (see above), not entrypoint-generated.

Then it runs `migrate.sh` (idempotent) before starting Tomcat, so a new release applies its schema migrations on start with no manual database step.

This means you never edit config files inside the container. Change an environment variable, restart the container, and the new config takes effect. Because `ROOT.xml` is regenerated on every start, anything you add to it by hand (for example a JNDI mail session) is lost; use the `MAIL_SMTP_*` variables instead (see [Sending Email from the Wiki](SendingEmailFromTheWiki)).

## Production Deployment

### First-time setup

```bash
# 1. Create your .env file
cp .env.example .env
# Edit .env — at minimum, change POSTGRES_PASSWORD and WIKANTIK_BASE_URL

# 2. Create the host directories for pages and backups
#    (pages default to /srv/wikantik/pages; override with WIKANTIK_PAGES_DIR.
#    Backups go to BACKUP_DIR, ./backups as set in .env.example)
sudo mkdir -p /srv/wikantik/pages
mkdir -p backups

# 3. Build and start everything
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

This will:
- Pull the `pgvector/pgvector:pg18` image and start PostgreSQL; `docker/db/001-init.sql` creates the pgvector extension
- Build the Wikantik image (multi-stage Maven build, takes a few minutes the first time)
- Start Wikantik, whose entrypoint applies every migration from `bin/db/migrations/` (this creates the schema and the default `admin` account) and then begins serving
- Start the backup container with cron scheduling

Two follow-ups are needed on a fresh host. The page directory starts empty, so push your page tree into it (`bin/remote.sh pages-push docs/wikantik-pages` from a workstation, or copy the files in). And point the wiki at an embedding service: set `WIKANTIK_EMBEDDING_BASE_URL` (and `WIKANTIK_EXTRACTOR_BASE_URL` for Knowledge Graph extraction) in `.env` to the shared GPU inference host, using a name that resolves from the container (for example `http://inference.lan:11434`). The shipped default, `http://inference.jakefear.com:11434`, does not resolve on every network. Until it is reachable, retrieval runs on BM25 alone.

For a remote host the supported path is `bin/remote.sh bootstrap` followed by the manual first-deploy sequence in [Docker Deployment](DockerDeployment) ("Remote host — first deploy"), not `remote.sh deploy`, which would migrate an empty schema.

### Verifying it works

```bash
# Check all services are running and healthy
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# Check the application health endpoint (engine, database, search index).
# It answers only to loopback and private-network clients.
curl -fsS http://localhost:8080/api/health

# Check PostgreSQL has the admin user
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec db psql -U wikantik -d wikantik -c "SELECT login_name FROM users;"

# View wikantik startup logs
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs wikantik
```

The MCP endpoints (`/wikantik-admin-mcp` and `/knowledge-mcp`) use Streamable HTTP and require an API key, so a plain unauthenticated `curl` is not a useful check.

### Default admin login

Migration V002 creates an admin user, and migration V039 flags it so the first login forces a new password. Open `http://your-host:8080/` and log in:

- **Username:** `admin`
- **Password:** `admin123`

You are required to choose a new password at first login. Do that before the deployment is reachable by anyone else.

### Deploying updates

The routine path is a release image swap, driven from the developer box: `bin/cut-release.sh X.Y.Z` then `bin/deploy-release.sh X.Y.Z` (see [Wikantik Release and Deploy Process](WikantikReleaseAndDeployProcess)). The database volume and the page directory persist, and the entrypoint applies any new migrations on start.

On a single host that builds from source, rebuild only the wikantik image and restart it:

```bash
# PostgreSQL and backup keep running — no data loss
docker compose -f docker-compose.yml -f docker-compose.prod.yml build wikantik
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik
```

Downtime is roughly 30 seconds while the container restarts; the production healthcheck allows 90 seconds on a cold start to cover migrations. If you have Cloudflare in front, it serves cached content during the gap.

### Stopping everything

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml down
```

This stops all containers but **preserves all Docker volumes** (database, logs, search index) and the page directory. Your data is safe. To start again:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

**Warning:** `docker compose down -v` deletes all volumes and destroys the database. Never use `-v` unless you intentionally want to start from scratch.

## Backup System

The backup system is the most important part of the production deployment. It protects against data loss from disk failure, accidental deletion, software bugs, or corrupted content.

### What gets backed up

Every backup captures **two things**:

1. **The PostgreSQL database** — `pg_dump` creates a complete SQL dump of the whole database (users, roles, groups, policy grants, API keys, the Knowledge Graph, embeddings, page metadata, and the `schema_migrations` ledger), gzip-compressed as `db.sql.gz`. It restores to a PostgreSQL server of the same major version or newer.

2. **Wiki page files** — `tar` archives the entire `/var/wikantik/pages` directory, which contains:
   - `.md` files (the actual article content in Markdown)
   - `.properties` files (page metadata: author, change notes, timestamps)
   - Attachment files (anything uploaded to wiki pages)

Both are essential. Without the database, you lose all user accounts, groups, and the Knowledge Graph. Without the page files, you lose all wiki content. The backup system captures both in every run.

### Backup schedule and retention

The cron schedule (`docker/backup/crontab`, container time) runs three tiers of backups:

| Tier | Schedule | Retention | Purpose |
|------|----------|-----------|---------|
| **Daily** | 2:00 AM every day | 30 days (`BACKUP_RETENTION_DAYS`) | Recover from recent mistakes |
| **Weekly** | 3:00 AM every Sunday | 12 weeks | Recover from issues noticed late |
| **Monthly** | 4:00 AM on the 1st | 12 months | Long-term recovery point |

Each backup creates a directory like `backups/daily/2026-03-21/` containing:

```
backups/
  daily/
    LATEST                  name of the newest snapshot
    2026-03-21/
      db.sql.gz             PostgreSQL dump (the whole database), gzip-compressed
      pages.tar.gz          All wiki pages, properties, and attachments
      checksums.sha256      SHA-256 hashes for integrity verification
      backup-status.json    Status manifest for the run
    2026-03-20/
      ...
  weekly/
    2026-03-16/
      ...
  monthly/
    2026-03-01/
      ...
```

### Running a manual backup

You can trigger a backup at any time without waiting for cron:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/backup.sh daily

# From a workstation, against a remote host:
bin/remote.sh backup-trigger daily
```

**Always run a manual backup before deploying updates or making major changes.**

### Verifying backups

Check that backups are being created:

```bash
# List recent backups on the host
ls -la backups/daily/

# Check backup sizes (should not be zero)
du -sh backups/daily/*/

# Verify checksums of a specific backup
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup sh -c "cd /backups/daily/2026-03-21 && sha256sum -c checksums.sha256"
```

### Changing the backup directory

By default, backups go to `./backups` on the host (relative to the compose files). To use a different location (like an external drive or NFS mount), set `BACKUP_DIR` in your `.env`:

```bash
BACKUP_DIR=/mnt/external-backup/wikantik
```

### Changing retention

To keep daily backups for 60 days instead of 30:

```bash
BACKUP_RETENTION_DAYS=60
```

Then restart the backup container:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps backup
```

### Off-box copies

The sidecar's snapshots live on the same host as the data, so a host loss takes them too. The production deployment adds a second host that pulls every snapshot over read-only rsync (`bin/backup/nas-pull.sh`), so a compromised app host cannot reach the off-box archive; see [Wikantik Backup and Recovery](WikantikBackupAndRecovery) and [NAS and Offsite Backups](NasAndOffsiteBackups). `bin/remote.sh backup-pull [DATE]` fetches a snapshot to your workstation.

## Restore Procedure

Restoring from backup is a three-step process. The restore script handles the actual data recovery; you just need to stop the wiki, run the script, and start the wiki again.

### Step-by-step restore

```bash
# 1. Stop the wiki (prevents writes during restore)
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik

# 2. See what backups are available
ls backups/daily/

# 3. Run the restore script (pick the date you want)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/restore.sh /backups/daily/2026-03-21

# 4. Start the wiki
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
```

The restore script will:
1. Verify SHA-256 checksums to confirm the backup is not corrupted
2. Drop and rebuild the whole `public` schema from the dump, then check that `users`, `kg_nodes` and `page_canonical_ids` loaded and that `users` is not empty
3. Clear the current pages directory and extract the backup archive
4. Report how many pages were restored

After starting the wiki, the Lucene search index rebuilds automatically from the restored page files. This takes 30-60 seconds depending on the number of pages. The entrypoint also applies any migrations newer than the restored dump. From a workstation, `bin/remote.sh restore REMOTE_PATH` runs the sidecar restore and restarts the stack.

### Restoring only the database (keeping current pages)

`restore.sh` always restores both parts. To restore only the database, repeat its database step by hand:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik

docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup sh -c '
    psql -h db -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 \
      -c "DROP SCHEMA IF EXISTS public CASCADE;" \
      -c "CREATE SCHEMA public;" \
      -c "GRANT ALL ON SCHEMA public TO \"$POSTGRES_USER\";" \
      -c "GRANT ALL ON SCHEMA public TO public;" \
      -c "CREATE EXTENSION IF NOT EXISTS vector;" \
      -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
    gunzip -c /backups/daily/2026-03-21/db.sql.gz \
      | psql -h db -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -q
  '

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
```

### Restoring only pages (keeping current users)

If you only need to restore page content without touching the database:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik

docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup sh -c "
    rm -rf /var/wikantik/pages/*
    tar -xzf /backups/daily/2026-03-21/pages.tar.gz -C /var/wikantik/pages
  "

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
```

### Disaster recovery: restoring to a fresh machine

If the original server is gone and you are starting from a new machine with only the backup files, `bin/dr-restore.sh TARGET_HOST` automates the whole sequence from a workstation (it stages the compose stack, ships the image, transfers a verified snapshot, runs the real `restore.sh`, starts the app and smoke-tests it; see [Wikantik Backup and Recovery](WikantikBackupAndRecovery)). The manual equivalent:

```bash
# 1. Clone the repository
git clone <your-wikantik-repository-url> wikantik
cd wikantik

# 2. Create .env with your production values
cp .env.example .env
# Edit .env with the same POSTGRES_PASSWORD and other settings

# 3. Start only PostgreSQL (let it initialize)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d db
# Wait for it to become healthy:
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# 4. Copy your backup files to the backups directory
mkdir -p backups/daily/2026-03-21
cp /path/to/your/backup/db.sql.gz backups/daily/2026-03-21/
cp /path/to/your/backup/pages.tar.gz backups/daily/2026-03-21/
cp /path/to/your/backup/checksums.sha256 backups/daily/2026-03-21/

# 5. Build and start wikantik (this runs the migrations on the empty database)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build wikantik

# 6. Start the backup container
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d backup

# 7. Run the restore (replaces the freshly migrated schema with your real data)
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/restore.sh /backups/daily/2026-03-21

# 8. Start wikantik to pick up the restored content
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
```

## Migrating from Bare-Metal Tomcat

If you are currently running Wikantik on a bare-metal Tomcat with a local PostgreSQL database, follow these steps to migrate to containers. The container database must be the same PostgreSQL major version as the source or newer: dumps restore forward, not backward.

### Before you start

Make a safety backup of your current system. Use your own database name and role (a standard install uses `wikantik` for both):

```bash
# Dump the existing database
pg_dump -U <db-user> -d <db-name> --no-owner --no-privileges > pre-migration.sql

# Archive the current pages
tar -czf pre-migration-pages.tar.gz -C /path/to/your/wikantik-pages .
```

Keep these files safe. They are your rollback path.

### Migration steps

```bash
# 1. Stop the bare-metal Tomcat
/path/to/tomcat/bin/shutdown.sh

# 2. Create .env with production values
cp .env.example .env
# Set POSTGRES_PASSWORD, WIKANTIK_BASE_URL, SMTP settings, etc.

# 3. Start only PostgreSQL
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d db
# Wait for healthy...

# 4. Clear the public schema, then load the dump. (The schema comes from the
#    migrations, which have not run because only db is up; the dump recreates
#    the pgvector extension, all tables, data, and schema_migrations.)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec -T db psql -U wikantik -d wikantik -v ON_ERROR_STOP=1 \
    -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO wikantik; GRANT ALL ON SCHEMA public TO public;"
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec -T db psql -U wikantik -d wikantik -v ON_ERROR_STOP=1 -q < pre-migration.sql

# 5. Build and start wikantik (the entrypoint applies only genuinely new migrations)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build wikantik

# 6. Copy your existing pages into the page directory
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec -T wikantik sh -c "rm -rf /var/wikantik/pages/*"
docker cp pre-migration-pages.tar.gz \
  $(docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q wikantik):/tmp/
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec wikantik sh -c "tar -xzf /tmp/pre-migration-pages.tar.gz -C /var/wikantik/pages && rm /tmp/pre-migration-pages.tar.gz"

# 7. Restart wikantik to rebuild the search index with the real pages
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart wikantik

# 8. Start the backup service
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d backup
```

### Verify the migration

- Browse to your wiki URL and confirm pages load correctly
- Log in with your existing admin account
- Check that search works (the index rebuilds on startup)
- Create a test page and verify it persists across a container restart
- Run a manual backup and verify the output:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/backup.sh daily
```

### If something goes wrong

You still have the bare-metal Tomcat and the pre-migration backup files. To roll back:

```bash
# Stop the containers
docker compose -f docker-compose.yml -f docker-compose.prod.yml down

# Start the bare-metal Tomcat again
/path/to/tomcat/bin/startup.sh
```

## Development Workflow

The dev setup skips the production build and backup service. It bind-mounts your local files so changes appear immediately.

```bash
# Build the WAR on your host (uses your local Maven cache — fast).
# Use -DskipTests, not -Dmaven.test.skip (which breaks the reactor).
mvn clean install -DskipTests -T 1C

# Start the dev environment
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# Wiki: http://localhost:8080/
# Debug port: 5005 (attach IntelliJ/VS Code remote debugger)
# PostgreSQL: localhost:15432 (connect with any SQL client)
```

Key differences from production:
- **Pages are bind-mounted** from `docs/wikantik-pages/` — edits in the wiki appear in your Git working directory
- **WAR is bind-mounted** from `wikantik-war/target/` — rebuild the WAR and restart the container to pick up changes
- **Debug port 5005** is exposed for remote JVM debugging
- **PostgreSQL port 15432** is exposed (not 5432, to avoid conflicting with a local PostgreSQL)
- **No backup sidecar** runs
- **MCP authorization is still DB-backed**: mint a key at `/admin/apikeys` after logging in (the access filter fails closed otherwise)

`bin/container.sh -e dev up -d` is the shorthand for the same thing. For bare-metal development (local PostgreSQL and Tomcat), see [PostgreSQL Local Deployment](PostgreSQLLocalDeployment).

## Cloudflare Integration

The Tomcat `server.xml` includes two settings for running behind Cloudflare:

1. **`RemoteIpValve`** — Extracts the real client IP from the header named by `PROXY_REMOTE_IP_HEADER` (default `CF-Connecting-IP`; use `X-Forwarded-For` behind Caddy, nginx or a cloud load balancer). Without this, all requests appear to come from the proxy's IPs, which also breaks per-client rate limiting and audit logging.

2. **`AccessLogValve`** — Logs the `CF-IPCountry` header for geographic insight.

Cloudflare terminates TLS, so the container only listens on HTTP port 8080. There is no HTTPS connector in the container.

If you are using `WIKANTIK_BASE_URL=https://wiki.example.com/`, the wiki generates HTTPS URLs for links, sitemaps, and canonical tags, even though the container itself receives HTTP. This is the correct configuration for Cloudflare proxying.

## Troubleshooting

### Container won't start

```bash
# Check logs for errors
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs wikantik

# Common issues:
# - "Connection refused" to db → PostgreSQL isn't healthy yet; wikantik waits for the db healthcheck
# - "CHANGEME" in logs → You didn't set POSTGRES_PASSWORD in .env
# - Port 8080 already in use → Stop your bare-metal Tomcat first
```

### Database connection errors

```bash
# Verify PostgreSQL is healthy
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps db

# Connect directly to verify
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec db psql -U wikantik -d wikantik -c "SELECT 1;"
```

### Pages not showing up

If the wiki starts but pages are missing, the pages directory may be empty:

```bash
# Check how many pages are in the directory
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec wikantik find /var/wikantik/pages -name '*.md' | wc -l
```

If zero, the directory was created empty. Restore from backup or copy pages in (see the migration steps, or `bin/remote.sh pages-push`).

### Backup container not running

```bash
# Check if it's running
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps backup

# Check cron logs
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs backup

# Verify cron schedule is loaded
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup crontab -l
```

## Quick Reference

```bash
# === Production ===
# Start everything
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# Rebuild and deploy wiki (db + backup keep running)
docker compose -f docker-compose.yml -f docker-compose.prod.yml build wikantik
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik

# View logs
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f wikantik

# Manual backup
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/backup.sh daily

# Restore from backup
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/restore.sh /backups/daily/2026-03-21
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik

# Stop everything (data preserved)
docker compose -f docker-compose.yml -f docker-compose.prod.yml down

# === Development ===
mvn clean install -DskipTests -T 1C
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```
