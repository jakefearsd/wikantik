# Professional Wikantik Deployment with Docker

This guide provides a comprehensive walkthrough for deploying a production-ready Wikantik instance using Docker, with a focus on configuration, data persistence, and automated backups.

## Current Application Endpoints

The deployed Wikantik instance includes the following endpoints:

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

The `wikantik-observability` module provides additional endpoints:

| Path | Description |
|------|-------------|
| `/api/health` | Application health checks |
| `/metrics` | Prometheus-compatible metrics |

Observability endpoints are IP-restricted to internal networks via `InternalNetworkFilter`.

## 1. Configuration

Wikantik's Docker container is highly configurable through environment variables. This allows you to customize your installation without modifying the core application files.

### Environment Variables

The following environment variables are available to configure your Wikantik instance. You can set them in your `docker-compose.yml` file or directly with the `docker run` command.

*   `CATALINA_OPTS`: Additional options for the Tomcat server. The default value, `-Djava.security.egd=file:/dev/./urandom`, is recommended for better performance on systems with low entropy.
*   `LANG`: Sets the language for the container. Defaults to `en_US.UTF-8`.
*   `wikantik_basicAttachmentProvider_storageDir`: The directory where attachments are stored. Defaults to `/var/wikantik/pages`.
*   `wikantik_fileSystemProvider_pageDir`: The directory where wiki pages are stored. Defaults to `/var/wikantik/pages`.
*   `wikantik_frontPage`: The name of the wiki's front page. Defaults to `Main`.
*   `wikantik_pageProvider`: The page provider to use. Defaults to `VersioningFileProvider`.
*   `wikantik_use_external_logconfig`: Set to `true` to use an external Log4j2 configuration file. Defaults to `true`.
*   `wikantik_workDir`: The working directory for Wikantik. Defaults to `/var/wikantik/work`.
*   `wikantik_datasource`: JNDI name of the PostgreSQL DataSource. Required for database-backed authorisation, groups, and the knowledge graph.

**Note on naming conventions**: The property prefix is `wikantik_`. The legacy `jspwiki_` prefix is still accepted by the property-override layer for backward compatibility with older deployments, but new deployments should use `wikantik_`.

## 2. Data Persistence and Backup Strategy

To ensure that your wiki's data persists across container restarts and to facilitate backups, it is crucial to use Docker volumes.

### Critical Data Directories

The following directories contain all of Wikantik's critical data and should be mounted as volumes:

*   `/var/wikantik/pages`: Contains the wiki pages and attachments.
*   `/var/wikantik/logs`: Contains the application logs.
*   `/var/wikantik/work`: The working directory for Wikantik.

Users, groups, and policy grants are stored in the PostgreSQL database — make
sure to back that up separately alongside the file volumes.

> **PostgreSQL 18+ data directory.** The `db` service uses
> `pgvector/pgvector:pg18`. From pg18 the official Docker images store the
> cluster under a version-specific subdirectory and expect the data volume
> mounted at `/var/lib/postgresql` — **not** the older `/var/lib/postgresql/data`.
> Mounting the old path makes the image refuse to start. If you bump the
> Postgres major version, re-check this mount path (and the backup sidecar
> image: `pg_dump` must be ≥ the server version). Keep the container's
> Postgres major version in step with your local dev Postgres — `pg_dump`
> restores forward across versions, not backward.

### Automated Backups

Our recommended backup strategy involves a dedicated backup container that has read-only access to the Wikantik data volume. This container runs a cron job to create compressed archives of the data at regular intervals.

## 3. Example Docker Compose Deployment

This example uses `docker-compose` to define and run a multi-container Wikantik application with an automated backup service.

### Project Structure

```
.
├── docker-compose.yml
└── backup/
    ├── backup.sh
    └── crontab
```

### `docker-compose.yml`

```yaml
version: '3.7'

services:
  wikantik:
    build: .
    container_name: wikantik
    restart: always
    ports:
      - "8080:8080"
    volumes:
      - wikantik-data:/var/wikantik

  backup:
    image: alpine
    container_name: wikantik-backup
    restart: always
    volumes:
      - wikantik-data:/var/wikantik:ro
      - ./backups:/backups
      - ./backup:/etc/periodic/daily
    command: ["crond", "-f", "-d", "8"]

volumes:
  wikantik-data:
```

### `backup/backup.sh`

```bash
#!/bin/sh

set -e

# Create a compressed archive of the wikantik data
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
BACKUP_FILE="/backups/wikantik-backup-${TIMESTAMP}.tar.gz"

tar -czf "${BACKUP_FILE}" -C /var/wikantik .

# Prune old backups (keep the last 7)
find /backups -name "wikantik-backup-*.tar.gz" -type f -mtime +7 -delete
```

### `backup/crontab`

```
# Run the backup script daily at 2:00 AM
0 2 * * * /etc/periodic/daily/backup.sh
```

### Deployment Steps

1.  **Create the necessary files and directories** as shown in the project structure above.
2.  **Make the `backup.sh` script executable**:

    ```bash
    chmod +x backup/backup.sh
    ```

