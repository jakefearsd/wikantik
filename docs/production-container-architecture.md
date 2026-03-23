# Production Container Architecture for Wikantik

## Executive Summary

This document describes the ideal production deployment for Wikantik using Docker containers, with automated CI/CD, zero-downtime rollback, and automated backup/restore. The goal: push to main, tests run on your build machine, a new image ships to production, and if anything goes wrong, you roll back in seconds — or restore from backup in minutes.

---

## The Three Machines

```
┌──────────────┐     git push     ┌──────────────────┐  docker save|ssh  ┌─────────────────────┐
│  Dev Machine  │ ───────────────> │   Build Machine   │ ────────────────> │  Production Server  │
│  (your desk)  │                  │  (self-hosted GHA) │                   │  (wiki.jakefear.com) │
└──────────────┘                  └──────────────────┘                   └─────────────────────┘
                                   Runs tests                             Runs containers
                                   Builds Docker image                    Serves wiki
                                   Transfers image via SSH                Runs backups
```

---

## Production Server: Container Layout

### Services

```yaml
services:
  db:           PostgreSQL 17 — users, groups, roles
  wikantik:     Tomcat 11/JDK 21 — the wiki application
  backup:       PostgreSQL 17 Alpine + cron — automated backups
```

### Volumes (persistent data)

```
pgdata          PostgreSQL data files (users, groups, roles)
wikantik-pages  Wiki page files (.md + .properties + attachments)
wikantik-work   Lucene index + reference manager cache (auto-rebuilt)
wikantik-logs   Application logs
```

### What's critical vs what rebuilds automatically

| Data | Volume | Critical? | Backed up? | Rebuilds? |
|------|--------|-----------|------------|-----------|
| Wiki pages (.md, .properties) | wikantik-pages | **YES** | **YES** | No — this IS your content |
| File attachments | wikantik-pages | **YES** | **YES** | No — uploaded files |
| PostgreSQL (users, groups) | pgdata | **YES** | **YES** | No — user accounts |
| Lucene search index | wikantik-work | No | No | Yes — rebuilt on startup |
| Reference manager cache | wikantik-work | No | No | Yes — rebuilt on startup |
| Application logs | wikantik-logs | No | No | Ephemeral |

---

## CI/CD Pipeline: Push-on-Green

### Flow

```
1. You push to main
2. GitHub sends job to self-hosted runner (build machine)
3. Runner:
   a. Checks out code
   b. Runs unit tests (mvn clean test -T 1C -B)        ~90 seconds
   c. If tests fail → STOP, notify, nothing deployed
   d. Builds Docker image (docker build -t wikantik)    ~60 seconds (cached)
   e. Tags image with commit SHA for rollback:
      docker tag wikantik:latest wikantik:sha-abc1234
   f. Transfers to prod: docker save | gzip | ssh | gunzip | docker load
   g. On prod: git pull (updates compose files + config)
   h. On prod: docker compose up -d --no-deps wikantik
   i. Waits 30s, health check: curl http://localhost:8080/wiki/Main
   j. If health check fails → auto-rollback (see below)
   k. Prunes old images on build machine
4. Total time: ~3-5 minutes, fully automated
```

### Auto-Rollback on Failed Health Check

The CI/CD workflow should keep track of the previous image tag. If the health check at step (i) fails:

```bash
# In the CI/CD workflow:
- name: Deploy with rollback
  run: |
    ssh prod "
      # Save the current running image tag before deploying
      PREV_TAG=\$(docker inspect wikantik --format='{{.Config.Image}}' 2>/dev/null || echo 'none')

      # Load and start new image
      cd $DEPLOY_DIR
      docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik

      # Wait for startup
      sleep 30

      # Health check
      if ! curl -fsS http://localhost:8080/wiki/Main > /dev/null 2>&1; then
        echo 'HEALTH CHECK FAILED — rolling back'
        docker tag \$PREV_TAG wikantik:latest 2>/dev/null || true
        docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik
        exit 1
      fi

      echo 'Deployment verified'
    "
```

