# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
The preferred development approach is test-driven development: put a test in place to demonstrate a
defect before repairing it, so we know the test actually detects the issue.

- Sole developer on this repo — work directly on main, no feature branches or PRs.
- Never swallow exceptions with empty catch blocks — always log at least a `LOG.warn()` with context and message.
- Don't use plan mode for trivial tasks (git add, single commands) — just do them directly.

Operational runbooks (container/remote deployment, load testing) live in
**[docs/admin/WikantikOperations.md](docs/admin/WikantikOperations.md)**, and the detailed design-doc status
blocks live in **[docs/developer/ProjectReference.md](docs/developer/ProjectReference.md)** — both kept out
of this file so it stays focused on rules + the architecture map.

## Superpowers skills — when they apply

Superpowers skills are first-class here; use them fully for substantive work. This section sets the
*trigger boundary* and **overrides** the "even a 1% chance → you MUST invoke a skill before any
response" rule in the `using-superpowers` skill (that framework's own instruction-priority order puts
this file first).

**Invoke the matching skill for substantive work:**
- New feature, behavior change, or new component → `brainstorming` first; then `writing-plans` if it's multi-step.
- Implementing any feature or bugfix → `test-driven-development` (a test that fails first, per the TDD note above).
- Any bug, test failure, or unexpected behavior → `systematic-debugging` before proposing a fix.
- Finishing substantive work → `verification-before-completion`, and `requesting-code-review` before declaring it done.

**Skip the ceremony — just do the task — for:** git operations (add/commit/push), a single-file edit
with a clear spec, grep/glob/read lookups, answering questions about existing code, config/doc tweaks,
and anything finishable in 1–2 tool calls. Don't invoke a skill, don't narrate, don't open plan mode.

**When a task crosses the line** (a "quick edit" that turns out to need design, or a one-liner that
uncovers a bug), escalate to the matching skill the moment it stops being mechanical.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 25+ | `java -version` |
| Maven | 3.9+ (recommended); enforced floor is 3.5 via `requireMavenVersion` | `mvn -version` |
| Node.js + npm | 20.19+ (or 22.12+) | Required by Vite 8 (Rolldown). WAR build runs `npm install` + `vite build` automatically |
| PostgreSQL | 15+ | For local deployment. **Unit tests that touch a database run against a real pgvector container** (`PostgresTestDb`, via Docker) with the migrations applied — there is no H2 schema. Without Docker those tests skip locally with a visible reason; CI passes `-Dtests.requireDocker=true` so an absent daemon **fails** the run |
| Docker (+ compose) | any recent | Required by the IT phase (per-module pgvector containers + the shared CPU-ollama embedder on 11435) and by `deploy-local.sh` (dev embedder on 11434; opt out with `WIKANTIK_LOCAL_EMBEDDINGS=false`). **The first run per instance pulls the ~600 MB `qwen3-embedding:0.6b` model** (once for dev, once for tests — the volumes are per-project on purpose): a download, not a hang. |
| Graphviz (`dot`) | any recent | **Only** for the code-health **site** build (`bin/site.sh`) module-coupling SVG. `apt-get install graphviz`. Optional — without it the site links the raw `.dot` instead. |

The `tomcat/` directory is **gitignored** and created on first run of `deploy-local.sh`.

## Development Commands

### Build Commands
```bash
# Standard build (includes tests and Apache RAT check)
mvn clean install

# Build without RUNNING tests (faster; not for final checks). Use -DskipTests,
# NOT -Dmaven.test.skip: the latter also skips building wikantik-main's test-jar,
# which wikantik-tools and the IT modules depend on — so a full-reactor build
# fails ("could not resolve …:jar:tests"), most reliably right after a release
# version bump when no prior test-jar is cached in ~/.m2. -DskipTests still
# builds the test-jars, it just doesn't run the tests.
mvn clean install -DskipTests

# Parallel build for faster execution (unit tests only, NOT integration tests)
mvn clean install -T 1C -DskipITs
```

Unit tests in wikantik-main and wikantik-rest run partitioned across forked JVMs
(`wikantik.surefire.forkCount`, default `0.5C` — cut the full build from ~10:30 to
~4:00). Pass `-Dwikantik.surefire.forkCount=1` to force a fully sequential run when
debugging a suspected cross-test interaction.

### Long builds — subagent protocol

