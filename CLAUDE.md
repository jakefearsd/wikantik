# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
The preferred development approach is test-driven development: put a test in place to demonstrate a
defect before repairing it, so we know the test actually detects the issue.

- Sole developer on this repo — work directly on main, no feature branches or PRs.
- Never swallow exceptions with empty catch blocks — always log at least a `LOG.warn()` with context and message.
- Don't use plan mode for trivial tasks (git add, single commands) — just do them directly.

Operational runbooks (container/remote deployment, load testing, entity extractor) and the detailed
design-doc status blocks live in **[docs/ProjectReference.md](docs/ProjectReference.md)** — kept out
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
mvn clean install -DskipTests -T 1C

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
mvn clean install -DskipTests -T 1C
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
and while we recognize it is not a perfect measurement, it is one we choose to pursue.

> Container/remote deployment, load testing, and the entity-extractor runbook moved to
> [docs/ProjectReference.md](docs/ProjectReference.md).

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
- **wikantik-knowledge**: Knowledge MCP server at `/knowledge-mcp` — 18 read-only tools (hybrid retrieval, Knowledge Graph traversal, schema discovery, structural-spine navigation, agent-grade page projection, batched markdown reads via `read_pages`, plus **`get_ontology`** (formal T-Box) and **`sparql_query`** (read-only SPARQL over the ontology)) plus the Knowledge Graph service (pgvector-backed embeddings, co-mention graph, hub discovery) and the ontology-aware query expansion in the hybrid retriever (flag `wikantik.search.ontologyExpansion.enabled`, default off). See `com.wikantik.knowledge.mcp.KnowledgeMcpInitializer`.
- **wikantik-tools**: OpenAPI 3.1 tool server at `/tools/*` — 2 tools (`search_wiki`, `get_page`) for OpenWebUI-compatible non-MCP clients.
- **wikantik-scim**: SCIM 2.0 provisioning server at `/scim/v2/*` — bearer-authed `Users` + `Groups` CRUD + discovery (`ServiceProviderConfig`/`Schemas`/`ResourceTypes`) for IdP-driven onboarding/offboarding. User decommission routes through the unified `UserLifecycleService`; Group membership sync routes through `GroupManager`. SCIM Groups never grant the Admin role.
- **wikantik-extract-cli**: Standalone entity-extractor CLI (offline/batch extraction against the Knowledge Graph pipeline)
- **wikantik-ontology**: RDF/OWL ontology layer (Apache Jena) — the `wikantik:` T-Box (`wikantik.ttl`: 9 entity + 5 content classes, the 21 KG predicates with domain/range + public mappings to schema.org/SKOS/Dublin Core/PROV-O, SKOS concept scheme) + SHACL shapes; Postgres→RDF projectors (Entity/Edge/Page/Concept) and a TDB2-backed `OntologyModelManager` with RDFS `subClassOf` inference. Depends only on `wikantik-api` + Jena. Runtime wiring (`OntologyRebuildCoordinator`, `/admin/ontology/rebuild`, startup-if-empty) lives in `wikantik-main` (`com.wikantik.ontology.runtime`). **Event-incremental sync:** `OntologyEventListener` re-projects a page's graph (+ its concept graphs) on save/rename and removes it on a true delete (rename-vs-delete disambiguated via `canonical_id` liveness); a nightly `OntologyRebuildScheduler` reconciles **entity** graphs (no KG-change events exist). **Public read surface** (wikantik-rest, `PublicRdfServletBase`): `/sparql` (read-only SELECT/ASK/CONSTRUCT, UPDATE rejected, result cap + timeout), `/id/{type}/{id}` (JSON-LD/Turtle dereferencing), `/export/{ontology.ttl,graph.nt}` (dumps) — all public + permissive-CORS, behind a **public/restricted ACL split**: `PublicProjectionFilter` + a request-free guest-session view check (`WikiSession.guestSession` + `PermissionFilter`) keep restricted pages/entities/edges out of the materialized dataset entirely. Config: `wikantik.ontology.enabled` (default true), `wikantik.ontology.tdb2.dir` (default `${wikantik.workDir}/ontology-tdb2`), `wikantik.ontology.rebuild.interval.hours` (default 24, 0=disabled). **Write-time SHACL gate (Phase 5b):** `OntologyShaclValidator.validateEdge` is threaded by `KnowledgeSubsystemFactory` (one shared instance) into both KG write chokepoints — `DefaultKgCurationOps.tryUpsertEdge` *refuses* a non-conformant edge citing the violated shape, and `KgMaterializationService` *skips + logs + counts* one on the machine path (`skippedNonConformantCount()`); the gate is **narrow** (only the 2 shaped predicates today — `implements`, `located_in`) and null-safe (degrades to no-op). **9-class entity vocabulary:** both extractors (chunk + page) emit only the canonical `EntityTypeVocabulary.ENTITY_CLASSES` (wikantik-api: person/organization/place/event/product/technology/concept/project/version) — prompts generated from it, parsers lowercase + allowlist to it (default `concept`); an `EntityTypeVocabularyDriftTest` ties it to `NodeTypeMapping`. **SEO↔ontology (Phase 6):** `SemanticHeadRenderer` re-sources the page JSON-LD schema.org `@type` from `NodeTypeMapping.schemaOrgType` (hub→CollectionPage, article→Article, runbook→HowTo, design→TechArticle, else Article — upgrade-only, never downgrades) and adds a `sameAs` to the page's `/id/page/{canonical_id}` ontology IRI; `SemanticHeadOntologyAgreementTest` asserts the rendered `@type` is among the ontology's inferred schema.org types for the page (the two faces of one model can't silently drift). It deliberately does **not** re-source the whole JSON-LD from the runtime graph (would drop SEO-only shapes — BreadcrumbList/SearchAction/hasPart — and couple a pure SSR function to TDB2). Design: `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md`.
- **wikantik-observability**: Health checks, Prometheus metrics, request correlation
- **wikantik-frontend**: React SPA (Vite build) — reader, editor, admin panel, Knowledge Graph viewer, Page Graph viewer
- **wikantik-war**: WAR packaging — bundles the React build and wires all servlets/filters
- **wikantik-wikipages**: Default wiki pages shipped with a fresh install
- **wikantik-it-tests**: Integration tests (Selenide browser automation, REST API, Cargo-launched Tomcat against PostgreSQL + pgvector)

