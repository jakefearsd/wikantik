---
related:
- WikantikArchitecture
- WikantikKnowledgeGraph
- WikantikEvolutionFromJSPWiki
- TestDrivenDevelopment
- HexagonalArchitecture
- ContinuousIntegration
title: Wikantik Development
cluster: wikantik-development
tags:
- development
- architecture
- tdd
- engineering
- ci
- history
verified_at: '2026-05-04T21:10:44.598011331Z'
verified_by: gemini-cli-mcp-client
canonical_id: 01KQ0P44YWV8Q0JMN1H2H5EGDX
status: active
date: '2026-06-20'
type: article
summary: 'Wikantik platform development: architecture decisions, TDD mandate, CI pipeline,
  Maven module structure, and the feature timeline through 2.4.x.'
---
# Wikantik Development

This cluster documents the development history of the Wikantik platform itself — the architecture decisions, feature implementations, and engineering patterns that have shaped the system over time. The current release is 2.4.57; `CHANGELOG.md` in the repository is the authoritative record of what shipped when.

## Core Engineering Pillars

- **[Test Driven Development (TDD)](TestDrivenDevelopment):** The mandatory TDD-first mandate: a failing test demonstrates every defect before a fix is written.
- **[Hexagonal Architecture](HexagonalArchitecture):** Core wiki logic is decoupled from infrastructure (PostgreSQL, React, MCP) via manager and provider interfaces in `wikantik-api`.
- **[Continuous Integration (CI)](ContinuousIntegration):** Tag-triggered `release.yml` on GitHub Actions builds the WAR (tests are run locally before tagging) and publishes `ghcr.io/jakefearsd/wikantik:<version>`. `quality-gates.yml` runs the static gates, shell tests and OSV scan on every push to `main`; the Java unit suite and coverage floors run only on the weekly schedule or a manual dispatch. The integration tests are **not** run in CI — they are the local pre-commit gate (see Test Strategy below). The nightly retrieval-quality run (nDCG/Recall/MRR) is an in-app scheduled job reporting at `/admin/retrieval-quality`, not a CI job.
- **Database Migrations:** Every schema change ships a numbered idempotent `V<NNN>__description.sql` under `bin/db/migrations/`, applied by `migrate.sh` on every deploy and by the container entrypoint before Tomcat starts. Currently V001–V061; the migrations are the only schema definition.

## Module Structure

Wikantik is a Maven multi-module reactor: 24 modules in the root `pom.xml` `<modules>` list, plus `wikantik-coverage-report` (aggregated JaCoCo report, added only under `-Pcoverage`). `wikantik-frontend` is not a Maven module: `wikantik-war` drives the npm build and bundles the output.