### Manual Rollback

Every image is tagged with its commit SHA. To roll back manually:

```bash
# On the build machine, list recent images:
docker images wikantik --format '{{.Tag}} {{.CreatedAt}}' | head -10

# Redeploy a specific version:
docker save wikantik:sha-abc1234 | gzip | \
  ssh -i ~/.ssh/wikantik-deploy user@prod 'gunzip | docker load'
ssh -i ~/.ssh/wikantik-deploy user@prod "
  docker tag wikantik:sha-abc1234 wikantik:latest
  cd /path/to/repo && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik
"
```

Or simply revert the commit and push — CI/CD redeploys the fix.

---

## Backup System: Three-Tier Retention

### What Gets Backed Up

Every backup captures TWO things:

1. **PostgreSQL dump** — `pg_dump` creates a complete SQL dump of all tables (users, roles, groups, group members). Restores to any PostgreSQL 15+ server.

2. **Wiki page files** — `tar` archives the entire `/var/wikantik/pages` directory:
   - `.md` files (article content)
   - `.properties` files (page metadata: author, timestamps, change notes)
   - Attachment files (anything uploaded)

### Backup Schedule

| Tier | When | Retention | Purpose |
|------|------|-----------|---------|
| Daily | 2:00 AM | 30 days | Recover from recent mistakes |
| Weekly | 3:00 AM Sunday | 12 weeks | Recover from issues noticed late |
| Monthly | 4:00 AM 1st | 12 months | Long-term recovery point |

### Backup Directory Structure

```
backups/
  daily/
    2026-03-23/
      db.sql              PostgreSQL dump
      pages.tar.gz        All wiki pages + attachments
      checksums.sha256    Integrity verification
    2026-03-22/
      ...
  weekly/
    2026-03-16/
      ...
  monthly/
    2026-03-01/
      ...
```

### Off-Site Backup (Recommended)

The backup directory should be on a separate filesystem or replicated off-server. Options:

1. **rsync to another machine** — Add a cron job on the prod server:
   ```bash
   0 5 * * * rsync -az /path/to/backups/ backup-server:/wikantik-backups/
   ```

2. **S3-compatible storage** — Mount an S3 bucket or use `aws s3 sync`:
   ```bash
   0 5 * * * aws s3 sync /path/to/backups/ s3://wikantik-backups/ --delete
   ```

3. **NFS/external drive** — Set `BACKUP_DIR=/mnt/external/wikantik-backups` in `.env`

The key principle: backups on the same server that hosts the wiki only protect against software failures, not hardware failures. At least one copy should be elsewhere.

---

## Restore Procedures

### Scenario 1: Bad Deployment (application bug)

**Symptom:** Wiki shows errors, pages don't render, MCP broken
**Cause:** Code change introduced a bug
**Recovery time:** ~30 seconds

```bash
# Option A: CI/CD auto-rollback already handled it (see above)

# Option B: Manual rollback to previous image
ssh prod "
  docker tag wikantik:sha-PREVIOUS wikantik:latest
  cd /path/to/repo
  docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps wikantik
"
```

No data is affected. Just the application image.

### Scenario 2: Corrupted Content (bad MCP write, accidental deletion)

**Symptom:** Pages have wrong content, pages missing
**Cause:** MCP client wrote garbage, user deleted pages
**Recovery time:** ~2 minutes

```bash
# Stop the wiki (prevents further writes during restore)
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik

# Restore pages only (keep current user database)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup sh -c "
    rm -rf /var/wikantik/pages/*
    tar -xzf /backups/daily/2026-03-23/pages.tar.gz -C /var/wikantik/pages
  "

# Restart (Lucene index auto-rebuilds)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
```

### Scenario 3: Database Corruption (user accounts lost)

**Symptom:** Can't log in, users missing, groups gone
**Cause:** Database bug, bad migration
**Recovery time:** ~2 minutes

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik

# Restore database only (keep current pages)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup sh -c "
    psql -h db -U \${POSTGRES_USER} -d \${POSTGRES_DB} \
      -c 'DROP TABLE IF EXISTS group_members, groups, roles, users CASCADE;'
    psql -h db -U \${POSTGRES_USER} -d \${POSTGRES_DB} \
      < /backups/daily/2026-03-23/db.sql
  "

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
```

### Scenario 4: Full Disaster Recovery (server gone)

**Symptom:** Production server is dead, everything lost
**Cause:** Hardware failure, datacenter issue
**Recovery time:** ~30 minutes (new server setup) + ~5 minutes (restore)

```bash
# On new server:

# 1. Install Docker
sudo apt install docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 2. Clone repo
git clone https://github.com/jakefearsd/wikantik.git ~/wikantik
cd ~/wikantik

# 3. Create .env with production secrets
cp .env.example .env
vim .env  # set POSTGRES_PASSWORD, WIKANTIK_BASE_URL, SMTP, MCP keys

# 4. Copy backup files from off-site storage
mkdir -p backups/daily/2026-03-23
scp backup-server:/wikantik-backups/daily/2026-03-23/* backups/daily/2026-03-23/

# 5. Start PostgreSQL (init script creates empty tables)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d db
# Wait for healthy...

# 6. Build and start wikantik (first time — uses Dockerfile build fallback)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build wikantik

# 7. Start backup container
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d backup

# 8. Restore from backup (replaces init data with real data)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup /usr/local/bin/restore.sh /backups/daily/2026-03-23

# 9. Restart wikantik to pick up restored content
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart wikantik

# 10. Update DNS to point to new server
# 11. Update CI/CD secrets (DEPLOY_HOST) to point to new server
# 12. Verify: browse wiki, test login, test MCP
```

### Scenario 5: Roll Back a Specific Page (surgical restore)

**Symptom:** One page was corrupted, rest is fine
**Cause:** Bad MCP edit on a single page
**Recovery time:** ~1 minute

```bash
# Extract just one page from the backup
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec backup sh -c "
    cd /tmp
    tar -xzf /backups/daily/2026-03-23/pages.tar.gz ./PageName.md ./PageName.properties
    cp /tmp/PageName.md /var/wikantik/pages/
    cp /tmp/PageName.properties /var/wikantik/pages/
    rm /tmp/PageName.md /tmp/PageName.properties
  "

# No restart needed — FileSystemProvider detects the change automatically
# (PageDirectoryWatcher picks it up within seconds)
```

---

## Monitoring and Verification

### Health Checks (built into Docker Compose)

```yaml
wikantik:
  healthcheck:
    test: ["CMD", "curl", "-fsS", "http://localhost:8080/wiki/Main"]
    interval: 30s
    timeout: 10s
    start_period: 90s
    retries: 5

db:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U wikantik -d wikantik"]
    interval: 10s
    timeout: 5s
    retries: 5
```

### Manual Verification Commands

```bash
# Check all services are healthy
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# Check wiki responds
curl -fsS http://localhost:8080/wiki/Main > /dev/null && echo "Wiki OK"

# Check MCP endpoint
curl -fsS http://localhost:8080/mcp > /dev/null 2>&1; echo "MCP: $?"  # 400 = OK (Streamable HTTP)

# Check database
docker compose exec db psql -U wikantik -d wikantik -c "SELECT COUNT(*) FROM users;"

# Check backup container is running cron
docker compose exec backup crontab -l

# Check latest backup exists and is non-empty
ls -lh backups/daily/$(date +%Y-%m-%d)/

# Verify backup integrity
docker compose exec backup sh -c "cd /backups/daily/$(date +%Y-%m-%d) && sha256sum -c checksums.sha256"

# Check disk usage
docker system df
du -sh backups/
```

### Backup Verification (run monthly)

Don't just check backups exist — verify they restore correctly:

```bash
# 1. Trigger a manual backup
docker compose exec backup /usr/local/bin/backup.sh daily

# 2. Start a temporary PostgreSQL to test the SQL dump
docker run --rm -d --name pg-test -e POSTGRES_PASSWORD=test postgres:17-alpine
sleep 5
docker exec -i pg-test psql -U postgres -c "CREATE DATABASE testdb;"
docker cp backups/daily/$(date +%Y-%m-%d)/db.sql pg-test:/tmp/
docker exec -i pg-test psql -U postgres -d testdb -f /tmp/db.sql
docker exec -i pg-test psql -U postgres -d testdb -c "SELECT COUNT(*) FROM users;"
docker stop pg-test

# 3. Test the pages archive
mkdir /tmp/page-test
tar -xzf backups/daily/$(date +%Y-%m-%d)/pages.tar.gz -C /tmp/page-test
ls /tmp/page-test/*.md | wc -l  # Should match your page count
rm -rf /tmp/page-test
```

---

## Security Considerations

### Network Exposure

```
Internet → Cloudflare (TLS termination) → Port 8080 (HTTP only)

PostgreSQL: NOT exposed to internet (no host port mapping in prod compose)
Backup: NOT exposed (internal only)
MCP: Protected by Bearer token (MCP_ACCESS_KEYS in .env)
```

### Secrets Management

All secrets live in `.env` on the production server:
- `POSTGRES_PASSWORD` — database access
- `MCP_ACCESS_KEYS` — MCP API authentication
- `MAIL_SMTP_PASSWORD` — email sending

`.env` is in `.gitignore` — never committed. Back it up separately (store in a password manager or encrypted vault).

### Image Provenance

Images are built on your build machine and transferred directly to prod via SSH. No registry, no third-party image storage. The image chain is: your code → your build machine → your prod server. No supply chain risk from external registries.

---

## Ideal Daily Operation

**Normal day:**
```
You write code → push to main → CI/CD runs tests → builds image →
ships to prod → health check passes → done
```

**Something breaks:**
```
CI/CD health check fails → auto-rollback to previous image →
you see red in GitHub Actions → fix the bug → push again
```

**Content problem:**
```
Notice corrupted page → run restore.sh for that day's backup →
page restored in 1 minute → no downtime
```

**Disaster:**
```
Server dies → spin up new server → clone repo + copy backup →
docker compose up → restore.sh → DNS update → back online in 30 min
```

---

## What Exists Today vs What's Needed

| Component | Status | What's Left |
|-----------|--------|-------------|
| Dockerfile (multi-stage build) | **Done** | — |
| docker-compose.yml (base) | **Done** | — |
| docker-compose.prod.yml (prod overrides) | **Done** | — |
| docker/entrypoint.sh (env → config) | **Done** | — |
| docker/db/001-init.sql | **Done** | — |
| docker/backup/backup.sh | **Done** | — |
| docker/backup/restore.sh | **Done** | — |
| docker/backup/crontab | **Done** | — |
| .env.example | **Done** | — |
| CI/CD workflow (ci-cd.yml) | **Done** | Auto-rollback enhancement |
| Self-hosted runner | **In progress** | Install on build machine |
| SSH deploy key | **Not done** | Generate + authorize |
| GitHub secrets | **Not done** | DEPLOY_HOST, DEPLOY_USER, DEPLOY_DIR |
| Production .env | **Not done** | Create from .env.example |
| Off-site backup | **Not done** | Set up rsync/S3 |
| Backup verification cron | **Not done** | Monthly test-restore |
| Auto-rollback in CI/CD | **Not done** | Enhance workflow |

---

## Summary

The system is designed around three principles:

1. **Push-on-green** — Every push to main that passes tests automatically deploys. No manual steps, no SSH, no remembering commands.

2. **Instant rollback** — Every image is tagged with its commit SHA. Rolling back is one command, takes 15 seconds. CI/CD auto-rolls back on health check failure.

3. **Automated restore** — Backups run on three tiers (daily/weekly/monthly). Restoring content, database, or both is a single command. Full disaster recovery from backup takes 30 minutes on a fresh server.

The infrastructure code (Dockerfile, compose files, backup scripts, CI/CD workflow) is all in the repo and version-controlled. The only things NOT in the repo are secrets (`.env`) and the backup data itself.
