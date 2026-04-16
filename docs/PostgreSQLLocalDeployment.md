# PostgreSQL Local Deployment Guide

This guide walks through running Wikantik against a local PostgreSQL
database, deployed as the ROOT context of a Tomcat 11 instance for
manual testing and front-end development.

## Overview

What ends up where:

- **Tomcat 11** at `tomcat/tomcat-11/` (gitignored, downloaded on first
  run of `bin/deploy-local.sh`)
- **PostgreSQL** running locally with a `wikantik` database and a
  `jspwiki` application role
- **Operator scripts** under `bin/`:
  - `bin/deploy-local.sh` — bootstraps Tomcat, applies templates,
    deploys the WAR, runs migrations, seeds dev users
  - `bin/db/install-fresh.sh` — one-time DB + role + schema bootstrap
  - `bin/db/migrate.sh` — apply pending schema migrations (rerun every
    deploy)
  - `bin/db/create-migrate-user.sh` — provision the dedicated `migrate`
    role used in production (optional locally)
- **Configuration templates** in `wikantik-war/src/main/config/tomcat/`
  (git-tracked) are copied to your Tomcat instance the first time
  `bin/deploy-local.sh` runs and never overwritten afterwards. Your
  password edits survive subsequent deploys.

The wiki is served at the **root context** (`/`) — there is no
`/Wikantik/` prefix. The React SPA lives at `/`, the knowledge-graph
visualization at `/graph`, and admin tools under `/admin/*`.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js + npm | 18+ | The WAR build runs `npm install` + `vite build` automatically |
| PostgreSQL | 15+ | Locally, listening on `localhost:5432` |
| pgvector extension | 0.5+ | Required — the knowledge-graph and hub features use it |

### Installing pgvector

Wikantik's knowledge-graph and hub-membership features store dense
embeddings as `pgvector` columns. The extension must be installed on
the same server that runs your local PostgreSQL — `install-fresh.sh`
issues `CREATE EXTENSION vector` and that only succeeds when the
binaries are present.

