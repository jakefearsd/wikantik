# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
The preferred development apppoach is test driven development, with putting tests in place to show
defects before they are repaired, so that we know we have an effective test to detect the issue.

- Sole developer on this repo — work directly on main, no feature branches or PRs.
- Never swallow exceptions with empty catch blocks — always log at least a `LOG.warn()` with context and message.
- Don't use plan mode for trivial tasks (git add, single commands) — just do them directly.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js + npm | 18+ | Required — WAR build runs `npm install` + `vite build` automatically |
| PostgreSQL | 15+ | For local deployment; unit tests use in-memory H2 |

The `tomcat/` directory is **gitignored** and created on first run of `deploy-local.sh`.

## Development Commands

### Build Commands
```bash
# Standard build (includes tests and Apache RAT check)
mvn clean install

# Build without tests (faster, but not recommended for final checks)
mvn clean install -Dmaven.test.skip

# Parallel build for faster execution (unit tests only, NOT integration tests)
mvn clean install -T 1C -DskipITs
```

### Manual Testing Credentials

A dedicated admin account exists for manual and automated testing against the local deployment.
Credentials are stored in `test.properties` (gitignored, project root):

```bash
# Read credentials
cat test.properties
# test.user.login=testbot
# test.user.password=<password>
```

Use these credentials wherever a logged-in admin is needed — browser login, REST API calls, curl:

```bash
# Example: trigger a search-index rebuild as an authenticated admin
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -u "${login}:${password}" -X POST http://localhost:8080/admin/content/rebuild-indexes
```

The account (`testbot`, role `Admin`) lives in the local PostgreSQL database alongside `admin`.
To recreate it after a database reset, run:

```bash
PASSWORD=$(grep test.user.password test.properties | cut -d= -f2)
HASH=$(java -cp wikantik-util/target/wikantik-util-*.jar \
       com.wikantik.util.CryptoUtil --hash "$PASSWORD")
PGPASSWORD="<db-password>" psql -h localhost -U jspwiki -d jspwiki <<SQL
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, created, modified)
VALUES ('testbot','testbot@localhost','Test Automation Bot','testbot','$HASH','TestBot',NOW(),NOW());
INSERT INTO roles (login_name, role) VALUES ('testbot', 'Admin');
SQL
```

### Testing Commands
```bash
# Run all tests
mvn clean test

# Run a specific test class
mvn test -Dtest=MarkdownRendererTest

# Run a specific test method
mvn test -Dtest=MarkdownRendererTest#testMarkupSimpleMarkdown

# Debug a test
mvn test -Dtest=TestClassName#methodName -Dmaven.surefire.debug

# Run integration tests (MUST run without parallelism - see critical note below)
# Always use -fae (fail at end) so all IT submodules run even if one has failures.
# The integration tests all live under wikantik-it-tests (Selenide browser + REST + custom provider suites).
mvn clean install -Pintegration-tests -fae

# Run memory profiling test (from wikantik-main module)
mvn test -Dtest=MemoryProfiling
```

### Local Deployment (Tomcat 11)

The local Tomcat instance lives at `tomcat/tomcat-11` (gitignored). Use this for running and testing — do not use Cargo.

The wiki is deployed as the ROOT context, serving pages from `docs/wikantik-pages/` (version-controlled).

Configuration files (gitignored; templates in `wikantik-war/src/main/config/tomcat/`, applied by `deploy-local.sh`):
- `tomcat/tomcat-11/lib/wikantik-custom.properties` — wiki settings (page provider, base URL, PostgreSQL JDBC, etc.)
- `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` — Tomcat context with PostgreSQL JNDI DataSources

#### First-time setup (fresh clone)

```bash
# 1. Create the database, application role, and full schema in one step
#    (idempotent — safe to re-run).
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' \
    bin/db/install-fresh.sh

# 2. Build the WAR (also builds the React frontend via npm automatically)
mvn clean install -Dmaven.test.skip -T 1C

# 3. Bootstrap — downloads Tomcat if absent, copies and patches config templates,
#    removes stale files, deploys the WAR. deploy-local.sh also runs migrate.sh
#    so any pending migrations get applied on every redeploy.
bin/deploy-local.sh

# 4. Set your PostgreSQL password in the context file (path shown by script output):
#    tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml

# 5. Start Tomcat
tomcat/tomcat-11/bin/startup.sh
# Access at http://localhost:8080/ — default login: admin / admin
# React SPA at http://localhost:8080/
```

#### Applying database migrations manually

`deploy-local.sh` runs `migrate.sh` automatically on every deploy. To run
migrations by hand (e.g. against production):