Any Maven invocation that can exceed ~5 minutes (full unit build, IT reactor) MUST be
run through `bin/agent-build.sh` — never as a bare foreground call (agent Bash calls
are killed at ~10 minutes) and never via bare `nohup mvn -q … &` (with `-q` a finished
build's log is indistinguishable from a crashed one):

```bash
bin/agent-build.sh start unit -- mvn clean install -DskipITs
bin/agent-build.sh status unit     # instant, one line: RUNNING | SUCCESS exit=0 | FAILED exit=N (+ log tail) | KILLED
bin/agent-build.sh wait unit 540   # bounded block ≤540s; exit 0=success 1=failed 2=still running
bin/agent-build.sh tail unit 30    # inspect the log
```

The script detaches the build into its own session (survives harness process-group
kills), unsets `WIKANTIK_*` env vars in the child, and appends an `EXIT=<code>`
sentinel to the log so completion is never ambiguous. Poll by calling `status` (or
bounded `wait`) repeatedly until the build terminates. **Never end your turn to "wait
for a notification"** — detached builds are not harness-tracked; nothing resumes an
idle agent. Keep polling until you can report SUCCESS or FAILED.

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

The account (`testbot`, role `Admin`) lives in the local database. To recreate it after a database reset, see
[ProjectReference](docs/developer/ProjectReference.md#recreating-the-testbot-account).

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

# CANONICAL pre-commit gate (~6-8 min warm): Phase 1 runs every unit test once
# (-T 1C -DskipITs), then the CI static checks (PMD ratchet, npm audit — fails only
# under --all, the release gate — eslint, vitest --coverage), then Phase 2 runs the
# four IT modules in one -T 4 reactor. REQUIRES DOCKER (see Prerequisites for the
# shared 11435 embedder and its first-run model download; watch it with
# docker logs -f $(docker ps -qf name=wikantik-embed-test)).
bin/run-tests.sh --parallel 4

# RELEASE gate (adds the opt-in suites; npm audit becomes fatal, ~15 min)
bin/run-tests.sh --all

# Fallbacks: bin/run-tests.sh (sequential IT phase). Dense module alone: bin/run-tests.sh --module dense

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
#    (idempotent — safe to re-run). DB_APP_USER/DB_APP_PASSWORD must match
#    POSTGRES_USER/POSTGRES_PASSWORD in .env (step 4) or the app can't connect.
#    --no-migrate-role runs migrations as the superuser (simplest for local dev);
#    omit it and pass DB_MIGRATE_PASSWORD='…' for a least-privilege migrate role.
#    The script fails fast if you provide neither.
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
    DB_APP_PASSWORD='choose-a-real-password' \
    bin/db/install-fresh.sh --no-migrate-role

# 2. Build the WAR (also builds the React frontend via npm automatically)
mvn clean install -DskipTests -T 1C

# 3. Bootstrap — downloads Tomcat if absent, copies and patches config templates,
#    removes stale files, deploys the WAR. deploy-local.sh also runs migrate.sh
#    so any pending migrations get applied on every redeploy.
bin/deploy-local.sh

# 4. deploy-local.sh renders ROOT.xml and wikantik-custom.properties from .env —
#    on the very first run it copies .env.example to .env and exits; edit
#    POSTGRES_PASSWORD in .env and re-run bin/deploy-local.sh

# 5. Start Tomcat
tomcat/tomcat-11/bin/startup.sh
# Access at http://localhost:8080/ — first login: admin / admin123 (a new password is required on first login)
# React SPA at http://localhost:8080/
```

#### Applying database migrations manually

`deploy-local.sh` runs `migrate.sh` automatically on every deploy. To run
migrations by hand (e.g. against production):

```bash
DB_NAME=wikantik DB_APP_USER=wikantik PGHOST=db.example.com PGUSER=postgres \
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
mvn clean install -DskipTests -T 1C
bin/redeploy.sh    # shutdown + rotate catalina.out + swap WAR + startup
```

`bin/redeploy.sh` is the fast iteration path. It **does** run
`bin/db/migrate.sh` (idempotent — applies only pending `V*.sql`, as the app
role from `.env`), but it does **not** re-run template materialisation,
secrets validation, Tomcat-upgrade snapshot logic, or dev-user seeding. For
first-time setup, Tomcat upgrades, or anything that touches
`wikantik-custom.properties` / `ROOT.xml`, run `bin/deploy-local.sh` instead.

### Code Quality
```bash
# Apache RAT — informational only: bound to no phase, run by no CI job, and red everywhere
# (~1,990 files without Apache headers). A red run is not a regression to chase.
mvn apache-rat:check

# Generate Javadocs with UML diagrams
mvn javadoc:javadoc

# CI-enforced complexity ratchet — fails on any NEW PMD design-rule violation
# not already in the burn-down baseline build-support/pmd-complexity-baseline.properties
# (entries only ever come out)
mvn pmd:check -Pcomplexity-gate

# Per-module line-coverage ratchet (JaCoCo check under -Pcoverage): floors pinned per pom,
# measured level rounded down, only ever raised, capped at 0.90.
mvn clean install -Pcoverage -DskipITs
```

```bash
# Full code-health site (coverage/coupling/PMD/CPD/SpotBugs/tests/tech-debt/deps)
bin/site.sh                 # -> target/staging/index.html

# ONE publish command for everything on www.wikantik.com — the marketing site
# (docroot root) AND the code-health site (/site subdir). Same host, same
# docroot, one interactive sudo prompt. Must be run with `!` so the prompt is
# answerable.
! bin/deploy-marketing.sh              # both (code-health half skipped + reported if unbuilt)
! bin/deploy-marketing.sh --build-site # run bin/site.sh first, then publish both
! bin/deploy-marketing.sh --marketing-only
! bin/deploy-site.sh                   # thin shim for --site-only
```

High test coverage at the line level, above 90% is a goal for this development team,
and while we recognize it is not a perfect measurement, it is one we choose to pursue.

> Container/remote deployment and load testing live in
> [docs/admin/WikantikOperations.md](docs/admin/WikantikOperations.md).
> **Agent shells are blocked** from `bin/deploy-release.sh`, writes on docker1, and installing
> timers/cron. Hand the exact command to the user to run with `!`, then verify the result
> yourself (health, `/api/capabilities`, `bin/smoke-wiki.sh http://docker1:8080`).

## Architecture Overview

### Wikantik is one component of the Simple Agility stack

Wikantik, **jakemon** (`~/source/jakemon`, the observability plane) and **Roller** are components of the
**Simple Agility** stack. The cross-repo contracts live on the wiki (canonical, not mirrored here):
`SimpleAgilityStackHub` → `SimpleAgilityObservabilityPlane` (what jakemon owns; push feeds vs pull
queries; the "do not build this in your backend" list), `SimpleAgilityTelemetryContract` (`/metrics`,
structured-log envelope + `correlation_id`, `/healthz`), `SimpleAgilityFeedbackLoopPattern`, and
`SimpleAgilityOnCallAutomation` (escalation ladder; the scoped-admin surfaces products must expose),
and **`SimpleAgilityRoadmap`** — read that one before going looking for a capability, because the
contracts state intent while the roadmap states what is actually built.

Start an agent session with `get_briefing(clusters=["wikantik-development"], prompt="<your first task>")` on the knowledge MCP; the briefing carries this repository's pages and the Simple Agility contracts they cite (SA-13, ADR-0011).

Read the plane page before adding any metrics/log/event store, third-party poller, alerting, or SLO
computation to Wikantik — those belong to jakemon. Apply its **"rule that decides where data lives"**
first: telemetry emitted for *observation* goes to the plane, but data that is a *functional input* to
Wikantik's own behavior stays a product table — `retrieval_query_log`, `briefing_log`,
`content_change_log`, `search_visibility_snapshot` and `/admin/audit` are all correct as they are and
must not be "consolidated" into Loki. Content Intelligence (`wikantik-insights`) is the reference
implementation of the loop pattern.

Telemetry conformance is machine-checked, not a prose checklist:

```bash
bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik
```

### Page Graph (and the removed Knowledge Graph)

The Page Graph is the only graph. The Knowledge Graph was removed; the note below says what is left.

- **Page Graph.** Edges are real page-to-page wikilinks parsed from page bodies, nothing else.
  Co-resident: `canonical_id` (rename-stable id) and `cluster:` (hub membership). Code
  `com.wikantik.pagegraph.*`, `com.wikantik.api.pagegraph`; UI `/page-graph`, `/admin/page-graph/*`.
- **Knowledge Graph — REMOVED (2026-11).** LLM-extracted entities, their curation tools,
  the inclusion policy, the judge and hub discovery were switched off in 2.5.12 and deleted
  in the following release (migration `V070` dropped the `kg_*`/`hub_*`/`chunk_entity_mentions`
  tables; plans `docs/superpowers/plans/2026-11-08-kg-retirement-pass.md` and
  `2026-11-09-kg-deletion.md`). Do not reintroduce it without new evidence. `kg_include`
  frontmatter was stripped from every prod page (2026-11-09) — don't write it. Renaming the
  KG-named leftovers below is **deferred by decision**; don't start it unasked. Still here despite
  the names: `kg_content_chunks`/`content_chunk_embeddings` (dense retrieval), the
  `wikantik-knowledge` module and `/knowledge-mcp` endpoint (retrieval + structure tools), and
  `com.wikantik.knowledge.*` (chunking, bundle, briefing, agent projection, eval, query log).
  "Similar pages" and agent `relatedPages` come from page-embedding centroids
  (`PageEmbeddingSimilarity`).
- **Citation edges.** A third, derived edge type: inline `cite://` claims grounded in a target
  section, parsed at save into `citations`, version-pinned + span-hashed with graded staleness.
  Code `com.wikantik.citation.*`; surfaces `/admin/drift/citations`, `list_stale_citations`,
  for-agent `stale_citations`.

Naming convention: the bare word "graph" is a code smell. Always say
"Page Graph" (or `pagegraph` in identifiers).

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
- **wikantik-api**: Core interfaces and contracts (manager interfaces, frontmatter, page save, retrieval/bundle types, Page Graph interfaces)
- **wikantik-main**: Main implementation — rendering, providers, auth, search, references, math parser, Obsidian vault importer (`com.wikantik.importer`: zip reader, planner, body rewriter, in-memory job registry; no schema change)
- **wikantik-event**: Event system for decoupled communication
- **wikantik-util**: Utility classes and helpers
- **wikantik-jdbc**: **The one way to touch the database.** `com.wikantik.jdbc.Jdbc` is the only data-access primitive (`JdbcSupport` is the `extends` convenience); `inTransaction` rolls back on any `Throwable`. Enforced by `JdbcAccessArchTest` (J-1; sole carve-out `JDBCPlugin`). Its test-jar ships `PostgresTestDb` (pgvector, all migrations applied — no H2), `@RequiresPostgres`, `FaultInjectingDataSource`. **Adding a repository:** hold a `Jdbc`, write the migration first, test against `PostgresTestDb`; a multi-statement write gets a rollback test. ADR-0010.
- **wikantik-cache**: EhCache-based caching layer (1-hour TTL for render caches, 10K entry capacity)
- **wikantik-cache-memcached**: Memcached adapter, **opt-in, not wired into the WAR** (override the `CachingManager` mapping in `classmappings.xml`, set `wikantik.cache.memcached.servers`). Kept for multi-node deployments.
- **wikantik-http**: Servlet filters — CSRF, CORS, CSP, security headers, SPA routing, `/wiki/{slug}?format=md|json` content filter
- **wikantik-rest**: REST/JSON API (`/api/*`) and admin panel endpoints (`/admin/*`). Includes the structured-curation surfaces: `/api/frontmatter-schema`, `/api/frontmatter/validate`, and `/admin/embeddings/*` (dense-index status + frontmatter backfill).
- **wikantik-mcp-core**: Shared MCP substrate — `McpTool`/`McpToolUtils`/`McpAudit`, endpoint bootstrap, the access filter, config. Hosts no tools since the Knowledge Graph removal (its shared `QueryNodesTool`/`SearchKnowledgeTool` were deleted). Extracted in 2.3.0 to break the `wikantik-knowledge → wikantik-admin-mcp` module cycle.
- **wikantik-coverage-report**: Aggregated JaCoCo cross-module coverage report. Build-time only — produces no runtime artifact.
- **wikantik-admin-mcp**: Admin MCP at `/wikantik-admin-mcp` — 20 tools: page authoring/inspection, `list_retrieval_queries`, `rename_cluster` (plan unless `confirm=true`), `list_content_opportunities` + `snooze_opportunity`. See `com.wikantik.mcp.McpServerInitializer`.
- **wikantik-knowledge**: Knowledge MCP at `/knowledge-mcp` — 16 read-only tools when fully wired (retrieval, structural spine, `get_page_neighbors`, `get_page_for_agent`, `read_pages`, `get_ontology`/`sparql_query`, `list_stale_citations`, `get_briefing` — ADR-0011). Ontology query expansion sits behind `wikantik.search.ontologyExpansion.enabled` (default off). See `com.wikantik.knowledge.mcp.KnowledgeMcpInitializer`.
- **wikantik-tools**: OpenAPI 3.1 tool server at `/tools/*` — 2 tools (`search_wiki`, `get_page`) for OpenWebUI-compatible non-MCP clients.
- **wikantik-scim**: SCIM 2.0 provisioning server at `/scim/v2/*` — bearer-authed `Users` + `Groups` CRUD + discovery (`ServiceProviderConfig`/`Schemas`/`ResourceTypes`) for IdP-driven onboarding/offboarding. User decommission routes through the unified `UserLifecycleService`; Group membership sync routes through `GroupManager`. SCIM Groups never grant the Admin role.
- **wikantik-extract-cli**: Offline CLIs (`ChunkerStatsCli`, `CorpusDivergenceCli`, `GenerateMainPageCli`, `GenerateConfigReferenceCli`, …). Also hosts `IngestDocumentsCli` — the derived-pages batch ingester (an HTTP client over `POST /api/ingest`, walks a folder).
- **wikantik-ingest**: Tika + flexmark-html2md extraction for **derived pages**, isolating PDFBox/POI from wikantik-main. A page is derived iff its frontmatter has `derived_from`; its body is machine-owned (ADR-0004). Wiki side `com.wikantik.derived.*`; surfaces `POST /api/ingest`, `/admin/derived/{reflow,status}`.
- **wikantik-plantuml**: Server-side drawing for ` ```plantuml ` fences — shaded MIT build, Ditaa stripped (LGPL), stdlib cut to C4, Smetana layout; sandboxed, time-bounded, SVG scrubbed. `PlantUmlLicenseArchTest` guards the licensing. `plantuml-mit` is **test-scope** in wikantik-main/-rest only and must never become compile scope. Config `wikantik.fences.plantuml.*`.
- **wikantik-ontology**: RDF/OWL layer (Apache Jena, TDB2) — `wikantik.ttl` T-Box (`Page` + 5 content classes, SKOS), Page/Concept projectors, event-incremental sync (`OntologyEventListener`) with a nightly rebuild backstop. Public read surface `/sparql`, `/id/*`, `/export/*` materializes only anonymously-viewable pages. `SemanticHeadRenderer` takes JSON-LD `@type` from the ontology (`SemanticHeadOntologyAgreementTest`). **Kept by decision (2026-11-09)** after the KG removal.
- **wikantik-connectors**: Seven connector types syncing into derived pages via `SyncOrchestrator` (`poll()` never throws — load-bearing). Six are admin-creatable (`ConnectorConfigCodec.UI_TYPES`); `filesystem` stays properties-only by design (D9: it reads host paths). Configs live in `connector_configs`, hot-applied without restart; secrets only in the encrypted `connector_credentials`. Kill switch `wikantik.connectors.enabled` (default true). UI `/admin/connectors`; guide `docs/admin/Connectors.md`.
- **wikantik-insights** (Content Intelligence): search-visibility facts, opportunity rules, effect measurement (`com.wikantik.insights.*`). Depends on neither `wikantik-api` nor `wikantik-main` — page state arrives through the `PageFacts` port. Surfaces `/admin/insights/{acquisition,backlog,ingest}`. The reference implementation of the Simple Agility feedback loop.
- **wikantik-observability**: Health checks, Prometheus metrics, request correlation
- **wikantik-frontend**: React SPA (Vite build) — reader, editor, admin panel, Page Graph viewer (the Knowledge Graph UI was removed 2026-11-08)
- **wikantik-war**: WAR packaging — bundles the React build and wires all servlets/filters
- **wikantik-wikipages**: Default wiki pages shipped with a fresh install
- **wikantik-it-tests**: Cargo-launched Tomcat + PostgreSQL/pgvector ITs. Default gate modules: `wikantik-it-test-rest` (REST + Selenide browser, built on the `wikantik-selenide-tests` test-jar), `-sso` (Keycloak OIDC+SAML), `-custom-jdbc`, `-dense` (the only one with embeddings on). Opt-in: `-scim-fullloop` (Authentik).

#### Agent-facing surface summary

| Endpoint | Module | Protocol | Tools | Auth |
|----------|--------|----------|-------|------|
| `/wikantik-admin-mcp` | wikantik-admin-mcp | MCP (Streamable HTTP) | 20 write/analytics tools: page authoring + inspection, admin-bypass reads, orphan listing, `list_retrieval_queries`, `rename_cluster`, Content Intelligence `list_content_opportunities`/`snooze_opportunity` | `McpAccessFilter` (`mcp_admin`) |
| `/knowledge-mcp` | wikantik-knowledge | MCP (Streamable HTTP) | 16 read-only tools (see the module entry); `get_page_for_agent` carries `draft_active` | `McpAccessFilter` (`mcp_read`) |
| `/tools/*` | wikantik-tools | OpenAPI 3.1 | 2 tools (`search_wiki`, `get_page`) | API key |
| `/scim/v2/*` | wikantik-scim | SCIM 2.0 | `Users` (CRUD, PATCH active, soft-delete) + `Groups` (CRUD, membership PATCH, hard delete) + discovery | `ScimAccessFilter` (bearer `wikantik.scim.token`) |
| `/api/*` | wikantik-rest | REST/JSON | 36 servlets (38 url-patterns) in `web.xml` — pages, search, mentions, previews/embeds, Obsidian import/export, fence render, ingest, collab (dark behind `wikantik.collab.enabled`); full list in ProjectReference | `RestServletBase.checkPagePermission()` (ACL + policy grants) |
| `/admin/*` | wikantik-rest | REST/JSON | 27 servlets (33 url-patterns) — embeddings, collab drafts, audit, ontology, drift, derived, cluster rename, connectors, insights; full list in ProjectReference | `AdminAuthFilter` (`AllPermission`) |
| `/wiki/{slug}?format=md\|json` | wikantik-rest | HTTP | Raw content for RAG ingestion / crawlers | Public — view ACL **enforced** (WikiPageFormatFilter gates on the caller's session; 404 hides restricted pages) |
| `/api/changes?since=…` | wikantik-rest | REST/JSON | Incremental change feed for sync pipelines | Public |
| `/api/bundle?q=…` | wikantik-rest | REST/JSON | RAG-as-a-Service **context bundle** — ranked, de-duplicated, version-pinned-cited sections (NO answer synthesis); dense-chunk source by default. Mirrors the `assemble_bundle` MCP tool | `RestServletBase` (view ACL via the candidate retrieval) |
| `/api/briefing?pins=…&clusters=…&prompt=…&format=md\|json` | wikantik-rest | REST/JSON + markdown | Session-start context briefing (members → cited → elsewhere); `briefing_log` telemetry. Mirrors `get_briefing` | `RestServletBase` (view ACL, as `/api/bundle`) |
| `/sparql`, `/id/{type}/{id}`, `/export/*.ttl\|.nt` | wikantik-rest | RDF (SPARQL/JSON-LD/Turtle/N-Triples) | Public read-only ontology: SPARQL query, per-resource dereferencing, full dumps | Public, read-only, permissive CORS — **public/restricted ACL split** (only anonymously-viewable pages are materialized, so restricted content cannot be queried) |

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

- **Rendered fences** (` ```mermaid `, ` ```plantuml `; the `example` modifier keeps either as code): `com.wikantik.markdown.extensions.fences`, validated server- and client-side against one fixture (`wikantik-frontend/src/utils/__fixtures__/renderedFences.json`); unsafe sources show as source plus reason. Mermaid draws in the browser. PlantUML is server-drawn: a signed placeholder that `ServerFenceResolver` splices **once, at the outermost `textToHTML`**, so render caches never hold drawings. New renderer: see the `RenderedFenceRenderer` Javadoc. Corpus checkers: `bin/check-plantuml.sh`, `check-mermaid.mjs`.

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
- `JDBCPlugin` (page-authored SQL against the wiki datasource) ships **disabled by default** behind `wikantik.plugin.jdbc.enabled` (checked before even the admin gate) — enable it only if you accept that a high-privilege DB role executes editor-supplied SQL.
- SSRF egress policy on connector fetches — `EgressGuard` (`wikantik-connectors`) rejects non-http(s) schemes and any host resolving to loopback/private/link-local/multicast/IPv6-ULA addresses, re-validated on every redirect hop; `wikantik.connectors.egress.allowPrivate=true` opts a deliberate internal crawl back in.
- Render caches skip viewer-dependent output — a render that consults the caller's session/ACLs (e.g. an ACL-aware `InsertPage`, `$username`) flags itself via `Context.VAR_VIEWER_SENSITIVE` and is excluded from the principal-less document/HTML caches, so it can't be cached for one user and served to another.
- Deserialization filtering — `ObjectInputFilter` whitelists on all `ObjectInputStream` usage
- MCP API-key scopes (`ApiKeyService.Scope`): `mcp_read` (`/knowledge-mcp` only) ⊂ `mcp_admin` (the full admin MCP). The legacy `mcp` scope is still accepted as an alias for `mcp_admin`, and every use is WARNed by `LegacyScopeUseLog`. Admin keys stay fully privileged by decision (#62, won't-fix).
- Pluggable authentication (LDAP, database, container, SSO via pac4j)
- Public identity is the **display name** (`users.full_name`, unique, `ProfileNameRules`). Login names show only to their owner and admin surfaces — resolve them through `PublicIdentity`; authorship is stored as the login (`AuthorKeys`). Strip `[{ALLOW …}]` for reader surfaces only via `com.wikantik.auth.acl.AclDirectiveText` — never call `AclDirectives` directly.
- SSO binds on `wikantik.sso.identityClaim` (default `sub`) and never adopts a non-SSO local account of the same name (fails closed without the `sso.subject` marker). The SAML `/sso/callback` is CSRF-exempt by design; login rotates the session; failures redirect to `/login?error=` (a dual-registered SPA route).

### Important Configuration

- **`ini/wikantik.properties` is the complete reference.** Every `wikantik.*` key the code reads is declared there, uncommented, with its explicit default, a description and a `Type:` line (`mcp.*` → `wikantik-mcp.properties`, `tools.*` → `wikantik-tools.properties`). `ConfigSurfaceDriftTest` fails the build on any drift — the violation kinds are in [ProjectReference](docs/developer/ProjectReference.md#configsurfacedrifttest-violation-kinds).
- **Adding a key:** declare it in the file first (section, description, `Type:`, explicit default), then read it in code with the same default. If the default is derived, leave the value blank, add `Blank means:`, and make the reader `isBlank()`-safe.
- **Docs are generated:** `bin/config-reference.sh --write` regenerates `docs/ConfigurationReference.md` and the wiki page `WikantikConfigurationReference`; `ConfigReferenceRegressionTest` fails when they are stale. Never hand-edit them.
- Overrides: `wikantik-custom.properties` (container lib or WEB-INF), cascade files, `-D` system properties. Precedence is documented in the generated reference.
- Security policy: `policy_grants` table (database-backed) or `WEB-INF/wikantik.policy` (file-based fallback). Schema: `bin/db/migrations/V*.sql` only.

### Extension Points

When implementing new features, consider these extension mechanisms:
- **Plugins**: For new wiki markup tags
- **Filters**: For content processing
- **Providers**: For custom storage backends
- **Modules**: For larger feature additions
- **Templates**: For UI customization

### Active Design Documents

Read the linked design doc before touching the relevant subsystem. Detailed "what shipped" status for
each is recorded in [docs/developer/ProjectReference.md](docs/developer/ProjectReference.md#active-design-documents--detailed-status).

- **[StructuralSpineDesign.md](docs/wikantik-pages/StructuralSpineDesign.md)** — structural index (a Page Graph sub-area). `Main.md` is **generated**: edit `docs/wikantik-pages/Main.pins.yaml` (`MainPageRegressionTest` reverts hand-edits). Save-time `canonical_id` enforcement is on (`StructuralSpinePageFilter`).
- **[AgentGradeContentDesign.md](docs/wikantik-pages/AgentGradeContentDesign.md)** — agent-grade content: `type: runbook`, page verification, `/api/pages/for-agent/{id}` projection, derived `agent_hints`, retrieval-quality CI. All six phases shipped 2026-04-25.
- **[HybridRetrieval.md](docs/wikantik-pages/HybridRetrieval.md)** — BM25 + dense (RRF), fail-closed to BM25; backend `wikantik.search.dense.backend = inmemory | pgvector | lucene-hnsw` (**docker1: `lucene-hnsw`**). Levers that worked (recall@12 0.60→0.74): the `ContentChunker` heading-boundary fix and contextual embeddings (`EmbeddingTextBuilder.forDocument` prefixes `Page | Cluster | Section` + summary). **Wikantik never calls a chat model** — `wikantik.genai.mode` is `full|none` (`embeddings-only` is a legacy alias for `full`). **Measured dead, don't retry:** LLM reranker and query decomposition (removed 2.5.15), HyDE, doc2query, KG graph rerank. **Op gotcha:** `inmemory`/`lucene-hnsw` reload after a bulk re-embed only if the run **succeeds**; a failed run leaves hybrid silently BM25-only — tell: bundle `topSimilarity: -1.0`, `confidence: unknown`.
- **[PageGraphVsKnowledgeGraph.md](docs/wikantik-pages/PageGraphVsKnowledgeGraph.md)** — the Page Graph explainer and what replaced each job of the removed Knowledge Graph.
- **[ClusterDeclarationDesign.md](docs/wikantik-pages/ClusterDeclarationDesign.md)** — all 7 phases shipped (2.4.0, ADR-0009). A cluster exists iff exactly one page has `type: hub` + scalar `cluster: <path>`; sub-clusters are `parent/child`, **one level, permanently** (limit lives only in `CLUSTER_SLUG_PATTERN`). Rules:
  - Compare clusters only via `ClusterPath` — **never `startsWith`**. `ClusterPath.memberships(raw)` resolves scalar-vs-list: non-hub pages may list several clusters (`PageDescriptor.cluster()` is the primary, `clusters()` all); a list-valued hub is an ERROR.
  - The one blocking rule (duplicate declaration → 422 in `StructuralSpinePageFilter`) ships **dark**: `wikantik.cluster_declaration.enforcement.enabled=false`. Everything else is a WARNING on `/admin/drift`. `hubs:` and `HubSyncFilter` are deleted; hub `hasPart` is derived from membership.
  - `rename_cluster` / `POST /admin/clusters/rename` rewrites `cluster:` only; unconfirmed = plan, not error; refuses a target another hub declares. MCP tools register **unconditionally** — a new tool must be added to `wikantik-mcp-instructions.txt` **and** `McpProtocolIT.EXPECTED_TOOLS` (else `InstructionsRegistryDriftTest`/`McpInstructionsDriftIT`/`McpProtocolIT` fail).
  - `ClusterStatus.jsx` (PageMeta) is client-side only, gated on `page.permissions.edit`; reads `hub_declared` (Gson omits a null `hub_slug`).
  - **Corpus divergence (read before ANY corpus-wide content work):** `docs/wikantik-pages/` and the prod page store are different corpora — **prod is authoritative, the checkout is a mirror**. Plan from the live index (`list_clusters`/`list_pages_by_filter`), never the repo. Prod pages carry prod-only `**Cluster Hub:**` footers and CRLF line endings — preserve both when pushing repo text (three-way merge from the last release tag, not a blind overwrite). Measure the gap with `CorpusDivergenceCli` (exit 2 = refused, incomplete snapshot; 1 = divergence under `--check`).
- **[RetrievalExperimentHarness.md](docs/wikantik-pages/RetrievalExperimentHarness.md)** — implemented, not yet scheduled.
- **[IndexingSupport.md](docs/admin/IndexingSupport.md)** — raw content + change feed + sitemap for RAG/SEO.
- **[RAG-as-a-Service design](docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md)** — assemble a ranked, de-duplicated, version-pinned-**cited** context bundle, NEVER a synthesized answer (ADR-0001). Logic `com.wikantik.knowledge.bundle.*`; surfaces `GET /api/bundle` + `assemble_bundle`; default source is global dense chunks. `bundleAssemblyService` is wired at the `WikiEngine.patchContextRetrievalService` seam — keep `BundleServiceWiring` off the `getManager` allow-list (DecompositionArchTest R-2). ADRs `docs/adr/0001`–`0007`, glossary `CONTEXT.md`.
- **[Structured page curation](docs/superpowers/specs/2026-06-08-structured-page-curation-design.md)** — the server-authoritative `FrontmatterSchema` validates every save path (REST + MCP). **Gotcha:** field-value checks are WARNINGS (only malformed YAML returns 422), so legacy corpus pages still save; SnakeYAML pre-parses `date`/`audience`.

### Testing Approach

- Unit tests use JUnit 5
- Integration tests use Selenide for browser automation
- Test utilities in `com.wikantik.TestEngine`
- Mock implementations available for most components
- **Database tests**: `@RequiresPostgres` + `PostgresTestDb.createDataSource()`; call `PostgresTestDb.truncate(…)` in `@BeforeEach` (the container is shared — never `DROP`/`ALTER`). Test rollbacks with `FaultInjectingDataSource`. Never hand-write `CREATE TABLE`; write the migration.

### Critical: Integration Test Parallelism

Parallel IT execution is supported ONLY through `bin/run-tests.sh --parallel N`:
its single reactor gives every IT module a build-helper-reserved free port set
and a uniquely-named pgvector container, so they cannot collide. Do NOT bolt
`-T` onto a raw `mvn clean install -Pintegration-tests` invocation yourself —
run-tests.sh is the sanctioned path (it also serializes runs per checkout via a
lock; concurrent Maven builds on one working tree corrupt each other's surefire
state). The opt-in Authentik scim-fullloop suite always runs sequentially and is
never folded into `--parallel`.

The shared embedder is the one resource NOT per-module: run-tests.sh starts a single
container on 11435 for the whole IT phase, so the model loads once regardless of N.
Only `wikantik-it-test-dense` actually uses it. Invoking that module's failsafe run
directly (bare `mvn -pl …`) skips that setup and the suite fails at `@BeforeAll` with
the compose command it wanted — start it by hand or use `bin/run-tests.sh --module dense`.

**Correct usage:**
```bash
# Canonical gate — unit phase + 4-wide parallel IT phase
bin/run-tests.sh --parallel 4

# Sequential IT fallback (e.g. when diagnosing a suspected cross-module issue)
bin/run-tests.sh

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

**Subagents are a standing default on this project — delegate by default, don't ask first.**
This overrides any harness-level "only use subagents when the user requests it" rule: the request
is on file here. When executing a written plan, each task (or independent group of tasks) goes to
its own subagent, dispatched in parallel wherever the tasks don't share files.

- **Don't use subagents for tasks you can do in 1-2 tool calls.** Creating a file, making an edit, running a grep — just do it directly.
- **Don't duplicate work.** If you delegate research to a subagent, don't also run the same searches yourself.
- **Default to the cheapest capable model per subtask** (pass it via the `Agent`/`Task` `model` param — don't ask first; omitting it inherits the expensive session model, which is the wrong default):
  - **haiku** — mechanical, single-file tasks with a complete spec (boilerplate, a TDD unit with the test/impl already written out, a one-line client method, a CSS append).
  - **sonnet** — multi-file integration, pattern-matching against existing code, debugging, and the spec-compliance / code-quality review subagents.
  - **opus** — architecture, planning, and the final cross-cutting review only.
  - Appropriateness is still a per-task judgment (there's no automatic classifier); when genuinely unsure, step up one tier rather than risk a cheap model botching integration work.

### Edits and Commits
- **Accumulate related edits, then build once.** Don't: edit file → build → edit file → build → edit file → build. Do: edit all files → build.
- **Stage specific files by name.** Never use `git add -A` — it picks up untracked junk. List the exact files.

### Planning and Communication
- **Don't narrate what you're about to do.** Just do it. "Let me read the file" followed by a Read call wastes tokens — just call Read.
- **Don't echo back file contents** you just read. Summarize the key finding in one sentence if needed.
- **Don't recap completed work** unless asked. The diff speaks for itself.
- **Keep commit messages to 1-3 lines.** The code change is the documentation.

### Raw MCP calls from scripts
Send `initialize`, take the `Mcp-Session-Id` response header, send `notifications/initialized`, and put
that header on every `tools/call` (without it the request falls into the servlet filters and returns a
raw 400/500). Replies are SSE: JSON-parse the `data:` line and read `result.content[0].text`. Treat a
reply as rate-limited (429) only when it has **no** `data:` line — matching "429" in the body gave a
false retry and a duplicate save. SPARQL needs `PREFIX wk: <https://wiki.wikantik.com/ns/wikantik#>`;
`SELECT` returns `sparql-results-json` (`results.bindings`).

### Formatting LaTeX Math
When writing LaTeX formulas in Wikantik:
- **Inline Math**: Use `$math$` syntax with NO SPACES inside the boundary dollars (e.g. `$x$`, not `$ x $`) — the inline math parser requires content that doesn't start or end with a space, so a spaced form falls back to plain text instead of `<span class="math-inline">`. The rule is Pandoc's
  (`InlineMathRule`, mirrored by the frontend `inlineMath.js`): a closing `$` followed by a digit is not math
  (write `$\times$ 6`, not `$\times$6`), and `\$` inside a formula is a literal dollar sign (`$c = \$1.00$`).
- **Block Math**: You MUST wrap `$$ ... $$` with blank lines before and after, and never wrap the block in `<div>` or `<center>` tags — either bypasses the markdown parser's block-math conversion (the `.math-display` class never gets assigned) and KaTeX silently ignores it. If you place `$$` adjacent to text without blank lines, the markdown parser will treat it as a standard paragraph. This causes two critical rendering defects:
  1. HTML escaping breaks the operators (`=` becomes `&#61;`).
  2. The parser's attribute extension will silently swallow bracketed letters (e.g., `\mathbb{E}` becomes `\mathbb`), destroying the formula.

## Agent skills

### Issue tracker

Issues are tracked as GitHub issues in `github.com/jakefearsd/wikantik` via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles map to their default label strings (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). **Only `wontfix` actually exists on the repo** (verified 2026-08-16) — the other four were never created, so applying one fails. See `docs/agents/triage-labels.md` for the two ways to resolve it.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root (created lazily by `/grill-with-docs`). See `docs/agents/domain.md`.