| Module | Role |
|--------|------|
| `wikantik-bom` | Bill of materials pinning shared dependency versions |
| `wikantik-util` | Utility classes and helpers |
| `wikantik-event` | Event system for decoupled component communication |
| `wikantik-jdbc` | The one data-access primitive (`com.wikantik.jdbc.Jdbc`); its test-jar ships `PostgresTestDb`, `@RequiresPostgres` and `FaultInjectingDataSource` |
| `wikantik-api` | Core interfaces — manager contracts, provider interfaces, frontmatter types, KG service, Page Graph interfaces |
| `wikantik-cache` | EhCache-based caching layer (render caches: 1-hour TTL; page, text and history caches: 60 seconds; about 10K entries each) |
| `wikantik-cache-memcached` | Opt-in Memcached adapter for multi-node deployments, not wired into the WAR |
| `wikantik-http` | Servlet filters — CSRF, CSP, cross-origin and other security headers, session-cookie policy |
| `wikantik-ingest` | Derived-page document extraction (Apache Tika + flexmark-html2md), isolated from `wikantik-main` |
| `wikantik-connectors` | External-source connector runtime — seven connector types (six admin-creatable) syncing into derived pages, with DB-backed configs and an encrypted credential store |
| `wikantik-main` | Main implementation — Markdown rendering (Flexmark), providers, auth (JAAS), search, entity extraction, math parser, Obsidian import/export |
| `wikantik-mcp-core` | Shared MCP substrate — `McpTool`, endpoint bootstrap, access filter, and the two tools both MCP servers register. Extracted to break the `wikantik-knowledge` → `wikantik-admin-mcp` module cycle |
| `wikantik-admin-mcp` | Admin MCP server at `/wikantik-admin-mcp` — 29 tools |
| `wikantik-tools` | OpenAPI 3.1 tool server at `/tools/*` — 2 tools for non-MCP clients |
| `wikantik-scim` | SCIM 2.0 provisioning at `/scim/v2/*` |
| `wikantik-knowledge` | Knowledge MCP server at `/knowledge-mcp` — 21 read-only tools when fully wired; also hosts the KG service, hybrid retriever, RAG bundle |
| `wikantik-ontology` | RDF/OWL ontology (Apache Jena + TDB2) — T-Box, projectors, SHACL gate, public SPARQL surface |
| `wikantik-observability` | Health checks, Prometheus metrics, request correlation, and the backpressure and rate-limit filters |
| `wikantik-insights` | Content Intelligence — search-visibility facts, opportunity rules, effect measurement; depends only on `wikantik-jdbc` |
| `wikantik-rest` | REST/JSON API (`/api/*`, 36 servlets) and admin endpoints (`/admin/*`, 29 servlets), plus `SpaRoutingFilter` |
| `wikantik-extract-cli` | Standalone CLIs — entity extractor, derived-page ingester, corpus-divergence check |
| `wikantik-war` | WAR packaging; bundles the React build, wires every servlet and filter, and hosts the architecture and config-drift tests |
| `wikantik-wikipages` | Default wiki pages shipped with a fresh install |
| `wikantik-it-tests` | Integration tests (Selenide, REST, Cargo-launched Tomcat + PostgreSQL + pgvector) |

The module list above matches the root `pom.xml`; [WikantikArchitecture](WikantikArchitecture) explains the layering and dependency rules.

## Test Strategy

- **Unit tests** are JUnit 5 under each module's `src/test`. `TestEngine` builds a real engine for tests, and lightweight in-memory stubs (for example `StubPageManager`) avoid booting one.
- **Database-backed tests run against a real database, not H2.** `@RequiresPostgres` plus `PostgresTestDb.createDataSource()` (from the `wikantik-jdbc` test-jar) give one `pgvector/pgvector:pg18` container per JVM with every migration in `bin/db/migrations/` applied. Without Docker these tests skip locally with a visible reason; CI passes `-Dtests.requireDocker=true` so a missing daemon fails the run. `FaultInjectingDataSource` proves multi-statement writes roll back, and `TestSchemaSingleSourceTest` forbids hand-written `CREATE TABLE` in tests.
- **Integration tests** live under `wikantik-it-tests` and run only under the `integration-tests` profile. The default modules are `rest`, `sso`, `knowledge-disabled`, `custom-jdbc` and `dense` (each with its own Tomcat and pgvector container); `scim-fullloop` is opt-in, and `wikantik-selenide-tests` is shared support code.
- **The canonical pre-commit gate** is `bin/run-tests.sh --parallel 4`: it runs every unit test once, then the default IT modules in one parallel reactor with per-module reserved ports. Anything that can exceed about five minutes runs detached through `bin/agent-build.sh start|status|wait|tail`.
- **Build flags:** use `-DskipTests`, never `-Dmaven.test.skip`, which also skips the `wikantik-main` test-jar the reactor needs.
- **Ratchets:** per-module JaCoCo line-coverage floors (`mvn clean install -Pcoverage -DskipITs`, capped at 0.90), a PMD complexity baseline that only shrinks (`mvn pmd:check -Pcomplexity-gate`), ArchUnit tests (`DecompositionArchTest`, `JdbcAccessArchTest`) and a configuration drift test (`ConfigSurfaceDriftTest`), plus ESLint and Vitest thresholds for the frontend.