```bash
DB_NAME=wikantik DB_APP_USER=jspwiki PGHOST=db.example.com PGUSER=postgres \
    PGPASSWORD='…' bin/db/migrate.sh

# Check what has been applied
bin/db/migrate.sh --status
```

#### Adding a schema change

**Every commit that changes the database schema MUST also add a numbered
migration** under `bin/db/migrations/`. Pick the
next `V<NNN>__description.sql`, write idempotent DDL (`CREATE TABLE IF NOT EXISTS`,
`ADD COLUMN IF NOT EXISTS`, `INSERT … ON CONFLICT DO NOTHING`), use the
`:app_user` psql variable for grants, and verify the migration is a no-op
when re-applied. See `bin/db/migrations/README.md`
for the full convention. Never edit a migration after it has been applied
in production — fix mistakes with a follow-up migration.

#### Routine redeploy (after first-time setup)

```bash
mvn clean install -Dmaven.test.skip -T 1C
bin/redeploy.sh    # shutdown + rotate catalina.out + swap WAR + startup
```

`bin/redeploy.sh` is the fast iteration path — it does **not** re-run
templates, DB migrations, or secrets validation. For first-time setup,
Tomcat upgrades, or anything that touches `wikantik-custom.properties` /
`ROOT.xml`, run `bin/deploy-local.sh` instead.

### Code Quality
```bash
# Apache RAT license check
mvn apache-rat:check

# Generate Javadocs with UML diagrams
mvn javadoc:javadoc
```
High test coverage at the line level, above 90% is a goal for this development team,
and while we recognize it is no a perfect measurement, it is one we choose to pursue.

### Container deployment

For container-based deployment (recommended for production), use
`bin/container.sh` — a top-level wrapper around `docker compose` that
drives build / up / down / logs / shell / psql / migrate / backup /
restore / smoke-test against the canonical service set:

```bash
bin/container.sh --help                         # subcommand list
bin/container.sh build                          # build the image
bin/container.sh up -d                          # start the dev stack
bin/container.sh logs -f                        # tail wikantik
bin/container.sh psql -- -c '\dt'               # list DB tables
bin/container.sh -e prod up -d                  # production stack with backup sidecar
bin/container.sh smoke-test                     # ephemeral up/health/down on test ports
```

Environments: `dev` (default), `prod`, `test`, `base`. Each subcommand
also accepts `--help`. Underlying compose files at the repo root
(`docker-compose{,.dev,.prod,.test}.yml`) and the runtime entrypoint at
`docker/entrypoint.sh` are still the source of truth — `bin/container.sh`
is just an ergonomic facade.

Monitoring is handled by the external **jakemon** stack — a Grafana Alloy agent on each host pushing metrics and logs to a central Prometheus + Loki + Grafana on host `inference`. The wikantik container exposes `/metrics`, which jakemon scrapes. There is no in-repo observability stack.

### Load testing

`bin/loadtest.sh <smoke|load|stress>` runs the k6 harness in `loadtest/`
against an instrumented set of endpoints. `--verify` scrapes `/metrics`
before and after and fails if a target dashboard panel did not move;
`--writes` adds an authenticated edit/delete cycle. k6 remote-writes its
own metrics into jakemon's central Prometheus (`192.168.0.10:9090`) so
offered load and host response share a timeline. See `loadtest/README.md`.

**Container deployment gotchas** (learned from the first docker1 deploy,
2026-05-16):
- **PostgreSQL image major version is coupled to the volume mount path.**
  The `db` service runs `pgvector/pgvector:pg18`. The pg18+ Docker images
  store data under a version-specific subdir and require the volume at
  `/var/lib/postgresql` — mounting the old `/var/lib/postgresql/data`
  makes the image refuse to start. Bumping the pg major version means
  re-checking that mount path. Keep the container pg major version in
  step with the local dev Postgres: a `pg_dump` restores forward across
  versions, not backward.
- **The deploying OS user must be in the `docker` group** on the target
  host. `bin/remote.sh bootstrap` only checks that the `docker` binary
  exists, not that the daemon socket is reachable — so it can pass while
  the actual deploy later fails with a `docker.sock` permission error.
- **Initialising the DB from an existing dump is a manual sequence**, not
  something `remote.sh deploy` does (it runs a full `up -d`, and the app
  entrypoint then migrates an empty schema). Bring up `db` alone, restore
  the dump (`DROP SCHEMA public CASCADE` first to clear the image's seed
  schema), then start `wikantik`. **To stand a fresh host up *from a backup*,
  use `bin/dr-restore.sh <host>`** — it automates this whole sequence (image +
  verified snapshot transfer → `db` → `restore.sh` → `wikantik` → smoke test),
  pulling the image from GHCR by default so it works even if the prod host is
  gone. See [docs/BackupAndRecovery.md](docs/BackupAndRecovery.md) §5.1.

