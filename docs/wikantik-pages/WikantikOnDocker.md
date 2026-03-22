---
type: article
status: active
tags:
- operations
- docker
- deployment
- backup
summary: "Complete guide to deploying Wikantik with Docker Compose, including production setup, automated backups, restore procedures, and migration from bare-metal Tomcat"
cluster: wikantik-operations
related: [About, GoodMcpDesign]
---

# Wikantik on Docker

This guide covers everything you need to deploy Wikantik using Docker Compose, from initial setup through production operations and disaster recovery.

## Architecture Overview

The containerized deployment runs three services orchestrated by Docker Compose:

| Service | Image | Purpose |
|---------|-------|---------|
| **db** | `postgres:17-alpine` | Stores users, groups, and roles |
| **wikantik** | Custom (Tomcat 11 / JDK 21) | Runs the wiki application |
| **backup** | `postgres:17-alpine` | Automated database dumps and page file archives (production only) |

All three services share a private Docker network. The `wikantik` container connects to `db` by hostname. The `backup` container connects to `db` for `pg_dump` and mounts the same pages volume as `wikantik` to archive page files. Only port 8080 is exposed to the host.

### What lives where

| Data | Container path | Docker volume | Can rebuild? | Backed up? |
|------|---------------|---------------|-------------|------------|
| Wiki pages (.md + .properties) | `/var/wikantik/pages` | `wikantik-pages` | **NO** | **YES** |
| File attachments | `/var/wikantik/pages` | `wikantik-pages` | **NO** | **YES** |
| PostgreSQL data | `/var/lib/postgresql/data` | `pgdata` | **NO** | **YES** |
| Lucene search index | `/var/wikantik/work` | `wikantik-work` | YES (auto-rebuilds on startup) | No |
| Reference manager cache | `/var/wikantik/work` | `wikantik-work` | YES (auto-rebuilds on startup) | No |
| Application logs | `/var/wikantik/logs` | `wikantik-logs` | N/A | No |

The three items that cannot be rebuilt (pages, attachments, database) are all covered by the automated backup system.

## File Layout

```
docker/
  config/
    server.xml              Tomcat HTTP connector, Cloudflare RemoteIpValve
    catalina.properties     Tomcat classpath and security settings
    log4j2-docker.xml       Console + rolling file logging for Docker
  db/
    001-init.sql            Creates tables and seeds admin user (first startup only)
  backup/
    backup.sh               Runs pg_dump + tar with tiered retention
    restore.sh              Guided restore with checksum verification
    crontab                 Schedules daily/weekly/monthly backups
  entrypoint.sh             Generates config files from environment variables
docker-compose.yml          Base services: db + wikantik
docker-compose.dev.yml      Dev overrides: bind mounts, debug port, no backup
docker-compose.prod.yml     Prod overrides: resource limits, backup service
Dockerfile                  Production: multi-stage Maven build inside container
Dockerfile.dev              Dev: Tomcat only, WAR bind-mounted from host
.env.example                Template for all environment variables
.dockerignore               Keeps build context small
```

## Environment Variables

All configuration is driven by environment variables in a `.env` file. Copy the template to get started:

```bash
cp .env.example .env
```

Then edit `.env` with your values:

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_DB` | `wikantik` | Database name |
| `POSTGRES_USER` | `wikantik` | Database user |
| `POSTGRES_PASSWORD` | `CHANGEME` | **Change this!** Database password |
| `WIKANTIK_BASE_URL` | `http://localhost:8080/` | Public URL of the wiki |
| `WIKANTIK_PAGE_DIR` | `/var/wikantik/pages` | Page storage path inside container |
| `WIKANTIK_WORK_DIR` | `/var/wikantik/work` | Work directory (Lucene index, caches) |
| `WIKANTIK_ATTACHMENT_DIR` | `/var/wikantik/pages` | Attachment storage path |
| `MCP_ACCESS_KEYS` | (empty) | Comma-separated Bearer tokens for MCP API |
| `MCP_RATE_LIMIT_GLOBAL` | `100` | MCP requests/second (all clients) |
| `MCP_RATE_LIMIT_PER_CLIENT` | `10` | MCP requests/second (per client) |
| `MAIL_SMTP_HOST` | (empty) | SMTP server for email notifications |
| `MAIL_SMTP_PORT` | `587` | SMTP port |
| `MAIL_SMTP_ACCOUNT` | (empty) | SMTP username |
| `MAIL_SMTP_PASSWORD` | (empty) | SMTP password |
| `MAIL_FROM` | (empty) | From address for emails |
| `BACKUP_RETENTION_DAYS` | `30` | Days to keep daily backups |
| `BACKUP_DIR` | `./backups` | Host path for backup files |