3.  **Start the application**:

    ```bash
    docker-compose up -d
    ```

4.  **Verify the deployment**:

    *   Access Wikantik at `http://localhost:8080`.
    *   Check the logs of the backup container to ensure that the cron job is running:

        ```bash
        docker logs wikantik-backup
        ```

### Restoring from a Backup

To restore your Wikantik instance from a backup:

1.  **Stop the `wikantik` container**:

    ```bash
    docker-compose stop wikantik
    ```

2.  **Extract the backup archive** to the `wikantik-data` volume:

    ```bash
    docker run --rm -v wikantik-data:/var/wikantik -v $(pwd)/backups:/backups alpine tar -xzf /backups/wikantik-backup-<TIMESTAMP>.tar.gz -C /var/wikantik
    ```

3.  **Restart the `wikantik` container**:

    ```bash
    docker-compose start wikantik
    ```

Remember to also restore the PostgreSQL database (users, groups, policy
grants, knowledge-graph tables) from its own backup — file volumes alone are
not sufficient for a full restore.

## 4. External Services

The bundled `docker-compose.yml` runs PostgreSQL+pgvector inside the stack;
everything else is **external**:

| Service | Status | Default endpoint |
|---|---|---|
| **PostgreSQL + pgvector** | Bundled (compose) | `db:5432` (internal) |
| **Ollama** (embeddings + judge) | External, optional | `http://localhost:11434` from the host |
| **Anthropic API** (entity extraction) | External, optional | configured via `ANTHROPIC_API_KEY` env var |
| **Cloudflare** (TLS termination + CF-Connecting-IP) | External, optional | The bundled server.xml is pre-configured for it |

If `wikantik.search.embedding.backend` and
`wikantik.knowledge.extractor.backend` are both `disabled` (the install
default), no LLM endpoints are required. Operators turning on the KG /
hybrid retrieval features must point at a reachable Ollama or supply an
Anthropic key.

## 5. Coexisting Bare-Metal and Container Stacks

The two install paths can run side by side on the same machine — useful
when validating container changes against an existing bare-metal install.
The base `docker-compose.yml` reads `WIKANTIK_HOST_PORT` (default `8080`),
and `docker-compose.test.yml` is a ready-made override that publishes on
`18080:8080` plus `15432:5432`. Bring the test stack up alongside an
unrelated bare-metal install:

```bash
WIKANTIK_HOST_PORT=18080 docker compose \
    -f docker-compose.yml -f docker-compose.test.yml \
    -p wikantik-test up -d --build
```

The `-p wikantik-test` project flag namespaces all volumes (becomes
`wikantik-test_pgdata` etc.) so prod / dev state is untouched. Tear down
with `... -p wikantik-test down -v`.

## 6. Migrating Data Between Bare-Metal and Container

A one-time procedure for operators who have an existing bare-metal
install and want to bring its data into a container deploy (or the
reverse).

### Bare-metal → Container

1. **Stop bare-metal Tomcat.** `tomcat/tomcat-11/bin/shutdown.sh`
2. **Dump PostgreSQL.** Match the DB name from
   `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`:
   ```bash
   PGPASSWORD=… pg_dump -h localhost -U <user> -d <db> \
     --no-owner --no-privileges > /tmp/wikantik-bare.sql
   ```
3. **Snapshot pages.** The page provider points at `docs/wikantik-pages/`
   (or wherever `wikantik.fileSystemProvider.pageDir` resolves to). Tar
   it up:
   ```bash
   tar czf /tmp/wikantik-bare-pages.tar.gz -C docs/wikantik-pages .
   ```
4. **Bring up the container stack** (without `-v`, so volumes
   auto-create):
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
   ```
   Then immediately stop the wikantik service to avoid writes during
   restore: `docker compose stop wikantik`.
5. **Load the SQL dump:**
   ```bash
   cat /tmp/wikantik-bare.sql | docker compose exec -T db \
     psql -U $POSTGRES_USER -d $POSTGRES_DB
   ```
6. **Load the pages tarball into the volume:**
   ```bash
   docker run --rm -v wikantik_wikantik-pages:/dst \
     -v /tmp:/src alpine \
     tar xzf /src/wikantik-bare-pages.tar.gz -C /dst
   ```
7. **Restart wikantik:** `docker compose start wikantik`. The Lucene
   index rebuilds automatically (~30-60 s).
8. **Verify:** log in, query a known page, run the judge runner against
   a node proposal.

### Container → Bare-Metal

Reverse procedure: `docker compose exec backup /usr/local/bin/backup.sh
daily` then copy the resulting `db.sql` and `pages.tar.gz` from
`./backups/daily/<DATE>/`. Restore via standard `psql`+`tar` against
the bare-metal install.

### Verification Reference

A clean restore round-trip was exercised on this codebase:
backup → wipe volumes → restore → log in. The procedure is encoded in
`docker/backup/backup.sh` and `docker/backup/restore.sh` and runs
nightly via the prod-compose backup sidecar's crontab (daily / weekly /
monthly tiers, 30-day daily retention by default).