### Remote container deployment over ssh

`bin/remote.sh` is the single entry point for deploying and administering
Wikantik on a remote host over ssh. It wraps `bin/container.sh` on the
remote and adds image transfer (`docker save | ssh 'docker load'`), pages
rsync, and a deploy lock. Configuration lives in `remote.env` at the repo
root (copy from `remote.env.example`; gitignored). Every state-changing
subcommand accepts `--dry-run`.

```bash
bin/remote.sh --help                          # subcommand list
bin/remote.sh bootstrap                       # first-time remote setup
bin/remote.sh deploy                          # local build → ssh push → up -d → health-poll
bin/remote.sh status                          # container ps + health + disk
bin/remote.sh pages-push docs/wikantik-pages  # rsync pages to remote (no --delete by default)
bin/remote.sh rollback                        # re-promote :rollback image
```

**Routine release upgrades** use two wrappers that capture the happy-path
command sequences as a single `bash` run each:

```bash
bin/cut-release.sh X.Y.Z       # version bump + CHANGELOG + tag + push → triggers release.yml
bin/deploy-release.sh X.Y.Z    # pull the published image → bin/remote.sh deploy --skip-build
```

`cut-release.sh` cuts the release (run a green build first — it does not
build); once `release.yml` is green, `deploy-release.sh` swaps the image on
the remote. The DB (named volume `repo_pgdata`) and pages (host bind mount)
persist across the swap, and the container entrypoint applies any pending
schema migrations on start — so an upgrade is just an image swap. The
**first** deploy is the exception: it initialises the DB (restore from a
`pg_dump`) and the page tree. Full procedure in
[docs/DockerDeployment.md](docs/DockerDeployment.md).

`remote.env` carries the ssh/host config; the prod container config is a
gitignored `.env.prod` at the repo root (`remote.sh` ships it to the remote
as `.env`, preferring it over the dev `.env`).

Prod content lives at `${WIKANTIK_PAGES_DIR}` on the remote host as a
bind mount — so `rsync` is the source of truth for the page tree,
independent of container lifecycle. Design doc:
[docs/superpowers/specs/2026-05-14-remote-container-admin-design.md](docs/superpowers/specs/2026-05-14-remote-container-admin-design.md).

### `bin/` script conventions

- Every script under `bin/` and `docker/` responds to `-h` / `--help`
  with its own header docstring. Use it.
- For scripts that pass through to a Java jar (`bin/kg-extract.sh`,
  `bin/kg-judge-experiment.sh`, `bin/kg-policy.sh`,
  `bin/kg-chunker-stats.sh`), the bash `--help` shows wrapper-level docs
  without triggering a build. Pass `--jar-help` to forward through and
  see the jar's full flag list.
- Credentials are read at runtime from `test.properties` (web logins) and
  `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` (DB password). No
  bin/ script embeds secrets.

## Running the entity extractor

`bin/kg-extract.sh` runs the per-page entity-extraction pipeline against the
local PostgreSQL via the deployed ROOT.xml. Defaults — `gemma4-assist:latest`
at concurrency 2, no judge — produce ~200–500 deduplicated, evidence-grounded
proposals in ~3.6 hours over a 1000-page corpus.

Routine usage:
```bash
bin/kg-extract.sh --max-pages 50 --dry-run --report reports/smoke.json   # smoke
bin/kg-extract.sh --report reports/extract-$(date +%Y%m%d).json          # full run
```

If the pending-proposal queue gets unwieldy and a clean restart is the right
call, snapshot pending proposals first, then wipe:

```bash
PGPASSWORD=… pg_dump -h localhost -U jspwiki -d jspwiki \
    --data-only --table=kg_proposals --column-inserts \
    --where="status = 'pending'" \
    > backups/kg_proposals_pending_$(date +%Y%m%d).sql

PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki -c \
    "DELETE FROM kg_proposals WHERE status = 'pending';"
```

Per the no-data-in-migrations rule, wipes are never landed in `Vxxx`
migrations — they are documented one-shots run by the operator.

## Architecture Overview

Wikantik is a modular Java-based wiki engine built on JEE technologies with the following key characteristics:

### Page Graph vs Knowledge Graph

Two distinct subsystems. Do not conflate them.

- **Page Graph.** A graph whose edges are real page-to-page wikilinks.
  Sources: wikilinks parsed from page bodies, period. Companion structure
  (not edges of the Page Graph itself, but co-resident in the same
  subsystem): `canonical_id` (rename-stable identifier in frontmatter)
  and `cluster:` (hub membership). Code: `com.wikantik.pagegraph.*`,
  `com.wikantik.api.pagegraph`. UI: `/page-graph` reader route,
  `/admin/page-graph/*` operator surfaces.