**Important:** The `.env` file contains secrets (database password, SMTP credentials, MCP keys). It is excluded from Git by `.gitignore`. Keep a copy of this file somewhere safe outside the repository.

## How the Entrypoint Works

The `docker/entrypoint.sh` script runs every time the wikantik container starts. It generates three configuration files from environment variables:

1. **`wikantik-custom.properties`** — Wiki settings: base URL, page directory, PostgreSQL JDBC database names, SMTP config, column mappings
2. **`ROOT.xml`** — Tomcat context with two JNDI DataSources (`jdbc/UserDatabase` and `jdbc/GroupDatabase`) pointing to the PostgreSQL container
3. **`wikantik-mcp.properties`** — MCP server rate limits and access keys

This means you never edit config files inside the container. Change an environment variable, restart the container, and the new config takes effect.

## Production Deployment

### First-time setup

```bash
# 1. Create your .env file
cp .env.example .env
# Edit .env — at minimum, change POSTGRES_PASSWORD and WIKANTIK_BASE_URL

# 2. Create the backups directory
mkdir -p backups

# 3. Build and start everything
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

This will:
- Pull the `postgres:17-alpine` image
- Build the Wikantik image (multi-stage Maven build, takes a few minutes the first time)
- Start PostgreSQL and run `docker/db/001-init.sql` to create tables and a default admin user
- Start Wikantik, which connects to PostgreSQL and begins serving pages
- Start the backup container with cron scheduling

### Verifying it works

```bash
# Check all three services are running and healthy
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# Check the wiki responds
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/wiki/Main
# Should print: 200

# Check PostgreSQL has the admin user
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec db psql -U wikantik -d wikantik -c "SELECT login_name FROM users;"

# Check the MCP endpoint
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/mcp
# Should print: 400 (expected — MCP uses Streamable HTTP, not plain GET)

# View wikantik startup logs
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs wikantik
```

### Default admin login

The init script creates an admin user. Log in at `http://your-host:8080/Login.jsp`:

- **Username:** `admin`
- **Password:** `admin`

**Change this password immediately** after first login via the user preferences page.

### Deploying updates

When you pull new code and want to deploy:

```bash
# Rebuild only the wikantik image and restart it
# PostgreSQL and backup keep running — no data loss
docker compose -f docker-compose.yml -f docker-compose.prod.yml build wikantik
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik
```

Downtime is roughly 10-20 seconds while the container restarts. If you have Cloudflare in front, it serves cached content during the gap.

### Stopping everything

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml down
```

This stops all containers but **preserves all Docker volumes** (database, pages, logs). Your data is safe. To start again:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

**Warning:** `docker compose down -v` deletes all volumes and destroys all data. Never use `-v` unless you intentionally want to start from scratch.

## Backup System

The backup system is the most important part of the production deployment. It protects against data loss from disk failure, accidental deletion, software bugs, or corrupted content.

### What gets backed up

Every backup captures **two things**:

1. **PostgreSQL database** — `pg_dump` creates a complete SQL dump of all tables (users, roles, groups, group members). This file can restore the database to any PostgreSQL 15+ server.

2. **Wiki page files** — `tar` archives the entire `/var/wikantik/pages` directory, which contains:
   - `.md` files (the actual article content in Markdown)
   - `.properties` files (page metadata: author, change notes, timestamps)
   - Attachment files (anything uploaded to wiki pages)

Both are essential. Without the database, you lose all user accounts and group memberships. Without the page files, you lose all wiki content. The backup system captures both in every run.

### Backup schedule and retention

The cron schedule runs three tiers of backups:

| Tier | Schedule | Retention | Purpose |
|------|----------|-----------|---------|
| **Daily** | 2:00 AM every day | 30 days (configurable) | Recover from recent mistakes |
| **Weekly** | 3:00 AM every Sunday | 12 weeks | Recover from issues noticed late |
| **Monthly** | 4:00 AM on the 1st | 12 months | Long-term recovery point |

Each backup creates a directory like `backups/daily/2026-03-21/` containing:

```
backups/
  daily/
    2026-03-21/
      db.sql              PostgreSQL dump (all users, groups, roles)
      pages.tar.gz        All wiki pages, properties, and attachments
      checksums.sha256    SHA-256 hashes for integrity verification
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
```

The output shows exactly what was captured:

```
================================================================
[Fri Mar 21 02:00:01 UTC 2026] Starting daily backup
================================================================
Dumping PostgreSQL database wikantik...
  db.sql: 4523 bytes