## Architecture Evolution

Wikantik began as a fork of Apache JSPWiki. Through a sustained modernization effort starting March 2026, the platform was transformed:

- **Frontend:** JSP templates replaced with a React SPA (Vite + React Router), served at `/`
- **Storage:** File-based providers augmented with PostgreSQL for users, groups, permissions, audit log, and the Knowledge Graph; pgvector for dense embeddings; all database access through the `wikantik-jdbc` primitive
- **Security:** XML policy files migrated to database-backed `policy_grants` table with admin UI; SCIM 2.0 provisioning added at `/scim/v2/*`; JAAS-based SSO (OIDC + SAML via pac4j) with session fixation defense and SameSite=Lax cookies; scoped `/admin/*` grants; MCP API-key scopes `mcp_read` and `mcp_admin`
- **AI Integration:** Two dedicated MCP servers — `/wikantik-admin-mcp` (29 write/analytics tools) and `/knowledge-mcp` (21 read-only retrieval + ontology tools when fully wired) — plus an OpenAPI 3.1 tool server at `/tools/*`
- **Retrieval:** Hybrid BM25 + dense (Lucene HNSW / pgvector) via RRF; chunk-level contextual embeddings; RAG-as-a-Service context bundle at `GET /api/bundle` + `assemble_bundle` MCP
- **Ontology:** RDF/OWL ontology layer (`wikantik-ontology`) — 9 entity + 5 content classes, SHACL write-time gate, public SPARQL endpoint, JSON-LD SEO re-sourced from the ontology
- **Content Intelligence:** `wikantik-insights` closes a feedback loop from search-visibility data to a ranked opportunity backlog and measured effect
- **Authoring:** Obsidian-style editing — Live Preview, native `[[wikilinks]]` and `![[embeds]]`, vault import and export
- **Observability:** Health checks, Prometheus `/metrics`; monitoring handled by the external **jakemon** stack (Grafana Alloy + central Prometheus + Loki + Grafana) on host `docker2`

## Key Design Decisions

1. **[ADR-001: Extract Manager Interfaces to API](ADR-001)** — Moves the manager interfaces into `wikantik-api` so the MCP modules compile against contracts rather than the engine implementation. The repository's ADR index (`docs/adr/`) records this as ADR-0000 and continues with 0001–0010.
2. **Frontmatter as Source of Truth** — Wiki page YAML frontmatter is the canonical source for cluster membership, tags, type, audience, verification, and SEO metadata. The server-authoritative `FrontmatterSchema` drives live validation and Save-gating in the editor.
3. **Page Graph vs Knowledge Graph** — Two strictly separate subsystems. The Page Graph tracks real wikilink edges; the Knowledge Graph tracks LLM-extracted entities and relations. See [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph).
4. **Fail-closed retrieval** — The hybrid retriever degrades to BM25 when the embedding service (Ollama) is unavailable; the context bundle dense source uses Lucene HNSW in-process (the docker1 production default) so there is no mandatory external network dependency for search.
5. **Cluster taxonomy is a frontmatter projection, not a filesystem hierarchy** (ADR-0009) — a path in frontmatter is data: validatable, re-projectable, multi-valued and revertable per page, where a path in the filesystem would bind page identity, slug history, URLs and wikilinks to one axis.
6. **One way to touch the database** (ADR-0010) — `wikantik-jdbc` is the only data-access primitive, enforced by `JdbcAccessArchTest`.
7. **Late-bound service registration** (ADR-0008) — new services register through `engine.setManager(...)` from the owning wiring helper; `WikiEngine` accepts no new `getManager` callers or late-bound fields.

## Feature Timeline