- **Knowledge Graph.** Nodes are LLM-extracted entities; edges are
  co-mention or typed-relation predicates between them. Code:
  `com.wikantik.knowledge.*`, `wikantik-knowledge` module, `kg_*`
  tables. UI: `/admin/knowledge-graph/*`, `/knowledge-mcp` tool surface.

Naming convention: the bare word "graph" is a code smell. Always say
"Page Graph", "Knowledge Graph", or `kg_*`/`pagegraph` in identifiers.

### Core Components

1. **WikiEngine** (`com.wikantik.WikiEngine`): Central orchestrator that manages all subsystems. Singleton per web application that provides access to all manager instances.

2. **WikiContext** (`com.wikantik.WikiContext`): Request-scoped context object that holds current page, user session, and request state. Essential for any operation that needs context about the current request.

3. **WikiSession** (`com.wikantik.WikiSession`): Manages user authentication state and principals. Integrates with JAAS for security.

4. **Manager Classes**: Each major subsystem has a manager interface with a default implementation:
   - `PageManager` - Page lifecycle and storage
   - `AttachmentManager` - File attachment handling
   - `PluginManager` - Plugin loading and execution
   - `FilterManager` - Content filtering pipeline
   - `SearchManager` - Search functionality
   - `RenderingManager` - Wiki markup rendering

### Module Structure

- **wikantik-bom**: Bill-of-materials POM pinning shared dependency versions
- **wikantik-api**: Core interfaces and contracts (manager interfaces, frontmatter, page save, Knowledge Graph service, Page Graph interfaces)
- **wikantik-main**: Main implementation — rendering, providers, auth, search, references, entity extraction, math parser
- **wikantik-event**: Event system for decoupled communication
- **wikantik-util**: Utility classes and helpers
- **wikantik-cache**: EhCache-based caching layer (1-hour TTL for render caches, 10K entry capacity)
- **wikantik-cache-memcached**: Distributed cache adapter for Memcached
- **wikantik-http**: Servlet filters — CSRF, CORS, CSP, security headers, SPA routing, `/wiki/{slug}?format=md|json` content filter
- **wikantik-rest**: REST/JSON API (`/api/*`) and admin panel endpoints (`/admin/*`)
- **wikantik-admin-mcp**: Admin MCP server at `/wikantik-admin-mcp` — 25 tools — adds admin-bypass mirrors of query_nodes + search_knowledge so curators see freshly-created entities, plus `list_orphaned_kg_nodes` for finding degree-0 entities at scale. Reconciled 2026-05-14. See `com.wikantik.mcp.McpServerInitializer`.
- **wikantik-knowledge**: Knowledge MCP server at `/knowledge-mcp` — 16 read-only tools (hybrid retrieval, Knowledge Graph traversal, schema discovery, structural-spine navigation, agent-grade page projection, batched markdown reads via `read_pages`) plus the Knowledge Graph service (pgvector-backed embeddings, co-mention graph, hub discovery). See `com.wikantik.knowledge.mcp.KnowledgeMcpInitializer`.
- **wikantik-tools**: OpenAPI 3.1 tool server at `/tools/*` — 2 tools (`search_wiki`, `get_page`) for OpenWebUI-compatible non-MCP clients.
- **wikantik-extract-cli**: Standalone entity-extractor CLI (offline/batch extraction against the Knowledge Graph pipeline)
- **wikantik-observability**: Health checks, Prometheus metrics, request correlation
- **wikantik-frontend**: React SPA (Vite build) — reader, editor, admin panel, Knowledge Graph viewer, Page Graph viewer
- **wikantik-war**: WAR packaging — bundles the React build and wires all servlets/filters
- **wikantik-wikipages**: Default wiki pages shipped with a fresh install
- **wikantik-it-tests**: Integration tests (Selenide browser automation, REST API, Cargo-launched Tomcat against PostgreSQL + pgvector)

#### Agent-facing surface summary

