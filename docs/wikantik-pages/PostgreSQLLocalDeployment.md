---
status: active
date: '2026-05-15'
title: PostgreSQL Local Deployment
related:
- DevelopingWithPostgresql
- DockerDeployment
- ObservabilityDesign
canonical_id: 01KQ0P44TNFQMPBHGY5R4G4A4F
cluster:
- wikantik-development
- devops-sre
type: article
tags:
- postgresql
- deployment
- tomcat
- wikantik-development
- pgvector
summary: 'End-to-end guide for a fresh Wikantik PostgreSQL deployment: database bootstrap,
  WAR build, Tomcat setup, legacy JSPWiki corpus import, and index rebuild.'
---
# Fresh Deployment & Legacy Article Import

This guide walks you through standing up a new Wikantik instance against PostgreSQL and, optionally, importing an existing corpus from a legacy JSPWiki deployment. It reflects the current code on `main`: database migrations live in `bin/db/migrations/`, the WAR deploys as the ROOT context, and the React SPA is served from the same origin.

## Deployment Stack at a Glance

A complete deployment touches five things:

| Layer | What it holds | How it is provisioned |
|-------|---------------|-----------------------|
| PostgreSQL | Users, roles, groups, policy grants, chunk embeddings, API keys | `bin/db/install-fresh.sh` + `bin/db/migrate.sh` (idempotent) |
| Tomcat 11 | Servlet container + JNDI DataSource | Downloaded automatically by `bin/deploy-local.sh` |
| Page corpus | Markdown files under `docs/wikantik-pages/` | Version-controlled; no per-instance copy step |
| Search indexes | Lucene (in `workDir`) + `content_chunk_embeddings` (Postgres) | Built on startup + async bootstrap |
| Embedding backend | Ollama HTTP endpoint (optional) | `bin/deploy-local.sh` starts a CPU embeddings container on `localhost:11434` through Docker (opt out with `WIKANTIK_LOCAL_EMBEDDINGS=false` in `.env`); the URL is set by `wikantik.search.embedding.base-url` |

---

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java (JDK) | 25+ | `java -version` (the WAR is Java 25 bytecode; `deploy-local.sh` and `redeploy.sh` refuse an older runtime) |
| Maven | 3.9+ | `mvn -version` |
| Node.js + npm | 20.19+ (or 22.12+) | Required — the WAR build runs `npm install` + `vite build` automatically |
| PostgreSQL | 15+ (with `pgvector` 0.5+) | `psql --version`; install the pgvector OS package first, then migration `V004` runs `CREATE EXTENSION vector` |
| Docker | any recent | `deploy-local.sh` starts the local CPU embeddings container with it (the first run downloads a ~600 MB model); database unit tests also use pgvector containers |
| `curl`, `tar` | — | Used by `deploy-local.sh` to fetch Tomcat and the JDBC driver |

PostgreSQL must be reachable on `localhost:5432` (or override `PGHOST`/`PGPORT`).

---

## Fresh Deployment (Clean Instance)

### 1. Bootstrap the database

`install-fresh.sh` creates the database, the application role, and runs every migration in order. It is idempotent — re-running against an already-bootstrapped database is a no-op.

```bash
sudo -u postgres \
    DB_NAME=wikantik \
    DB_APP_USER=wikantik \
    DB_APP_PASSWORD='choose-a-real-password' \
    bin/db/install-fresh.sh --no-migrate-role
```

`DB_APP_USER` / `DB_APP_PASSWORD` must match `POSTGRES_USER` / `POSTGRES_PASSWORD` in `.env` (step 3), or the application cannot connect. Without `--no-migrate-role` the script refuses to run unless `DB_MIGRATE_PASSWORD` is set.

This creates:

- Database `wikantik` with the `pgvector` extension installed
- Application role `wikantik` with `CONNECT` + `USAGE` on `public`; each migration grants it DML on the tables it creates
- Every table from the `bin/db/migrations/V*.sql` migrations (users/roles/groups, policy grants, knowledge graph, content chunks + embeddings, API keys, and the rest), plus the initial `admin` account
- A `schema_migrations` table so `migrate.sh` knows what has been applied