| Date | Feature |
|------|---------|
| 2026-03-23 | Test stub conversion for decoupled testing |
| 2026-03-28 | JSP → React SPA migration |
| 2026-04-03 | User profile management, attachment handling |
| 2026-04-04 | Knowledge Graph core — entity extraction, `kg_*` tables, pgvector embeddings |
| 2026-04-25 | Agent-grade content layer — `type: runbook`, page verification, `get_page_for_agent`, retrieval-quality CI |
| 2026-05-02 | Structural spine — `canonical_id`, `cluster:`, `list_clusters`/`list_tags`/`list_pages_by_filter`; typed `relations:` frontmatter removed |
| 2026-05-30 | Session stability — SameSite=Lax auth cookies, remember-me re-auth filter |
| 2026-06-05 | SCIM deactivation auth guard; auth lock enforcement per-login-module |
| 2026-06-08 | RDF/OWL ontology layer — T-Box, SHACL gate, public SPARQL/JSON-LD/dumps, SEO `@type` re-sourced from ontology |
| 2026-06-09 | Structured frontmatter editor — live validation, Save-gating, page-scoped KG curation panel |
| 2026-06-14 | RAG-as-a-Service Phase 1 — context bundle (`GET /api/bundle`, `assemble_bundle` MCP), derived pages (Tika ingest), chunker heading-fidelity fix, contextual embeddings |
| 2026-06-19 | Bundle hybrid source (BM25+dense RRF default-on); admin-MCP reconciled to 26 tools; `list_retrieval_queries` added |
| 2026-07-05 | Context briefing — `get_briefing` MCP tool + `GET /api/briefing` for session-start agent context |
| 2026-07-15 | Connector framework + admin UI — seven connector types, DB-backed configs with hot-apply, encrypted credential store, guided add wizard |
| 2026-08-15 | Cluster declaration (ADR-0009) — the hub page declares its cluster, scalar-or-list multi-membership, segment-aware `ClusterPath` matching, `rename_cluster` bulk curation; admin-MCP 26 → 27 tools (2.4.0) |
| 2026-08-17 | Content Intelligence loop closes (2.4.6) — `wikantik-insights` opportunity backlog and nightly effect measurement; admin-MCP 27 → 29 tools (`list_content_opportunities`, `snooze_opportunity`); scoped `/admin/*` grants (2.4.8) |
| 2026-08-19 | Security sweep (2.4.14–2.4.17) — an unauthenticated admin-data disclosure and an attachment-filename file write fixed (2.4.14), public SPARQL SSRF and restricted-metadata leaks (2.4.15), `JDBCPlugin` disabled by default (2.4.16), connector SSRF egress policy (2.4.17) |
| 2026-08-20 | MCP API-key scope split into read-only `mcp_read` and admin (2.4.18; the admin scope is renamed `mcp_admin` in 2.4.54, 2026-10-06) |
| 2026-09-07 | `wikantik-jdbc` — one data-access primitive (ADR-0010); migrations become the only schema definition and database tests run on a real pgvector container; every configuration key declared in `ini/wikantik.properties` (2.4.20) |
| 2026-09-15 | SHA-1 password formats removed; per-module JaCoCo coverage floors (2.4.24) |
| 2026-09-18 | PostgreSQL 18 with pgvector 0.8.6 in production; test containers move to `pgvector/pgvector:pg18` (2.4.25) |
| 2026-09-20 | KG entities extracted from ACL-restricted pages no longer reach the public ontology; connector credentials bound to their endpoint host (2.4.26) |
| 2026-09-22 | `wikantik.search.embedding.profile` (`cpu` or `gpu`) selects the embedding hardware path (2.4.27) |
| 2026-10-01 | Editor workspace — quick switcher, command palette, live link completion, paste/drop uploads; `GET /api/export` Obsidian vault zip (2.4.29) |
| 2026-10-03 | Obsidian-style Live Preview editor, daily notes, zipped-vault import, native `[[wikilinks]]` and `![[embeds]]` (2.4.50) |
| 2026-10-05 | Login-only authorization — the bootstrap admin override requires a login session, policy grants match by principal type, ACL user entries resolve by login name; JSPWiki-era default pages removed (2.4.53) |
| 2026-10-12 | PostgreSQL JDBC driver 42.7.14 (2.4.56) |
| 2026-10-13 | SCIM refuses every write to the `Admin` group; `/api/users/mentionable` needs a sign-in; the audit Actor filter works and admin API-key revocations are audited; KG `new-node` approval is first-writer-wins and `--reset-kg` spares human-approved nodes; the standalone mail session applies STARTTLS and 5 s timeouts; privacy policy rewritten (2.4.57, the current release) |