| Endpoint | Module | Protocol | Tools | Auth |
|----------|--------|----------|-------|------|
| `/wikantik-admin-mcp` | wikantik-admin-mcp | MCP (Streamable HTTP) | 25 write/analytics tools (incl. KG curation + admin-bypass reads + orphan-listing) | `McpAccessFilter` (bearer token / API key) |
| `/knowledge-mcp` | wikantik-knowledge | MCP (Streamable HTTP) | 16 read-only retrieval + Knowledge Graph + Page Graph structural-spine + agent-projection + batched-read tools | `KnowledgeMcpAccessFilter` (same scheme) |
| `/tools/*` | wikantik-tools | OpenAPI 3.1 | 2 tools (`search_wiki`, `get_page`) | API key |
| `/api/*` | wikantik-rest | REST/JSON | 24 Resource classes | `RestServletBase.checkPagePermission()` (ACL + policy grants) |
| `/admin/*` | wikantik-rest | REST/JSON | 9 admin resources (incl. `/admin/kg-policy/*`) | `AdminAuthFilter` (`AllPermission`) |
| `/wiki/{slug}?format=md\|json` | wikantik-rest | HTTP | Raw content for RAG ingestion / crawlers | Public (same ACL as page view) |
| `/api/changes?since=…` | wikantik-rest | REST/JSON | Incremental change feed for sync pipelines | Public |

### Key Design Patterns

1. **Provider Pattern**: Storage abstraction through provider interfaces
   - `PageProvider` for page storage (FileSystem, Versioning)
   - `AttachmentProvider` for attachment storage
   - `SearchProvider` for pluggable search engines

2. **Event-Driven Architecture**: WikiEvent system enables loose coupling
   - Components communicate through events
   - Listeners can react to page changes, user actions, etc.

3. **Command Pattern**: UI actions modeled as commands
   - URL-to-command mapping via CommandResolver
   - Clean separation of UI actions from business logic

4. **Plugin Architecture**: Extensible through multiple mechanisms
   - Plugins for dynamic content generation
   - Filters for content pre/post-processing
   - Custom editors, templates, and providers

### Rendering Pipeline

1. **Parser**: `MarkdownParser` converts Markdown to Flexmark AST.
2. **Filters**: Pre/post-processing of content
3. **Plugins**: Dynamic content insertion via `[{Plugin}]` syntax (auto-normalized to `[{Plugin}]()` for Flexmark)
4. **Renderer**: `MarkdownRenderer` produces HTML via Flexmark.

### Security Model

- JAAS-based authentication and authorization
- Fine-grained permissions: `view`, `comment`, `edit`, `modify`, `upload`, `rename`, `delete` (page); `createPages`, `createGroups`, `editPreferences`, `editProfile`, `login` (wiki)
- **Database-backed policy grants** — default role permissions stored in `policy_grants` table, managed via admin UI at `/admin/security`
- **Database-backed groups** — stored in `groups` + `group_members` tables, managed via admin UI
- Page-level ACLs via inline `[{ALLOW view Admin}]` syntax in page content
- REST API permission enforcement — all `/api/*` endpoints check ACLs via `RestServletBase.checkPagePermission()`
- Admin endpoints at `/admin/*` protected by `AdminAuthFilter` (requires `AllPermission`)
- Bootstrap admin override — `wikantik.admin.bootstrap` property guarantees admin access during initial setup
- Database-backed policy grants — always active when `wikantik.datasource` is configured (the default)
- NIST 800-63B password validation with common-password blocklist
- Deserialization filtering — `ObjectInputFilter` whitelists on all `ObjectInputStream` usage
- Pluggable authentication (LDAP, database, container, SSO via pac4j)
- SSO identity binding keys on `wikantik.sso.identityClaim` (default `sub`); set to `preferred_username` to trust a mutable claim. SSO never adopts a pre-existing non-SSO-linked local account of the same name (auto-provisioned profiles carry an `sso.subject` marker; a name collision without a matching marker fails closed). Multi-valued IdP claims are normalised to their first scalar; the SAML HTTP-POST `/sso/callback` is exempt from the CSRF synchronizer-token filter (the IdP-signed assertion is its own CSRF defense), and a successful SSO login rotates the HTTP session (fixation defense). SSO failures redirect to the `/login` SPA route with an `?error=` code surfaced by `LoginPage` — `/login` is dual-registered (web.xml + `SpaRoutingFilter.SPA_EXACT`).

### Important Configuration

- Main configuration: `ini/wikantik.properties` (in JAR)
- Custom overrides: `wikantik-custom.properties` (in WEB-INF or container lib)
- Security policy: `policy_grants` table (database-backed) or `WEB-INF/wikantik.policy` (file-based fallback)
- Permission migration DDL: `bin/db/postgresql-permissions.ddl` (legacy reference; current schema lives in `bin/db/migrations/V003__policy_grants.sql`)

### Extension Points

When implementing new features, consider these extension mechanisms:
- **Plugins**: For new wiki markup tags
- **Filters**: For content processing
- **Providers**: For custom storage backends
- **Modules**: For larger feature additions
- **Templates**: For UI customization

### Active Design Documents

Living design docs for in-flight architectural work (read before touching the relevant subsystem):

