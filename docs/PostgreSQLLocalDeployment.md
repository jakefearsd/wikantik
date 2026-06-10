# PostgreSQL Local Deployment Guide

This is the **canonical bare-metal deployment guide** — running Wikantik against
a local PostgreSQL, deployed as the ROOT context of a Tomcat 11 instance, for
development, manual testing, front-end work, and single-host installs. (The
container path — recommended for production — is
[DockerDeployment.md](DockerDeployment.md); the operator handbook overview is
[WikantikOperations.md §1.3](WikantikOperations.md).)

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
    role and grant it the privileges migrations need. **Not optional** if
    you deploy with `bin/deploy-local.sh` or `bin/redeploy.sh`: both run
    `migrate.sh` as `PGUSER=migrate`, so an unprovisioned/under-privileged
    `migrate` role makes the deploy abort mid-migration. See
    [The `migrate` role](#the-migrate-role-required-for-redeploysh-recommended-for-deploy-localsh).
- **Configuration templates** in `wikantik-war/src/main/config/tomcat/`
  (git-tracked) are materialised into your Tomcat instance the first time
  `bin/deploy-local.sh` runs and are **write-once — never overwritten
  afterwards** (`server.xml`/`context.xml` are overwritten only while still
  stock). Your password edits in `ROOT.xml` survive subsequent deploys — but so
  does everything else, so **edits to the templates (e.g. the performance knobs)
  do not reach an existing install**; apply them to the deployed file by hand or
  delete it and re-deploy. See [Performance & concurrency tuning](#performance--concurrency-tuning-bare-metal).

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

# 2. Fast redeploy: shutdown + rotate catalina.out + swap WAR + run
#    pending migrations + startup.
#    Skips template re-materialisation and secrets validation only —
#    use bin/deploy-local.sh instead for those
#    (first-time setup, Tomcat upgrade, secrets rotation, new config templates).
bin/redeploy.sh

# 3. Browse to the wiki at http://localhost:8080/.
#    First login: admin / admin123 — you will be required to choose a new password.
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

This applies every `V*.sql` in `bin/db/migrations/` (currently through the V03x
series) — the full relational schema: users/roles/groups, `policy_grants`, the
Knowledge Graph tables (`kg_*`) and the `vector` extension, the Ollama-backed
embedding stack (`kg_content_chunks`, `content_chunk_embeddings`,
`chunk_entity_mentions`), hub tables, `api_keys`, the Page Graph structural index
(`page_canonical_ids`, `page_slug_history`), verification/runbook, retrieval
quality, KG inclusion policy, and the pgvector HNSW index. The migration ledger
lives in `schema_migrations`. For the authoritative, always-current list, read
[`bin/db/migrations/README.md`](../bin/db/migrations/README.md) and the numbered
files themselves — this guide does not enumerate versions to avoid going stale.

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
   with `@@POSTGRES_*@@` tokens substituted from the env — **only if `ROOT.xml`
   does not already exist** (write-once). On an existing install the file is left
   untouched, so a changed password (or a changed DBCP pool size in the template)
   requires editing `ROOT.xml` directly or deleting it and re-deploying.
7. Render `wikantik-custom-postgresql.properties.template` →
   `lib/wikantik-custom.properties` with `@@REPO_ROOT@@` substituted
   for your project root.
8. Copy `Tomcat-context.xml.template` → `conf/context.xml` (adds
   `<CookieProcessor sameSiteCookies="lax"/>` to the stock file —
   `lax` is required for SSO; `strict` causes random logouts because the IdP's
   cross-site redirect back to `/sso/callback` withholds a `strict` cookie).
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

It does shutdown + rotate `catalina.out` + swap WAR + **run pending migrations** + startup.
Use `bin/deploy-local.sh` instead when you need any of:

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

### The `migrate` role (required for `redeploy.sh`; recommended for `deploy-local.sh`)

`bin/redeploy.sh` invokes `migrate.sh` with **`PGUSER=migrate`** (hardcoded), so
a properly provisioned `migrate` role is required for fast redeployments or the
deploy aborts mid-migration and Tomcat never starts.

`bin/deploy-local.sh` is more lenient: it calls `migrate.sh` with no explicit
`PGUSER` on the first attempt (whatever the environment provides — typically the
`migrate` role if `.pgpass` is configured), and falls back to `PGUSER=postgres`
if that fails. This means `deploy-local.sh` usually works even without a
provisioned `migrate` role, but provisioning it is still recommended for a
consistent setup. The role is created and granted by `bin/db/create-migrate-user.sh`, which
`install-fresh.sh` runs automatically **when `DB_MIGRATE_PASSWORD` is set**:

```bash
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' DB_MIGRATE_PASSWORD='AlsoChangeMe!' \
    bin/db/install-fresh.sh
```

`create-migrate-user.sh` grants `migrate` everything the migration set needs:

- `LOGIN`, `CREATE`/`USAGE` on `public`, membership in the app role;
- **`CREATEROLE`** and **`pg_monitor WITH ADMIN OPTION`** — required by
  `V031__monitoring_role`, which creates the `wikantik_exporter` role and grants
  it `pg_monitor`;
- **ownership of existing public-schema tables** (transferred from `postgres`/the
  app role) — so later `ALTER TABLE` migrations succeed and `migrate` can write
  the `schema_migrations` ledger.

If you bootstrapped the DB **without** `DB_MIGRATE_PASSWORD`, run the provisioning
step after the fact (idempotent):

```bash
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_MIGRATE_PASSWORD='AlsoChangeMe!' bin/db/create-migrate-user.sh
```

Symptoms of a half-provisioned `migrate` role (and the privilege each needs) are
in [Troubleshooting](#troubleshooting).

---

## Performance & concurrency tuning (bare-metal)

The throughput/overload knobs and their values are documented once, with
reasoning, in [WikantikOperations.md §1.5](WikantikOperations.md#15-performance--concurrency-tuning).
On bare-metal they come from the git-tracked templates and land in these deployed
files:

| Knob | Deployed file | Template |
|------|---------------|----------|
| Tomcat `maxThreads=400` / `acceptCount=200` | `conf/server.xml` | `Tomcat-server.xml.template` |
| DBCP `maxTotal=90` / `maxWaitMillis=5000` / `maxIdle=30` | `conf/Catalina/localhost/ROOT.xml` | `Wikantik-context.xml.template` |
| Backpressure cap `WIKANTIK_MAX_INFLIGHT_REQUESTS` (default 390) | `bin/setenv.sh` (env/sysprop) | `setenv.sh.template` |
| Dense backend + HNSW (`wikantik.search.dense.*`) | `lib/wikantik-custom.properties` | `wikantik-custom-postgresql.properties.template` |

Two things to keep in mind:

- **The backpressure cap must stay below `maxThreads`.** The default 390 is below
  the connector's 400 on purpose; with stock Tomcat (`maxThreads=200`) a 390 cap
  can never fire. The `BackpressureFilter` reads the value from a system property
  or environment variable only — there is no `wikantik.*` property for it —
  so `bin/setenv.sh` is where you set it.
- **Templated config is write-once** (see [Overview](#overview)). A long-lived
  install does **not** pick up template changes to these values. To apply an
  updated knob, edit the deployed file in place (then restart Tomcat), or delete
  the deployed file and re-run `bin/deploy-local.sh` to re-render it. Verify the
  cap is live and correctly sized after a restart:

  ```bash
  curl -s http://localhost:8080/metrics | grep backpressure_permits_max
  # wikantik_backpressure_permits_max 390.0   (and < the connector maxThreads)
  ```

## Configuration files at a glance

### Git-tracked templates

`wikantik-war/src/main/config/tomcat/`:

| File | Purpose |
|------|---------|
| `Wikantik-context.xml.template` | JNDI DataSource configuration for PostgreSQL |
| `wikantik-custom-postgresql.properties.template` | Wikantik runtime settings (page provider, sitemap base URL, hub thresholds, CORS allow-list, etc.) |
| `wikantik-mcp.properties.template` | MCP server config: rate limits, server name/title/version. Rendered to `lib/wikantik-mcp.properties` on first deploy. |
| `setenv.sh.template` | Tomcat launch environment: enables the JDK incubator Vector API for Lucene 10; also where `WIKANTIK_MAX_INFLIGHT_REQUESTS` is set (default 390) for the backpressure filter. Rendered to `bin/setenv.sh` (write-once). |
| `log4j2-local.xml.template` | Local-dev logging config |

### Local files (gitignored)

`tomcat/tomcat-11/`:

| File | Purpose |
|------|---------|
| `lib/postgresql.jar` | PostgreSQL JDBC driver (auto-downloaded) |
| `lib/wikantik-custom.properties` | Customized Wikantik settings — edit here, not in the template |
| `lib/wikantik-mcp.properties` | MCP server config (rate limits, etc.) — rendered from template on first deploy; edit directly thereafter |
| `lib/log4j2.xml` | Effective log config |
| `conf/Catalina/localhost/ROOT.xml` | JNDI context with the actual DB password |
| `bin/setenv.sh` | Tomcat launch env (Vector API flags, `WIKANTIK_MAX_INFLIGHT_REQUESTS`). Write-once — edit directly to change the backpressure cap or JVM flags without re-running `deploy-local.sh`. |

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
| `permission denied to create role` on V031 (deploy aborts, Tomcat not started) | The `migrate` role lacks `CREATEROLE` | Provision it: `sudo -u postgres … bin/db/create-migrate-user.sh` (grants `CREATEROLE`). See [The `migrate` role](#the-migrate-role-required-for-redeploysh-recommended-for-deploy-localsh). |
| `must have admin option on role "pg_monitor"` on V031 | `migrate` not a `pg_monitor` admin | Same provisioning step (grants `pg_monitor WITH ADMIN OPTION`) |
| `permission denied for table schema_migrations` after a migration's DDL ran | `migrate` can't write the ledger (table owned by `postgres`, no grant) | Run `create-migrate-user.sh` (its ownership transfer covers `schema_migrations`), or `GRANT INSERT,SELECT,UPDATE,DELETE ON schema_migrations TO migrate;` as superuser |
| `slug 'X' is already claimed by canonical_id …` (WARN, repeated at boot) | `page_canonical_ids` rows drifted from frontmatter (handled gracefully) | Delete the stale rows so the rebuild re-inserts correct IDs — see `bin/db/one-shots/reconcile_page_canonical_ids.sh` for the pattern |
| `Match [Context] failed to set property [cachingAllowed]` (Tomcat WARNING) | `cachingAllowed` is invalid on `<Context>` in Tomcat 11 (valid only on `<Resources>`) | Remove the attribute from `<Context>` in `ROOT.xml` |
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