#### Agent-facing surface summary

| Endpoint | Module | Protocol | Tools | Auth |
|----------|--------|----------|-------|------|
| `/wikantik-admin-mcp` | wikantik-admin-mcp | MCP (Streamable HTTP) | 25 write/analytics tools (incl. KG curation + admin-bypass reads + orphan-listing) | `McpAccessFilter` (bearer token / API key) |
| `/knowledge-mcp` | wikantik-knowledge | MCP (Streamable HTTP) | 18 read-only retrieval + Knowledge Graph + Page Graph structural-spine + agent-projection + batched-read tools (incl. `get_ontology` + `sparql_query`) | `KnowledgeMcpAccessFilter` (same scheme) |
| `/tools/*` | wikantik-tools | OpenAPI 3.1 | 2 tools (`search_wiki`, `get_page`) | API key |
| `/scim/v2/*` | wikantik-scim | SCIM 2.0 | `Users` (CRUD, PATCH active, soft-delete) + `Groups` (CRUD, membership PATCH, hard delete) + discovery | `ScimAccessFilter` (bearer `wikantik.scim.token`) |
| `/api/*` | wikantik-rest | REST/JSON | 24 Resource classes | `RestServletBase.checkPagePermission()` (ACL + policy grants) |
| `/admin/*` | wikantik-rest | REST/JSON | 11 admin resources (incl. `/admin/kg-policy/*`, the tamper-evident `/admin/audit*` log, and `/admin/ontology/*`: rebuild + status + SHACL-conformance violations) | `AdminAuthFilter` (`AllPermission`) |
| `/wiki/{slug}?format=md\|json` | wikantik-rest | HTTP | Raw content for RAG ingestion / crawlers | Public — view ACL **enforced** (WikiPageFormatFilter gates on the caller's session; 404 hides restricted pages) |
| `/api/changes?since=…` | wikantik-rest | REST/JSON | Incremental change feed for sync pipelines | Public |
| `/sparql`, `/id/{type}/{id}`, `/export/*.ttl\|.nt` | wikantik-rest | RDF (SPARQL/JSON-LD/Turtle/N-Triples) | Public read-only ontology: SPARQL query, per-resource dereferencing, full dumps | Public, read-only, permissive CORS — **public/restricted ACL split** (only anonymously-viewable pages/entities are materialized, so restricted content cannot be queried) |

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

Read the linked design doc before touching the relevant subsystem. Detailed "what shipped" status for
each is recorded in [docs/ProjectReference.md](docs/ProjectReference.md#active-design-documents--detailed-status).

- **[StructuralSpineDesign.md](docs/wikantik-pages/StructuralSpineDesign.md)** — machine-queryable structural index (a **Page Graph** sub-area). Load-bearing gotchas: `Main.md` is **generated** — edit `docs/wikantik-pages/Main.pins.yaml`, never `Main.md` (CI's `MainPageRegressionTest` will revert hand-edits); save-time `canonical_id` enforcement is **on** (`StructuralSpinePageFilter`). All four phases shipped.
- **[AgentGradeContentDesign.md](docs/wikantik-pages/AgentGradeContentDesign.md)** — agent-grade content: `type: runbook`, page verification, `/api/pages/for-agent/{id}` projection, derived `agent_hints`, retrieval-quality CI. All six phases shipped 2026-04-25.
- **[HybridRetrieval.md](docs/wikantik-pages/HybridRetrieval.md)** — BM25 + dense + KG rerank, fail-closed BM25 fallback. Dense backend `wikantik.search.dense.backend = inmemory | pgvector | lucene-hnsw`; **`lucene-hnsw` is the docker1 default**.
- **[PageGraphVsKnowledgeGraph.md](docs/wikantik-pages/PageGraphVsKnowledgeGraph.md)** — canonical Page Graph (wikilink edges) vs Knowledge Graph (LLM entities) explainer. Read before touching either.
- **[KgInclusionPolicy.md](docs/wikantik-pages/KgInclusionPolicy.md)** — cluster-primary KG inclusion policy (`bin/kg-policy.sh`, default-exclude, `kg_include:` frontmatter override).
- **[RetrievalExperimentHarness.md](docs/wikantik-pages/RetrievalExperimentHarness.md)** — implemented, not yet scheduled.
- **[IndexingSupport.md](IndexingSupport.md)** — raw content + change feed + sitemap for RAG/SEO.

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