- **[docs/wikantik-pages/StructuralSpineDesign.md](docs/wikantik-pages/StructuralSpineDesign.md)** — Machine-queryable structural index for the wiki (clusters, tags, canonical IDs, `/api/structure/*`, matching MCP tools, generated `Main.md`, save-time enforcement). Sub-area of the **Page Graph** subsystem. All four phases implemented. Note: the typed `relations:` frontmatter mechanism was removed 2026-05-02 — see `docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md`.

**`Main.md` is generated.** Edit `docs/wikantik-pages/Main.pins.yaml` instead, then run `mvn package -pl wikantik-extract-cli -am -DskipTests -q && java -cp wikantik-extract-cli/target/wikantik-extract-cli.jar com.wikantik.extractcli.GenerateMainPageCli docs/wikantik-pages --write`. Hand-edits to `Main.md` will be reverted by the next regeneration and will fail `MainPageRegressionTest` on CI.

**Save-time enforcement is on.** `StructuralSpinePageFilter` runs in `preSave`: pages saved without `canonical_id` get one auto-assigned and injected into frontmatter. Toggle with `wikantik.structural_spine.enforcement.enabled=false` (default `true`). Operators triage lingering issues at `GET /admin/page-graph/conflicts`. (The `relations:` field validation was removed 2026-05-02 when typed relations were dropped.)
- **[docs/wikantik-pages/AgentGradeContentDesign.md](docs/wikantik-pages/AgentGradeContentDesign.md)** — Agent-grade content layer (`type: runbook`, verification metadata, `/api/pages/for-agent/{id}` token-optimised projection, scheduled retrieval-quality CI (`DefaultRetrievalQualityRunner`), worked tool-description examples). All six phases shipped 2026-04-25 — design is complete.

**Page verification is in.** Frontmatter accepts `verified_at`, `verified_by`, `confidence` (authoritative | provisional | stale — usually computed; author can pin), and `audience` (`humans` | `agents` | `[humans, agents]`). The structural index rebuild reads these and writes them through to `page_verification`. Confidence is computed from `verified_at` + the `trusted_authors` registry by `ConfidenceComputer` (90-day stale window, configurable via `wikantik.verification.stale_days`). Authors stamp pages via the `mark_page_verified` MCP tool on `/wikantik-admin-mcp`; operators triage at `GET /admin/verification?confidence=stale`.

**`/for-agent` projection is in.** `GET /api/pages/for-agent/{canonical_id}` and the matching `get_page_for_agent` MCP tool on `/knowledge-mcp` return a token-budgeted projection of any page: summary, key facts, headings outline, recent changes, MCP tool hints, and verification state — without the full markdown body. (The `outgoingRelations`/`incomingRelations` fields were removed 2026-05-02 when typed relations were dropped; use `get_outbound_links`/`get_backlinks` on `/wikantik-admin-mcp` for Page Graph traversal.) The service composes four extractors (`HeadingsOutlineExtractor`, `KeyFactsExtractor`, `RecentChangesAdapter`, `McpToolHintsResolver`) with per-field try/catch graceful degradation; failures surface on a `degraded` flag + `missing_fields` list rather than blowing the whole response. Memoised in `wikantik.forAgentCache` (1h TTL, 5K entries) by `(canonical_id, updated_at_millis)`. Response sizes flow into the `wikantik_for_agent_response_bytes` Prometheus histogram. URL deviation: design said `/api/pages/{id}/for-agent` but Servlet API can't tail-segment-pattern; current path mirrors `/api/pages/by-id/{id}`. The projection now also carries derived `agent_hints` — `prefer_tools` (ranked across the page and its cluster hub via `McpToolHintsResolver`) and `prefer_pages` (cluster hub + intra-cluster wikilink centrality, with a verified-authoritative bonus). When the projection's authored hub summary matches the generic "Index of pages on…" pattern, `HubSummarySynthesizer` overlays a Top-3 highlight at projection time and sets `summary_synthesized: true` (the page body is never modified). Both fields are computed at projection time — no author burden. See `docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md`.

**Runbook page type is in.** Frontmatter accepting `type: runbook` plus a six-key `runbook:` block (`when_to_use`, `inputs`, `steps`, `pitfalls`, `related_tools`, `references`) — schema-validated by `FrontmatterRunbookValidator`, enforced at save time by `RunbookValidationPageFilter` (priority -1003, gated by `wikantik.runbook.enforcement.enabled`, default `true`). The `/for-agent` projection runs the same validator at read time so corpus drift is graceful — invalid runbooks land with `runbook: null` and `"runbook"` in `missing_fields` rather than poisoning the response. `references:` entries resolve to either canonical_ids (via the structural index) or page titles (via `PageManager.pageExists`); `related_tools:` entries match `/api|knowledge-mcp|wikantik-admin-mcp|tools/*` or a bare snake_case tool name. `RunbookBlock` (in `wikantik-api`) carries snake_case Java field names so default Gson serialisation matches the wire form without a per-instance naming policy.

