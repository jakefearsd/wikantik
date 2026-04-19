---
type: article
tags:
- deployment
- postgresql
- operations
summary: End-to-end guide for bringing up a fresh Wikantik instance, including how to import pages from a legacy JSPWiki deployment.
---
# Fresh Deployment & Legacy Article Import

This guide walks you through standing up a new Wikantik instance against PostgreSQL and, optionally, importing an existing corpus from a legacy JSPWiki deployment. It reflects the current code on `main`: database migrations live in `bin/db/migrations/`, the WAR deploys as the ROOT context, and the React SPA is served from the same origin.

## Overview

A complete deployment touches five things:

| Layer | What it holds | How it is provisioned |
|-------|---------------|-----------------------|
| PostgreSQL | Users, roles, groups, policy grants, chunk embeddings, API keys | `bin/db/install-fresh.sh` + `bin/db/migrate.sh` (idempotent) |
| Tomcat 11 | Servlet container + JNDI DataSource | Downloaded automatically by `bin/deploy-local.sh` |
| Page corpus | Markdown files under `docs/wikantik-pages/` | Version-controlled; no per-instance copy step |
| Search indexes | Lucene (in `workDir`) + `content_chunk_embeddings` (Postgres) | Built on startup + async bootstrap |
| Embedding backend | Ollama HTTP endpoint (optional) | External service; URL configured in properties |

---

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java (JDK) | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js + npm | 18+ | Required — the WAR build runs `npm install` + `vite build` automatically |
| PostgreSQL | 15+ (with `pgvector`) | `psql --version`; pgvector is installed by `V004` |
| `curl`, `tar` | — | Used by `deploy-local.sh` to fetch Tomcat |

PostgreSQL must be reachable on `localhost:5432` (or override `PGHOST`/`PGPORT`).

---

## Fresh Deployment (Clean Instance)

### 1. Bootstrap the database

`install-fresh.sh` creates the database, the application role, and runs every migration in order. It is idempotent — re-running against an already-bootstrapped database is a no-op.

```bash
sudo -u postgres \
    DB_NAME=wikantik \
    DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' \
    bin/db/install-fresh.sh
```

This creates:

- Database `wikantik` with the `pgvector` extension installed
- Application role `jspwiki` with `CONNECT` + `USAGE` on `public`
- All tables from `bin/db/migrations/V001`–`V010` (users/roles/groups, policy grants, knowledge graph, content chunks + embeddings, API keys)
- A `schema_migrations` table so `migrate.sh` knows what has been applied