`--no-migrate-role` runs the migrations as the superuser, which is the simplest local setup. To bootstrap a dedicated migration role instead (recommended for production so `ALTER` migrations don't run as `postgres`), omit the flag and set `DB_MIGRATE_PASSWORD`; the script will call `create-migrate-user.sh` and transfer ownership. See [Developing with PostgreSQL](DevelopingWithPostgresql) for the full database setup.

### 2. Build the WAR

```bash
mvn clean install -DskipTests -T 1C
```

This compiles all modules, builds the React frontend, and produces `wikantik-war/target/Wikantik.war`. Use `-DskipTests`, not `-Dmaven.test.skip`: the latter also skips building `wikantik-main`'s test-jar, which other modules need, and breaks the full-reactor build. Run the full gate (`bin/run-tests.sh --parallel 4`) before any production cutover to make sure the test suite is green.

### 3. Configure secrets

`bin/deploy-local.sh` renders the Tomcat context file from a gitignored `.env` at the repository root. Create it from the template and set `POSTGRES_PASSWORD` to the same value you gave `DB_APP_PASSWORD`:

```bash
cp .env.example .env
$EDITOR .env      # POSTGRES_PASSWORD=choose-a-real-password
```

If `.env` is missing, the first `deploy-local.sh` run copies `.env.example` to `.env` and then stops because `POSTGRES_PASSWORD` is still the `CHANGEME` placeholder; edit it and re-run. There is no hand-edit step for `ROOT.xml`.

### 4. Deploy to Tomcat

```bash
bin/deploy-local.sh
```

The script is self-assembling. On first run it will:

1. Check that `npm` and `wikantik-war/target/Wikantik.war` exist and that the Java runtime is 25+
2. Source `.env` and refuse to continue if `POSTGRES_PASSWORD` is unset or `CHANGEME`
3. Download Tomcat 11 into `tomcat/tomcat-11/` (gitignored)
4. Download the PostgreSQL JDBC driver to `tomcat/tomcat-11/lib/postgresql.jar`
5. Start the local CPU embeddings container (needs Docker; skipped with `WIKANTIK_LOCAL_EMBEDDINGS=false`)
6. Render the config templates from `wikantik-war/src/main/config/tomcat/`: `conf/Catalina/localhost/ROOT.xml` (host, port, database, user and password from `.env`), `lib/wikantik-custom.properties` (substituting `@@REPO_ROOT@@` and the embedding URL), `lib/log4j2.xml`, `lib/wikantik-mcp.properties` and `bin/setenv.sh`, and patch `conf/context.xml` and `conf/server.xml`
7. Deploy `Wikantik.war` as `webapps/ROOT.war`
8. Run `migrate.sh` against the target database (no-op on fresh install, applies any new migrations on subsequent runs)
9. Run `bin/db/seed-users.sql`, which ensures the `admin` / `admin123` account exists (insert-if-absent, so an existing account is never overwritten; the first login forces a new password), then `bin/db/seed-users.local.sql` if you have that gitignored file
10. Start Tomcat

Templated config is write-once: each file is rendered on the first deploy and never overwritten, so your edits survive redeploys, but later changes to the templates do not reach an existing install. Edit the deployed file by hand, or delete it and re-run `deploy-local.sh`.

### 5. Verify the Deployment

- Browse http://localhost:8080/ — the React SPA should load.
- Log in as `admin` / `admin123`; you are asked to choose a new password on first login (change it immediately if the instance is anything more than local scratch).
- Open Admin > Content (`/admin/content`) — the Dashboard shows the page count and the Index Status tab shows the search-index state.
- `curl -s http://localhost:8080/api/health` reports engine, database and search-index health (it answers only to loopback and private-network clients).
- Tail `tomcat/tomcat-11/logs/catalina.out` for any DataSource errors.

For automated testing, `test.properties` (gitignored) holds a dedicated `testbot` admin account — see `CLAUDE.md` for how to recreate it after a database reset.

---

## Importing Articles from a Legacy JSPWiki Deployment

Wikantik stores pages as `*.md` files under `docs/wikantik-pages/` (version-controlled), with optional `*.properties` sidecars for per-page metadata. A legacy JSPWiki corpus stores pages as `*.txt` files using classic wiki syntax. The import is a one-time conversion.

### 1. Export the legacy corpus

Copy the page directory and attachments from the old deployment:

```bash
# On the legacy host
tar -czf legacy-pages.tgz \
    /var/lib/jspwiki/pages \
    /var/lib/jspwiki/attachments

scp legacy-pages.tgz new-host:/tmp/
```

A JSPWiki page directory typically contains:

- `PageName.txt` — the current revision, wiki syntax
- `PageName.properties` — frontmatter-like metadata (author, timestamp, keywords)
- `OLD/PageName/1.txt`, `2.txt`, ... — prior revisions (optional; Wikantik treats git history as the version store)
- `attachments/PageName-att/...` — file attachments per page

### 2. Stage the files

Unpack into a staging directory — **not** directly into `docs/wikantik-pages/` until after conversion:

```bash
mkdir -p /tmp/wikantik-import
tar -xzf /tmp/legacy-pages.tgz -C /tmp/wikantik-import --strip-components=3
```

Drop the `OLD/` hierarchy if you do not want to preserve old revisions as separate pages — Wikantik treats git as the version of record.

### 3. Convert wiki syntax to Markdown

The `bin/wiki2markdown.py` converter is a faithful port of `WikiToMarkdownConverter.java`. It scans a directory for `.txt` files, scores each against a wiki-syntax heuristic, converts if the score is above threshold, renames straight to `.md` if the file is already Markdown, and deletes the source `.txt`.

```bash
# Dry-run first — prints every planned action with per-file warnings
python3 bin/wiki2markdown.py /tmp/wikantik-import --dry-run

# Commit the conversion
python3 bin/wiki2markdown.py /tmp/wikantik-import
```

Conversions performed:

| Wiki | Markdown |
|------|----------|
| `! Heading`, `!! Heading`, `!!! Heading` | `# Heading`, `## Heading`, `### Heading` |
| `__bold__` | `**bold**` |
| `''italic''` | `*italic*` |
| `{{inline code}}` | `` `inline code` `` |
| ``[PageName]`` | ``[PageName]`()` (Flexmark plugin syntax — Wikantik resolves the empty URL on render) |
| `[text|url]` | `[text](http://example.com)` |
| `[{Plugin args}]` | `[{Plugin args}]()` |
| `{{{ code block }}}` | triple-backtick fenced block |
| `|| header || cells ||` / `|| row |` | GFM tables |

Any unconverted constructs (`%%style`, old-style footnotes, etc.) are flagged as warnings next to the filename. Resolve these by hand or leave them — Flexmark will render them as literal text.

### 4. Move into the page corpus

```bash
# Pages
cp /tmp/wikantik-import/*.md       docs/wikantik-pages/
# Keep the property sidecars — they carry categories, keywords, authors
cp /tmp/wikantik-import/*.properties docs/wikantik-pages/ 2>/dev/null || true
```

Attachments are not version-controlled in `docs/wikantik-pages/`. They live in the directory named by `wikantik.basicAttachmentProvider.storageDir`, which is blank by default (meaning a `wikantik-files` directory in the user's home folder) and is not set by the local template. If the legacy corpus uses attachments, point that property at a directory in `wikantik-custom.properties` and copy them there:

```bash
# In tomcat/tomcat-11/lib/wikantik-custom.properties:
#   wikantik.basicAttachmentProvider.storageDir = /path/to/attachments
mkdir -p /path/to/attachments
cp -R /tmp/wikantik-import/attachments/* /path/to/attachments/
```

(A Markdown or Obsidian vault does not need this conversion route: see `docs/user/ObsidianImportExport.md` for the vault importer.)

### 5. Backfill frontmatter (optional)

Wikantik expects a YAML frontmatter block on each Markdown page. If a page lacks one, `wikantik.frontmatter.autoDefaults = true` (set by the deploy template) will generate a minimal block — `title`, `type: article`, `tags: [uncategorized]`, empty `summary` — the first time the page is saved through the UI. For a bulk import you typically want real frontmatter. A minimal block looks like:

```yaml
---
type: article
tags:
- imported
summary: One-line summary for search and link previews.
---
```

Run a small script over the staging directory to inject this block on every `.md` file that does not already have one — the import script at `bin/wiki2markdown.py` does **not** do this for you.

### 6. Commit and redeploy

```bash
git add docs/wikantik-pages/
git commit -m "import: legacy JSPWiki page corpus"
bin/deploy-local.sh
```

`deploy-local.sh` does not rebuild indexes automatically — Wikantik will pick up the new Markdown on first request, but Lucene and the chunk embeddings need to be rebuilt explicitly.

### 7. Rebuild Lucene and Embedding Indexes

**Lucene + chunks** — via the admin UI (Admin > Content > Index Status), the helper script, or REST:

```bash
bin/trigger-rebuild-indexes.sh            # kicks off the rebuild (logs in with test.properties)
bin/trigger-rebuild-indexes.sh status     # polls /admin/content/index-status

# or directly, as the testbot admin account from test.properties:
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -u "${login}:${password}" -X POST \
     http://localhost:8080/admin/content/rebuild-indexes
```

**Embedding reindex** (hybrid search — only if `wikantik.search.hybrid.enabled=true` and the Ollama endpoint is reachable):

```bash
curl -u "${login}:${password}" -X POST \
     http://localhost:8080/admin/content/reindex-embeddings
```

The embedding rebuild is async and streams progress into `GET /admin/content/index-status`. For a corpus of a few thousand pages against a CPU-only Ollama host, budget 30–90 minutes.

On a completely fresh instance the startup `BootstrapEmbeddingIndexer` will kick off the embedding run automatically when it detects an empty `content_chunk_embeddings` table — no admin action needed unless you want to force a rerun.

---

## Subsequent Deployments

After the one-time setup, redeploying is a single command:

```bash
bin/deploy-local.sh
```

The script is safe to re-run: it preserves the rendered config files (and therefore the DB password), reapplies any new migrations, ensures the admin account exists, and restarts Tomcat. Rebuild the project first with `mvn clean install -DskipTests -T 1C` if you have source changes.

For faster iteration when only the code has changed, use the fast path:

```bash
mvn clean install -DskipTests -T 1C
bin/redeploy.sh    # stop Tomcat, rotate catalina.out, swap the WAR, apply pending migrations, start
```

`redeploy.sh` does not re-render templates, validate secrets or seed users; use `deploy-local.sh` for anything that touches those. It keeps `tomcat/tomcat-11/data/workdir`, so the Lucene index survives; delete that directory yourself if you want a clean index rebuild.

---

## Configuration Reference

### Git-tracked templates

Located in `wikantik-war/src/main/config/tomcat/`:

| File | Installed to | Purpose |
|------|--------------|---------|
| `Wikantik-context.xml.template` | `conf/Catalina/localhost/ROOT.xml` | JNDI DataSource for PostgreSQL |
| `wikantik-custom-postgresql.properties.template` | `lib/wikantik-custom.properties` | Wiki settings (page dir, workDir, admin bootstrap, hybrid search, CORS) |
| `wikantik-mcp.properties.template` | `lib/wikantik-mcp.properties` | MCP server settings (both MCP endpoints) |
| `log4j2-local.xml.template` | `lib/log4j2.xml` | Logging config pointed at `${catalina.base}/logs/wikantik` (honored once `wikantik.use.external.logconfig = true`; see [Logging Configuration](LoggingConfig)) |
| `Tomcat-context.xml.template`, `Tomcat-server.xml.template` | patched into `conf/context.xml` and `conf/server.xml` | Session-cookie SameSite policy; reverse-proxy and access-log valves |
| `setenv.sh.template` | `bin/setenv.sh` | Tomcat launch environment (Vector API flags, request backpressure cap) |

### Key properties

| Property | Default | Notes |
|----------|---------|-------|
| `wikantik.pageProvider` | `VersioningFileProvider` | Reads from the on-disk page directory |
| `wikantik.fileSystemProvider.pageDir` | `<repo>/docs/wikantik-pages` | Tracked in git |
| `wikantik.workDir` | `<repo>/tomcat/tomcat-11/data/workdir` | Lucene index lives here |
| `wikantik.datasource` | `jdbc/WikiDatabase` | Must match the Resource name in `ROOT.xml` |
| `wikantik.userdatabase` | `JDBCUserDatabase` | Users/roles stored in Postgres |
| `wikantik.admin.bootstrap` | _unset_ | Set to a login name on first deploy to guarantee admin access; remove after |
| `wikantik.frontmatter.autoDefaults` | `true` | Generates minimal frontmatter on save for pages without it |
| `wikantik.search.hybrid.enabled` | `true` | Toggle dense retrieval; falls back to BM25 if off |
| `wikantik.search.embedding.base-url` | ini default `http://inference.jakefear.com:11434` (a decommissioned host); `deploy-local.sh` renders `http://localhost:11434` from `WIKANTIK_EMBEDDING_BASE_URL` | Ollama endpoint |
| `wikantik.search.embedding.model` | `qwen3-embedding-0.6b` | Also supports `nomic-embed-v1.5`, `bge-m3` |
| `wikantik.cors.allowedOrigins` | _unset_ | Comma list; supports `https://*.example.com` wildcards |

---

## Troubleshooting

### Database Errors

| Symptom | Cause | Resolution |
|---------|-------|------------|
| `Connection refused` in `catalina.out` | PostgreSQL not running | `sudo systemctl start postgresql` |
| `Password authentication failed for user "wikantik"` | Password mismatch between `ROOT.xml` and role | Edit `ROOT.xml` (or delete it and re-run `deploy-local.sh` after fixing `.env`); restart Tomcat |
| `relation "users" does not exist` | Migrations never ran | `DB_NAME=wikantik bin/db/migrate.sh --status` to check state; then run without `--status` to apply |
| `must be owner of table X` during migrate | Tables owned by `postgres`, running as `migrate` | Re-run `bin/db/create-migrate-user.sh` to transfer ownership |
| `extension "vector" is not available` | pgvector not installed on server | `sudo apt install postgresql-<version>-pgvector` (or the distro equivalent), then restart PostgreSQL |

### Deployment Errors

| Symptom | Cause | Resolution |
|---------|-------|------------|
| `WAR file not found` | Build not run | `mvn clean install -DskipTests -T 1C` |
| `npm not found` | Node.js missing | Install Node 20.19+ (or 22.12+); the WAR build depends on `vite` |
| `JNDI name not found` | Context file not loaded | Confirm `ROOT.xml` exists in `conf/Catalina/localhost/` |
| Login fails with seeded password | The admin password was already changed, or the admin row is missing | `seed-users.sql` is insert-if-absent: re-run `deploy-local.sh` to re-create a missing admin, or use the `UPDATE` in the header of `bin/db/seed-users.sql` to reset it to `admin123` |
| 500 on admin API after DB restart | Expected: fail-soft for reads, hard-fail for writes | Connection pool reheats on next request; admin writes need the DB back |

### Index Errors

| Symptom | Cause | Resolution |
|---------|-------|------------|
| Empty search results on a freshly-imported corpus | Lucene empty | `POST /admin/content/rebuild-indexes` |
| Hybrid search returns BM25-only | Embedding backend unreachable or circuit open | Check `wikantik.search.embedding.base-url`; inspect `catalina.out` for `embedder` warnings |
| `reindex-embeddings` returns 409 | A rebuild is already running | Wait; poll `GET /admin/content/index-status` |
| `reindex-embeddings` returns 503 | `wikantik.search.hybrid.enabled=false` or no embedder configured | Enable hybrid search and confirm the Ollama endpoint responds |

### Logs

```bash
# Tomcat container log
tail -f tomcat/tomcat-11/logs/catalina.out

# Application logs (Log4j)
tail -f tomcat/tomcat-11/logs/wikantik/wikantik.log

# Filter for DataSource / JNDI errors
grep -i "jdbc\|datasource\|jndi" tomcat/tomcat-11/logs/catalina.out

# Migration status
DB_NAME=wikantik bin/db/migrate.sh --status
```

### Full reset (local only — destroys data)

```bash
# Tomcat
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT tomcat/tomcat-11/webapps/ROOT.war \
       tomcat/tomcat-11/data/workdir tomcat/tomcat-11/logs/wikantik

# Database
sudo -u postgres psql -c 'DROP DATABASE wikantik;'
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
     DB_APP_PASSWORD='choose-a-real-password' bin/db/install-fresh.sh --no-migrate-role

# Redeploy
bin/deploy-local.sh
```

---

## Related Documentation

- [Developing with PostgreSQL](DevelopingWithPostgresql) — JDBC and JNDI configuration reference
- [Docker Deployment](DockerDeployment) and [Wikantik on Docker](WikantikOnDocker) — containerized production deployment
- [Observability Design](ObservabilityDesign) — health checks, Prometheus metrics, request correlation
- [Search Index and Knowledge Graph Rebuild](SearchIndexAndKnowledgeGraphRebuild) — Lucene, chunk, embedding and Knowledge Graph rebuild runbook
- `bin/db/migrations/README.md` — migration conventions for schema changes
- `CLAUDE.md` — sole-developer workflow notes, test credentials, and dev shortcuts