**Retrieval-quality CI is in.** `DefaultRetrievalQualityRunner` (in `wikantik-main` under `com.wikantik.knowledge.eval`) executes the curated `core-agent-queries` query set (16 questions seeded from the agent-cookbook runbooks, plus one cross-cluster query) through `BM25`, `HYBRID`, and `HYBRID_GRAPH`, computes per-query nDCG@5/@10 + Recall@20 + MRR, persists aggregates to `retrieval_runs`, and publishes `wikantik_retrieval_ndcg_at_5` / `_at_10` / `_recall_at_20` / `_mrr` gauges keyed by `{set,mode}`. Schedule activates when `wikantik.retrieval.cron.enabled=true` (default; default hour `wikantik.retrieval.cron.hour_utc=3`). Operators triage at `GET /admin/retrieval-quality?limit=N` and trigger ad-hoc runs via `POST /admin/retrieval-quality/run` with `{"query_set_id":"...","mode":"..."}`. The runner depends on narrow `Retriever` / `CanonicalIdResolver` functional seams so `RetrievalQualitySmokeTest` (the pre-merge gate) can drive it deterministically without a live search stack. Threshold tuning is deferred — `nDCG@5 >= 0.5` is the smoke gate; production thresholds calibrate after two weeks of nightly runs.