## See Also

- [WikantikArchitecture](WikantikArchitecture) — detailed module breakdown
- [WikantikKnowledgeGraph](WikantikKnowledgeGraph) — semantic network documentation
- [WikantikEvolutionFromJSPWiki](WikantikEvolutionFromJSPWiki) — the history from JSPWiki fork to Wikantik
- [ContinuousIntegration](ContinuousIntegration) and [WikantikReleaseAndDeployProcess](WikantikReleaseAndDeployProcess) — CI workflows, cutting a release, deploying it

## Wikantik Core Internals & Tools

- [AdvancedSearchTermEngineering](AdvancedSearchTermEngineering) — Query engineering for RAG systems, beyond basic semantic search.
- [AttachmentManagement](AttachmentManagement) — Drag-and-drop attachment UI with server-side image processing and inline Markdown references.
- [ContentFeedback](ContentFeedback) — How publishers receive AI-generated content-quality feedback.
- [CurationTest](CurationTest) — The curation test suite: frontmatter schema, link integrity and KaTeX math checks for curators.
- [DockerAdminSmallNetworks](DockerAdminSmallNetworks) — Managing a handful of containers on a small network.
- [FullOAuth](FullOAuth) — The OAuth SSO implementation plan.
- [McpServerCritique2026](McpServerCritique2026) — Critique of the Admin and Knowledge MCP servers from active use and robustness/security testing.
- [MvnCheatSheet](MvnCheatSheet) — Maven commands used in Wikantik development: build, test, run and release.
- [GraphRerankStep](GraphRerankStep) — Historical: how the graph-proximity rerank worked; it was removed in 2026-07 after measuring no retrieval lift.
- [ApiDocumentation](ApiDocumentation) — General guide to API documentation with OpenAPI 3.1 and documentation-as-code.
- [ContentNegotiation](ContentNegotiation) — General guide to HTTP content negotiation: Accept headers and q-values.

## Wikantik Engine Infrastructure & Tools

- [WikiAuditSkill](WikiAuditSkill) — Automated broken-link detection, frontmatter validation and orphan discovery.
- [WikiInternationalization](WikiInternationalization) — General guidance on UI and content internationalization for wikis.
- [WikiToMarkdownConverter](WikiToMarkdownConverter) — Converting legacy JSPWiki markup to Markdown.
- [NewUI](NewUI) — The original Reader UI implementation plan for the React SPA.
- [RemoveJspAndAppPrefix](RemoveJspAndAppPrefix) — Removal of the JSP templates and the `/app/` URL prefix.
- [SearchIndexAndKnowledgeGraphRebuild](SearchIndexAndKnowledgeGraphRebuild) — Operator runbook for the four-phase rebuild of chunks, Lucene, embeddings and entity extraction.
- [UserProfileBio](UserProfileBio) — The Markdown-enabled user biography feature.

## Compliance & Search Discovery

- [PrivacyPolicy](PrivacyPolicy) — The site privacy policy page.
- [Sitemap](Sitemap) — The `sitemap.xml` servlet.
- [SitemapOptimization](SitemapOptimization) — Research notes on the ideal `sitemap.xml` output for Google Search.