The full per-platform install instructions live in the project
[`README.md`](../README.md#postgresql--pgvector). Short version for
Debian/Ubuntu with PostgreSQL 16:

```bash
sudo apt install -y postgresql-16-pgvector
sudo systemctl restart postgresql
```

Verify:

```bash
psql -h localhost -U postgres -c \
    "SELECT name, default_version FROM pg_available_extensions WHERE name='vector';"
```

You should see a `vector` row.

---

## Quick Start (after one-time setup)

```bash
# 1. Build (also builds the React frontend via npm)
mvn clean install -Dmaven.test.skip -T 1C

# 2. Deploy + apply pending migrations + seed dev users
bin/deploy-local.sh

# 3. Browse to the wiki — Tomcat is started automatically by deploy-local.sh
#    http://localhost:8080/
#    Default login: admin / admin123
```

---

## One-Time Setup

### Step 1: Create the database, role, and full schema

`install-fresh.sh` is idempotent — it creates the `wikantik` database,
the `jspwiki` application role, and applies every `V*.sql` migration
in `bin/db/migrations/`:

```bash
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' \
    bin/db/install-fresh.sh
```

This creates the schema currently described by V001 through V007:

- `users`, `roles`, `groups`, `group_members` (V002)
- `policy_grants` for database-backed authorization (V003)
- `kg_nodes`, `kg_edges`, `kg_proposals`, `kg_rejections`, `kg_embeddings`,
  `kg_content_embeddings` + the `vector` extension (V004)
- `hub_centroids`, `hub_proposals` (V005)
- `hub_discovery_proposals` (V006, V007)

A default `admin` user is seeded with password `admin` (SSHA hashed).
**Change this immediately after first login.**

If your `.pgpass` already authenticates the `postgres` superuser, you
can drop the `sudo -u postgres` prefix.

### Step 2: Build Wikantik

```bash
mvn clean install -Dmaven.test.skip -T 1C
```

`-T 1C` enables one-thread-per-core parallel builds. **Don't** combine
this with the `integration-tests` profile — IT modules share fixed
ports and require sequential execution.

### Step 3: Deploy

```bash
bin/deploy-local.sh
```

The script will:

1. Verify `npm` is on PATH (needed for the React frontend build).
2. Verify `wikantik-war/target/Wikantik.war` exists.
3. Download Tomcat 11 if `tomcat/tomcat-11/` is missing.
4. Download the PostgreSQL JDBC driver if missing.
5. Copy `Wikantik-context.xml.template` → `conf/Catalina/localhost/ROOT.xml`
   (only if the destination doesn't already exist — your password edits
   are preserved).
6. Copy `wikantik-custom-postgresql.properties.template` →
   `lib/wikantik-custom.properties` with `@@REPO_ROOT@@` substituted
   for your project root.
7. Copy `log4j2-local.xml.template` → `lib/log4j2.xml`.
8. Stop Tomcat if running, rotate `catalina.out` to `.old`.
9. Replace `webapps/ROOT/` with the freshly built WAR.
10. Run `bin/db/migrate.sh` against the database named in `ROOT.xml`
    (idempotent — re-applies only pending migrations).
11. Seed dev user accounts via `bin/db/seed-users.sql` (also idempotent).
12. Start Tomcat.

### Step 4: Set the database password in `ROOT.xml`

Edit the JNDI context file and replace the password placeholder:

```bash
nano tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml
```

The connection string and password appear inside `<Resource>` elements
(typically two — one for the application, one for the embedded Lucene
search index if configured). Match what you used for `DB_APP_PASSWORD`
in Step 1.

### Step 5: Restart Tomcat after the password edit

```bash
tomcat/tomcat-11/bin/shutdown.sh
tomcat/tomcat-11/bin/startup.sh

# Watch the log
tail -f tomcat/tomcat-11/logs/catalina.out
```

### Step 6: Verify

1. Open <http://localhost:8080/> — the React SPA should load.
2. Log in as `admin` / `admin123`.
3. Visit <http://localhost:8080/graph> — the knowledge-graph view.
4. Confirm DB connectivity from the shell:

   ```bash
   psql -h localhost -U jspwiki -d wikantik -c "SELECT login_name FROM users;"
   ```

---

## Subsequent Deployments

```bash
mvn clean install -Dmaven.test.skip -T 1C
bin/deploy-local.sh
```

`deploy-local.sh` stops Tomcat, redeploys the WAR, applies any new
migrations, reseeds dev users (idempotent), and starts Tomcat back up.
Your `ROOT.xml` and `wikantik-custom.properties` are untouched — only
the WAR and freshly-migrated schema change.

### Manual schema migration

If you only need to apply pending migrations without redeploying:

```bash
bin/db/migrate.sh           # apply pending
bin/db/migrate.sh --status  # list applied versions
```

The default `PGUSER` for `migrate.sh` is `migrate` — set
`PGUSER=postgres` (or another superuser) explicitly when running
locally if you haven't provisioned the dedicated `migrate` role:

```bash
PGUSER=postgres bin/db/migrate.sh --status
```

See [ProductionDBWorkflow.md](ProductionDBWorkflow.md) for the
end-state plan that introduces the dedicated `migrate` role and
extension-pre-install separation.

---

## Configuration files at a glance

### Git-tracked templates

`wikantik-war/src/main/config/tomcat/`:

| File | Purpose |
|------|---------|
| `Wikantik-context.xml.template` | JNDI DataSource configuration for PostgreSQL |
| `wikantik-custom-postgresql.properties.template` | Wikantik runtime settings (page provider, sitemap base URL, hub thresholds, CORS allow-list, etc.) |
| `log4j2-local.xml.template` | Local-dev logging config |

### Local files (gitignored)

`tomcat/tomcat-11/`:

| File | Purpose |
|------|---------|
| `lib/postgresql.jar` | PostgreSQL JDBC driver (auto-downloaded) |
| `lib/wikantik-custom.properties` | Customized Wikantik settings — edit here, not in the template |
| `lib/log4j2.xml` | Effective log config |
| `conf/Catalina/localhost/ROOT.xml` | JNDI context with the actual DB password |

### Test credentials (for automated/manual API testing)

The repo also expects a gitignored `test.properties` at the project
root with credentials for a `testbot` admin user — see the **Manual
Testing Credentials** section in `CLAUDE.md` for the exact format and
how to recreate the user after a database reset.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `Cannot create JDBC driver` | `tomcat/tomcat-11/lib/postgresql.jar` missing | Re-run `bin/deploy-local.sh` |
| `JNDI name not found` | `ROOT.xml` not in `conf/Catalina/localhost/` | Re-run `bin/deploy-local.sh` |
| `Password authentication failed` for `jspwiki` | Wrong DB password in `ROOT.xml` | Edit `ROOT.xml`, restart Tomcat |
| `Connection refused` to PostgreSQL | PostgreSQL not running | `sudo systemctl start postgresql` |
| `extension "vector" is not available` during install | pgvector package not installed | See [Installing pgvector](#installing-pgvector) |
| Login fails with correct password | Wrong password hash format in `users` table | Recreate the user with `CryptoUtil --hash` (see CLAUDE.md) |
| WAR file not found | `mvn clean install` not run yet | Build first |
| Migration fails midway | Idempotent retry usually safe — re-run `bin/db/migrate.sh` | Inspect the specific `V*.sql` file's error before retrying destructive changes |
| `404` at `http://localhost:8080/Wikantik/` | Stale URL — Wikantik now serves at `/` | Use `http://localhost:8080/` |

### Checking logs

```bash
# Tomcat
tail -f tomcat/tomcat-11/logs/catalina.out

# JDBC / JNDI specifically
grep -i "jdbc\|datasource\|jndi" tomcat/tomcat-11/logs/catalina.out

# Wikantik application logs
tail -f tomcat/tomcat-11/logs/wikantik/wikantik.log

# PostgreSQL (path varies by distro)
sudo tail -f /var/log/postgresql/postgresql-*-main.log
```

### Resetting local Tomcat configuration

```bash
rm tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml
rm tomcat/tomcat-11/lib/wikantik-custom.properties
bin/deploy-local.sh
# Re-edit ROOT.xml to set the password again
```

### Resetting the database

The migration system has no destructive "reset" command. Drop and
recreate the database, then run `install-fresh.sh` again:

```bash
sudo -u postgres psql -c 'DROP DATABASE wikantik;'
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' \
    bin/db/install-fresh.sh
```

---

## Relationship to the test suite

This local-deployment configuration has **zero impact** on the build
or test suite:

- Unit tests use `TestEngine` with their own in-memory configuration.
- Integration tests stand up their own per-module PostgreSQL +
  pgvector container via `io.fabric8:docker-maven-plugin` on port
  `55432`, and apply the same `bin/db/migrate.sh` against it. See
  [`wikantik-it-tests/README.md`](../wikantik-it-tests/README.md).
- The `tomcat/` directory is gitignored.
- `bin/deploy-local.sh` is operator-only — never invoked from Maven.

You can run `mvn clean install` and `mvn clean install -Pintegration-tests -fae`
without touching anything in `tomcat/`.

---

## Related documentation

- [README.md](../README.md) — project overview and pgvector install per platform
- [CLAUDE.md](../CLAUDE.md) — full development workflow, test credentials, schema-change conventions
- [Developing with PostgreSQL](DevelopingWithPostgresql.md) — JDBC configuration reference
- [Production DB Workflow (future state)](ProductionDBWorkflow.md) — split-role provisioning and baseline plan
- [`bin/db/migrations/README.md`](../bin/db/migrations/README.md) — naming, idempotence rules, how to add a migration