**Tool-description examples are in.** Every MCP tool on `/wikantik-admin-mcp` (25) and `/knowledge-mcp` (16), plus both OpenAPI tools on `/tools/*` (2), now ships with at least one worked input/output example in its schema. On the MCP servers, examples land per-property on `inputSchema.properties.<name>` and as a top-level `examples` array on `outputSchema` (the SDK's `JsonSchema` record can't carry top-level extras; `outputSchema` is a free Map). On the OpenAPI tool server, examples use OpenAPI 3.1's `example` keyword on request/response content and on parameter objects. The canonical specimen — `search_knowledge` — matches the design doc's hand-written example verbatim. Agents seeing concrete payloads make first-call success more reliable than reasoning from type schemas alone.
- **[docs/wikantik-pages/HybridRetrieval.md](docs/wikantik-pages/HybridRetrieval.md)** — Implemented. BM25 + dense + Knowledge Graph-aware rerank with fail-closed BM25 fallback.

  Dense backend is selectable via `wikantik.search.dense.backend = inmemory | pgvector | lucene-hnsw`.
  **`lucene-hnsw` is the docker1 production default** — an in-process Lucene HNSW
  ANN index (RAM `ByteBuffersDirectory`, rebuilt on boot, metadata read via
  DocValues not stored fields) that replaced the brute-force `inmemory` scan
  (~60% of search CPU). Knobs: `wikantik.search.dense.lucene.{m=16,ef_construction=64,ef_search=100}`.
  Specs: [lucene-hnsw](docs/superpowers/specs/2026-05-22-lucene-hnsw-dense-retrieval-design.md),
  [DocValues](docs/superpowers/specs/2026-05-22-hnsw-docvalues-retrieval-design.md).
  `pgvector` (server-side HNSW on `content_chunk_embeddings.embedding`, V032; see
  [its spec](docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md))
  is for split-DB topologies; `inmemory` (exact brute force) is the rollback.

  **Performance & concurrency tuning** (the 2026-05-22 scaling campaign): the
  per-request DB-connection tax and a chain of shared-lock hotspots — not CPU —
  were the real ceiling under load. Removed via short-TTL caches (API-key verify,
  user lookup, KG mentions) and hoisting shared JDK objects off the per-request
  path (`Collator`, `TimeZone`, `SecureRandom`). The backpressure semaphore
  (`WIKANTIK_MAX_INFLIGHT_REQUESTS`, default **390**) **must sit below Tomcat
  `maxThreads`=400** or it can never fire. Operator reference:
  [WikantikOperations.md § 1.5](docs/WikantikOperations.md#15-performance--concurrency-tuning);
  full diagnostic chain: [ScalingCharacterization.md § 14](docs/ScalingCharacterization.md).
  Diagnose concurrency stalls with thread dumps (`jcmd 1 Thread.print`) + host CPU
  from Prometheus — high latency + moderate CPU means blocking, not compute.
- **[docs/wikantik-pages/PageGraphVsKnowledgeGraph.md](docs/wikantik-pages/PageGraphVsKnowledgeGraph.md)** — Canonical explainer distinguishing the Page Graph (wikilink edges) from the Knowledge Graph (LLM-extracted entities). Reference this before touching either subsystem.
- **[docs/wikantik-pages/RetrievalExperimentHarness.md](docs/wikantik-pages/RetrievalExperimentHarness.md)** — Implemented but not yet scheduled; targeted by `AgentGradeContentDesign.md` for CI integration.
- **[IndexingSupport.md](IndexingSupport.md)** — Implemented. Raw content + change feed + sitemap for RAG ingestion and SEO.
- **[docs/superpowers/specs/2026-04-27-kg-inclusion-policy-design.md](docs/superpowers/specs/2026-04-27-kg-inclusion-policy-design.md)** — Cluster-primary KG inclusion/exclusion policy with admin dashboard, CLI, and frontmatter override. Implemented 2026-04-27 against `docs/superpowers/plans/2026-04-27-kg-inclusion-policy.md`. New `kg_cluster_policy` / `kg_policy_audit` / `kg_excluded_pages` tables; admin surface at `/admin/kg-policy/*`; `bin/kg-policy.sh` CLI. Default-exclude. System pages now also filtered out of the KG extraction pipeline (latent bug fix bundled in). Page-level override via `kg_include: true|false` frontmatter, validated at save time. See [KgInclusionPolicy](docs/wikantik-pages/KgInclusionPolicy.md) for the operator guide.
- **[docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md](docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md)** — Derived `agent_hints` projection field (no author burden), hub summary overlay, `read_pages` batch MCP tool, `/admin/agent-grade-audit` weak-signal report. Implemented 2026-05-10. Spec rationale: Gemini's external-agent feedback on the discovery → action gap and per-page read tax.

### Testing Approach

- Unit tests use JUnit 5
- Integration tests use Selenide for browser automation
- Test utilities in `com.wikantik.TestEngine`
- Mock implementations available for most components

### Critical: Integration Test Parallelism

**NEVER run integration tests with Maven parallel builds (`-T 1C` or `-T` flags).**

The integration tests use Maven Cargo to start embedded Tomcat instances that share fixed
port numbers (8080, 8205, etc.). Running multiple IT modules in parallel causes port
conflicts and unreliable test failures like:

```
Port number 8205 (defined with the property cargo.rmi.port) is in use
```

**Correct usage:**
```bash
# Integration tests - MUST be sequential (no -T flag), always use -fae
mvn clean install -Pintegration-tests -fae

# Unit tests only - can use parallel builds
mvn clean install -T 1C -DskipITs
```

Test suite reliability is critical for this project's development workflow.

## Token Efficiency Rules

Claude Code sessions on this project are expensive. Follow these rules to minimize waste:

### Reading Files
- **Use targeted reads.** Pass `offset` and `limit` to read only the lines you need. Never read an entire 500-line file to check one method.
- **Use Grep/Glob first, Read second.** Find the line numbers you need, then read just that range.
- **Never re-read a file you already have in context** unless it was modified since you last read it.

### Searching
- **Use Grep/Glob directly** for simple, directed searches (specific class, function, import). Do NOT spawn an Agent for "find where X is imported."
- **Reserve Agent/Explore for genuinely open-ended investigation** that will take 4+ queries to resolve.
- **Search once, record the answer.** If you grep for all callers of a method, write down the file list. Don't grep again.

### Tool Calls
- **Batch independent operations** into a single message with parallel tool calls. Reading 3 files? One message, three Read calls.
- **Use `mvn compile -pl <module> -q`** to check compilation of a single module instead of a full `mvn clean install` when you only changed one module.
- **Use `mvn test -pl <module> -Dtest=ClassName`** to run a single relevant test class, not the entire suite, until you're ready for a final verification build.
- **One full build at the end.** Don't run `mvn clean install -T 1C -DskipITs` after every single file edit. Compile-check the affected module, fix all issues, then do one final full build.

### Subagents
- **Don't use subagents for tasks you can do in 1-2 tool calls.** Creating a file, making an edit, running a grep — just do it directly.
- **Don't duplicate work.** If you delegate research to a subagent, don't also run the same searches yourself.

### Edits and Commits
- **Accumulate related edits, then build once.** Don't: edit file → build → edit file → build → edit file → build. Do: edit all files → build.
- **Stage specific files by name.** Never use `git add -A` — it picks up untracked junk. List the exact files.

### Planning and Communication
- **Don't narrate what you're about to do.** Just do it. "Let me read the file" followed by a Read call wastes tokens — just call Read.
- **Don't echo back file contents** you just read. Summarize the key finding in one sentence if needed.
- **Don't recap completed work** unless asked. The diff speaks for itself.
- **Keep commit messages to 1-3 lines.** The code change is the documentation.