Archiving wiki pages...
  pages.tar.gz: 189234 bytes (47 .md files)
  checksums.sha256 written

Backup written to /backups/daily/2026-03-21
total 192K
-rw-r--r--  1 root root 4.5K Mar 21 02:00 db.sql
-rw-r--r--  1 root root 185K Mar 21 02:00 pages.tar.gz
-rw-r--r--  1 root root  142 Mar 21 02:00 checksums.sha256
================================================================
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

By default, backups go to `./backups/` relative to the docker-compose files. To use a different location (like an external drive or NFS mount), set `BACKUP_DIR` in your `.env`:

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
2. Drop and recreate the PostgreSQL tables from the backup dump
3. Clear the current pages directory and extract the backup archive
4. Report how many users and pages were restored

After starting the wiki, the Lucene search index rebuilds automatically from the restored page files. This takes 30-60 seconds depending on the number of pages.

### Restoring only the database (keeping current pages)

If you only need to restore user accounts without touching page content:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik

docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup sh -c "
    psql -h db -U \${POSTGRES_USER} -d \${POSTGRES_DB} \
      -c 'DROP TABLE IF EXISTS group_members, groups, roles, users CASCADE;'
    psql -h db -U \${POSTGRES_USER} -d \${POSTGRES_DB} \
      < /backups/daily/2026-03-21/db.sql
  "

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

If the original server is gone and you are starting from a new machine with only the backup files:

```bash
# 1. Clone the repository
git clone https://github.com/your-repo/wikantik.git
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
cp /path/to/your/backup/db.sql backups/daily/2026-03-21/
cp /path/to/your/backup/pages.tar.gz backups/daily/2026-03-21/
cp /path/to/your/backup/checksums.sha256 backups/daily/2026-03-21/

# 5. Build and start wikantik (this creates the pages volume)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build wikantik

# 6. Start the backup container
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d backup

# 7. Run the restore (replaces init data with your real data)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/restore.sh /backups/daily/2026-03-21

# 8. Restart wikantik to pick up the restored content
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart wikantik
```

## Migrating from Bare-Metal Tomcat

If you are currently running Wikantik on a bare-metal Tomcat with a local PostgreSQL database, follow these steps to migrate to containers.

### Before you start

Make a safety backup of your current system:

```bash
# Dump the existing database
pg_dump -U jspwiki -d jspwiki --no-owner --no-privileges > pre-migration.sql

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
# Set POSTGRES_PASSWORD, WIKANTIK_BASE_URL, SMTP settings, MCP keys, etc.

# 3. Start only PostgreSQL
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d db
# Wait for healthy...

# 4. Replace the auto-created tables with your real data
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec -T db psql -U wikantik -d wikantik < pre-migration.sql

# 5. Build and start wikantik
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build wikantik

# 6. Copy your existing pages into the Docker volume
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
# Build the WAR on your host (uses your local Maven cache — fast)
mvn clean install -Dmaven.test.skip -T 1C

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
- **MCP has no access keys** (open for local testing)
- **No backup service** runs

## Cloudflare Integration

The Tomcat `server.xml` includes two settings for running behind Cloudflare:

1. **`RemoteIpValve`** — Extracts the real client IP from the `CF-Connecting-IP` header. Without this, all requests appear to come from Cloudflare's IPs.

2. **`AccessLogValve`** — Logs the `CF-IPCountry` header for geographic insight.

Cloudflare terminates TLS, so the container only listens on HTTP port 8080. There is no HTTPS connector in the container.

If you are using `WIKANTIK_BASE_URL=https://wiki.example.com/`, the wiki generates HTTPS URLs for links, sitemaps, and canonical tags, even though the container itself receives HTTP. This is the correct configuration for Cloudflare proxying.

## Troubleshooting

### Container won't start

```bash
# Check logs for errors
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs wikantik

# Common issues:
# - "Connection refused" to db → PostgreSQL isn't healthy yet, wikantik will retry
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

If the wiki starts but pages are missing, the pages volume may be empty:

```bash
# Check how many pages are in the volume
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec wikantik find /var/wikantik/pages -name '*.md' | wc -l
```

If zero, the volume was created empty. Restore from backup or copy pages in (see migration steps).

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
mvn clean install -Dmaven.test.skip -T 1C
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```
