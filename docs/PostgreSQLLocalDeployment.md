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
`/Wikantik/` prefix. The React SPA lives at `/`, the Page Graph viewer
at `/page-graph`, the Knowledge Graph viewer at `/knowledge-graph`, and
admin tools under `/admin/*`.

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

For a routine "edit code, see it running" iteration:

```bash
# 1. Build (also builds the React frontend via npm)
mvn clean install -Dmaven.test.skip -T 1C

# 2. Fast redeploy: shutdown + rotate catalina.out + swap WAR + startup.
#    Skips template re-materialisation, secrets validation, and DB
#    migrations — use bin/deploy-local.sh instead when those need to run
#    (first-time setup, Tomcat upgrade, secrets rotation, new V*.sql).
bin/redeploy.sh

# 3. Browse to the wiki at http://localhost:8080/.
#    Default login: admin / admin123.
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

This creates the schema currently described by V001 through V025:

- `users`, `roles`, `groups`, `group_members` (V002)
- `policy_grants` for database-backed authorization (V003)
- `kg_nodes`, `kg_edges`, `kg_proposals`, `kg_rejections` + the `vector`
  extension (V004; the legacy `kg_embeddings`/`kg_content_embeddings`
  tables were dropped in V019)
- `hub_centroids`, `hub_proposals` (V005)
- `hub_discovery_proposals` (V006, V007)
- `kg_content_chunks` (V008), `content_chunk_embeddings` (V009),
  `chunk_entity_mentions` (V011) — the unified Ollama-backed embedding stack
- `api_keys` (V010); `page_canonical_ids`, `page_slug_history` (V013;
  the typed `page_relations` table was dropped in V023 when typed
  relations were retired); verification + runbook tables (V014);
  retrieval quality (V016, V017); KG inclusion policy (V018)
- `kg_proposals.signature` for dedupe (V020); `kg_node_embeddings`
  (V021, V022); KG staged validation (V024); KG judge timeout tracking
  (V025)

A default `admin` user is seeded with password `admin123` by
`bin/db/seed-users.sql` (SHA-256 hashed). **Change this immediately
after first login** if you intend to keep the deployment online longer
than a smoke test.

If your `.pgpass` already authenticates the `postgres` superuser, you
can drop the `sudo -u postgres` prefix.

### Step 2: Build Wikantik

```bash
mvn clean install -Dmaven.test.skip -T 1C
```

`-T 1C` enables one-thread-per-core parallel builds. **Don't** combine
this with the `integration-tests` profile — IT modules share fixed
ports and require sequential execution.

### Step 3: Configure secrets

Copy `.env.example` to `.env` and set `POSTGRES_PASSWORD` to whatever you
used for `DB_APP_PASSWORD` in Step 1. `bin/deploy-local.sh` refuses to
run while the password is still the literal `CHANGEME`, and uses the
value to materialise `ROOT.xml` from the template — there is no manual
`ROOT.xml` edit step.

```bash
cp .env.example .env
$EDITOR .env  # set POSTGRES_PASSWORD (and any other defaults you want to override)
```

### Step 4: Deploy

```bash
bin/deploy-local.sh
```

The script will:

1. Verify `npm` is on PATH (needed for the React frontend build).
2. Verify `wikantik-war/target/Wikantik.war` exists.
3. Source `.env` and refuse to proceed if `POSTGRES_PASSWORD` is unset
   or still `CHANGEME`.
4. Download Tomcat 11.0.22 if `tomcat/tomcat-11/` is missing (or out of
   date; pass `--upgrade-tomcat` to perform an in-place upgrade that
   preserves managed configs and data).
5. Download the PostgreSQL JDBC driver if missing.
6. Render `Wikantik-context.xml.template` → `conf/Catalina/localhost/ROOT.xml`
   with `@@POSTGRES_*@@` tokens substituted from the env. The file is
   regenerated every deploy — your password lives in `.env`, not the
   context file.
7. Render `wikantik-custom-postgresql.properties.template` →
   `lib/wikantik-custom.properties` with `@@REPO_ROOT@@` substituted
   for your project root.
8. Copy `Tomcat-context.xml.template` → `conf/context.xml` (adds
   `<CookieProcessor sameSiteCookies="strict"/>` to the stock file).
9. Copy `Tomcat-server.xml.template` → `conf/server.xml` (adds the
   Cloudflare RemoteIpValve and the custom AccessLogValve).
10. Copy `log4j2-local.xml.template` → `lib/log4j2.xml`.
11. Stop Tomcat if running, rotate `catalina.out` to `.old`.
12. Replace `webapps/ROOT/` with the freshly built WAR.
13. Run `bin/db/migrate.sh` against the database named in the rendered
    `ROOT.xml` (idempotent — re-applies only pending migrations).
14. Seed dev user accounts via `bin/db/seed-users.sql` (also idempotent;
    skips upsert if a wiki_name conflict exists under a different
    login_name, so existing operator accounts are preserved).
15. Start Tomcat.

### Step 5: Verify

```bash
tail -f tomcat/tomcat-11/logs/catalina.out
```

1. Open <http://localhost:8080/> — the React SPA should load.
2. Log in as `admin` / `admin123`.
3. Visit <http://localhost:8080/page-graph> — Page Graph viewer.
4. Visit <http://localhost:8080/knowledge-graph> — Knowledge Graph viewer.
5. Confirm DB connectivity from the shell:

   ```bash
   psql -h localhost -U jspwiki -d wikantik -c "SELECT login_name FROM users;"
   ```
6. Confirm `/api/health` reports UP for engine + database + searchIndex:

   ```bash
   curl -s http://localhost:8080/api/health | jq
   ```

---

## Subsequent Deployments

For routine "edit code, see it running" iteration — `bin/redeploy.sh`
is the fast path:

```bash
mvn clean install -Dmaven.test.skip -T 1C
bin/redeploy.sh
```

It only does shutdown + rotate `catalina.out` + swap WAR + startup.
Use `bin/deploy-local.sh` instead when you need any of:

- a fresh schema migration applied (`migrate.sh` runs every deploy)
- secrets re-validation against `.env`
- a Tomcat upgrade (`bin/deploy-local.sh --upgrade-tomcat`)
- regenerated config templates (rare in a stable working tree)

```bash
mvn clean install -Dmaven.test.skip -T 1C
bin/deploy-local.sh
```

`deploy-local.sh` stops Tomcat, regenerates every templated config from
`.env`, redeploys the WAR, applies any new migrations, reseeds dev users
(idempotent), and starts Tomcat back up.

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
bin/deploy-local.sh   # regenerates both files from .env-templated values
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