To bootstrap a dedicated migration role (recommended for production so `ALTER` migrations don't run as `postgres`), also set `DB_MIGRATE_PASSWORD` and the script will call `create-migrate-user.sh` and transfer ownership.

### 2. Build the WAR

```bash
mvn clean install -Dmaven.test.skip -T 1C
```

This compiles all modules, builds the React frontend, and produces `wikantik-war/target/Wikantik.war`. Use a full `mvn clean install` (without `-Dmaven.test.skip`) before any production cutover to make sure the test suite is green.

### 3. Deploy to Tomcat

```bash
bin/deploy-local.sh
```

The script is self-assembling. On first run it will:

1. Download Tomcat 11 into `tomcat/tomcat-11/` (gitignored)
2. Download the PostgreSQL JDBC driver to `tomcat/tomcat-11/lib/`
3. Copy the context template to `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` with a `YOUR_SECURE_PASSWORD_HERE` placeholder
4. Copy the properties template to `tomcat/tomcat-11/lib/wikantik-custom.properties`, substituting `@@REPO_ROOT@@` for the repo path
5. Copy the Log4j config to `tomcat/tomcat-11/lib/log4j2.xml`
6. Deploy `Wikantik.war` as `webapps/ROOT.war`
7. Run `migrate.sh` against the target database (no-op on fresh install, applies any new migrations on subsequent runs)
8. Run `bin/db/seed-users.sql` — upserts the `admin` / `admin123` and `jakefear@gmail.com` / `passw0rd` dev accounts
9. Start Tomcat

### 4. Set the database password

Edit the context file to replace the placeholder:

```bash
$EDITOR tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml
# username="wikantik" → username="jspwiki"
# password="YOUR_SECURE_PASSWORD_HERE" → password="ChangeMe123!" (or whatever you set)
```

Restart Tomcat so the DataSource picks up the new password:

```bash
tomcat/tomcat-11/bin/shutdown.sh
tomcat/tomcat-11/bin/startup.sh
```

### 5. Verify

- Browse http://localhost:8080/ — the React SPA should load.
- Log in as `admin` / `admin123` (change this immediately if the instance is anything more than local scratch).
- Visit `/admin/content/stats` from the admin UI — confirm page count and index status.
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

### 3. Convert wiki syntax → Markdown

The `scripts/wiki2markdown.py` converter is a faithful port of `WikiToMarkdownConverter.java`. It scans a directory for `.txt` files, scores each against a wiki-syntax heuristic, converts if the score is above threshold, renames straight to `.md` if the file is already Markdown, and deletes the source `.txt`.

```bash
# Dry-run first — prints every planned action with per-file warnings
python3 scripts/wiki2markdown.py /tmp/wikantik-import --dry-run

# Commit the conversion
python3 scripts/wiki2markdown.py /tmp/wikantik-import
```

Conversions performed:

| Wiki | Markdown |
|------|----------|
| `! Heading`, `!! Heading`, `!!! Heading` | `# Heading`, `## Heading`, `### Heading` |
| `__bold__` | `**bold**` |
| `''italic''` | `*italic*` |
| `{{inline code}}` | `` `inline code` `` |
| `[PageName]` | `[PageName]()` (Flexmark plugin syntax — Wikantik resolves the empty URL on render) |
| `[text\|url]` | `[text](url)` |
| `[{Plugin args}]` | `[{Plugin args}]()` |
| `{{{ code block }}}` | triple-backtick fenced block |
| `\|\| header \|\| cells \|\|` / `\| row \|` | GFM tables |

Any unconverted constructs (`%%style`, old-style footnotes, etc.) are flagged as warnings next to the filename. Resolve these by hand or leave them — Flexmark will render them as literal text.

### 4. Move into the page corpus

```bash
# Pages
cp /tmp/wikantik-import/*.md       docs/wikantik-pages/
# Keep the property sidecars — they carry categories, keywords, authors
cp /tmp/wikantik-import/*.properties docs/wikantik-pages/ 2>/dev/null || true
```

Attachments are not yet version-controlled in `docs/wikantik-pages/`. If the legacy corpus uses them:

```bash
# Attachments live under the wiki workDir, referenced by wikantik.basicAttachmentProvider.storageDir
mkdir -p tomcat/tomcat-11/data/attachments
cp -R /tmp/wikantik-import/attachments/* tomcat/tomcat-11/data/attachments/
```

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

Run a small script over the staging directory to inject this block on every `.md` file that does not already have one — the import script at `scripts/wiki2markdown.py` does **not** do this for you.

### 6. Commit and redeploy

```bash
git add docs/wikantik-pages/
git commit -m "import: legacy JSPWiki page corpus"
bin/deploy-local.sh
```

`deploy-local.sh` does not rebuild indexes automatically — Wikantik will pick up the new Markdown on first request, but Lucene and the chunk embeddings need to be rebuilt explicitly.

### 7. Rebuild indexes

**Lucene + chunks** — via admin UI at `/admin/index-status`, or via REST:

```bash
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

The script is safe to re-run: it preserves the context file (and therefore the DB password), reapplies any new migrations, re-seeds users, and restarts Tomcat. Rebuild the project first with `mvn clean install -Dmaven.test.skip -T 1C` if you have source changes.

For a faster iteration when only the WAR has changed:

```bash
mvn clean install -Dmaven.test.skip -T 1C -pl wikantik-war -am
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT tomcat/tomcat-11/webapps/ROOT.war
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

---

## Configuration Reference

### Git-tracked templates

Located in `wikantik-war/src/main/config/tomcat/`:

| File | Installed to | Purpose |
|------|--------------|---------|
| `Wikantik-context.xml.template` | `conf/Catalina/localhost/ROOT.xml` | JNDI DataSource for PostgreSQL |
| `wikantik-custom-postgresql.properties.template` | `lib/wikantik-custom.properties` | Wiki settings (page dir, workDir, admin bootstrap, hybrid search, CORS) |
| `log4j2-local.xml.template` | `lib/log4j2.xml` | Logging config pointed at `${catalina.base}/logs/wikantik` |

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
| `wikantik.search.embedding.base-url` | `http://inference.jakefear.com:11434` | Ollama endpoint |
| `wikantik.search.embedding.model` | `qwen3-embedding-0.6b` | Also supports `nomic-embed-v1.5`, `bge-m3` |
| `wikantik.cors.allowedOrigins` | _unset_ | Comma list; supports `https://*.example.com` wildcards |

---

## Troubleshooting

### Database

| Symptom | Cause | Resolution |
|---------|-------|------------|
| `Connection refused` in `catalina.out` | PostgreSQL not running | `sudo systemctl start postgresql` |
| `Password authentication failed for user "jspwiki"` | Password mismatch between `ROOT.xml` and role | Edit `ROOT.xml`; restart Tomcat |
| `relation "users" does not exist` | Migrations never ran | `DB_NAME=wikantik bin/db/migrate.sh --status` to check state; then run without `--status` to apply |
| `must be owner of table X` during migrate | Tables owned by `postgres`, running as `migrate` | Re-run `bin/db/create-migrate-user.sh` to transfer ownership |
| `extension "vector" is not available` | pgvector not installed on server | `sudo apt install postgresql-15-pgvector` (or the distro equivalent) |

### Deployment

| Symptom | Cause | Resolution |
|---------|-------|------------|
| `WAR file not found` | Build not run | `mvn clean install -Dmaven.test.skip -T 1C` |
| `npm not found` | Node.js missing | Install Node 18+; the WAR build depends on `vite` |
| `JNDI name not found` | Context file not loaded | Confirm `ROOT.xml` exists in `conf/Catalina/localhost/` |
| Login fails with seeded password | Schema mismatch after a reset | Re-run `psql -d wikantik -f bin/db/seed-users.sql` |
| 500 on admin API after DB restart | Expected: fail-soft for reads, hard-fail for writes | Connection pool reheats on next request; admin writes need the DB back |

### Indexes

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
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
     DB_APP_PASSWORD='ChangeMe123!' bin/db/install-fresh.sh

# Redeploy
bin/deploy-local.sh
```

---

## Related Documentation

- [Developing with PostgreSQL](DevelopingWithPostgresql.md) — JDBC and JNDI configuration reference
- [Docker Deployment](DockerDeployment.md) — containerized production deployment
- [Observability Design](ObservabilityDesign.md) — health checks, Prometheus metrics, request correlation
- [Index Rebuild](IndexRebuild.md) — deeper dive on Lucene + embedding rebuild internals
- `bin/db/migrations/README.md` — migration conventions for schema changes
- `CLAUDE.md` — sole-developer workflow notes, test credentials, and dev shortcuts
