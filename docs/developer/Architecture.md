# Wikantik Architecture

This page is for developers who need a map of the code before changing it: which module owns what, which way dependencies point, how the core objects fit together, and where the three graph-like subsystems differ. It is the one place that states module and servlet counts. Counts below are as of 2.4.53 and are derived from the root `pom.xml` and `wikantik-war/src/main/webapp/WEB-INF/web.xml`.

## Read the module map

The root `pom.xml` declares 24 modules in its default `<modules>` list. A 25th, `wikantik-coverage-report`, is added only under the `coverage` profile. `wikantik-frontend` is not a Maven module: `wikantik-war` builds it with npm (see [Building.md](Building.md)).

| Module | What it holds |
|--------|---------------|
| `wikantik-bom` | Bill of materials pinning shared dependency versions |
| `wikantik-util` | Utility classes with no wiki dependencies |
| `wikantik-event` | The event system used for decoupled communication |
| `wikantik-jdbc` | The one data-access primitive (`com.wikantik.jdbc.Jdbc`) and the test-jar with `PostgresTestDb` |
| `wikantik-api` | Manager interfaces, frontmatter, page save, Knowledge Graph and Page Graph contracts |
| `wikantik-cache` | EhCache-based caching layer |
| `wikantik-cache-memcached` | Opt-in Memcached adapter, not wired into the WAR |
| `wikantik-http` | Servlet filters: CSRF, CSP, cross-origin and other security headers, session-cookie policy |
| `wikantik-ingest` | Document extraction (Tika, HTML to Markdown) for derived pages, isolated from `wikantik-main` |
| `wikantik-connectors` | External-source connector runtime (filesystem, web crawler, sitemap, feed, Google Drive, GitHub, Confluence) |
| `wikantik-insights` | Content Intelligence: search-visibility facts, opportunity rules, effect measurement |
| `wikantik-ontology` | RDF/OWL ontology layer on Apache Jena: T-Box, SHACL shapes, projectors, TDB2 store |
| `wikantik-main` | The engine: rendering, providers, auth, search, references, entity extraction, import and export, runtime wiring |
| `wikantik-mcp-core` | Shared MCP substrate: tool base classes, access filter, endpoint bootstrap |
| `wikantik-admin-mcp` | Admin MCP server at `/wikantik-admin-mcp` (`McpServerInitializer`) |
| `wikantik-knowledge` | Knowledge MCP server at `/knowledge-mcp` (`KnowledgeMcpInitializer`) and the Knowledge Graph service |
| `wikantik-tools` | OpenAPI tool server at `/tools/*` |
| `wikantik-scim` | SCIM 2.0 provisioning at `/scim/v2/*` |
| `wikantik-observability` | Health checks, Prometheus metrics, request correlation |
| `wikantik-rest` | REST API (`/api/*`) and admin endpoints (`/admin/*`), plus `SpaRoutingFilter` |
| `wikantik-extract-cli` | Standalone command-line tools: entity extractor, corpus divergence, KG policy, derived-page ingest |
| `wikantik-war` | WAR packaging; bundles the React build and wires servlets and filters; hosts the ArchUnit and config-drift tests |
| `wikantik-wikipages` | Default wiki pages shipped with a fresh install |
| `wikantik-it-tests` | Integration tests (see [Testing.md](Testing.md)) |

Tool counts for the MCP servers are owned by the admin MCP documentation, not here.

## Follow the dependency rules

These come from each module's `pom.xml`; break them and the build, or an architecture test, fails.

- `wikantik-insights` depends only on `wikantik-jdbc`. It depends on neither `wikantik-api` nor `wikantik-main`, so the rule engine is a pure function of rows, page facts and config. Page state reaches it through a `PageFacts` port that an adapter in `wikantik-main` implements.
- `wikantik-jdbc` depends on no other Wikantik module. It is the only way to touch the database: `JdbcAccessArchTest` in `wikantik-war` forbids `DataSource.getConnection`, `DriverManager.getConnection` and the `Connection` statement and transaction methods outside `com.wikantik.jdbc..`. The one carve-out is `JDBCPlugin`. See [ADR-0010](../adr/0010-one-data-access-primitive.md).
- `wikantik-ontology` depends only on `wikantik-api` plus Jena. Its runtime wiring (`OntologyRebuildCoordinator`) lives in `wikantik-main` under `com.wikantik.ontology.runtime`.
- `wikantik-mcp-core` holds what both MCP servers share, so `wikantik-knowledge` depends on `wikantik-mcp-core` and not on `wikantik-admin-mcp`. That broke a module cycle.
- `wikantik-ingest` depends on no Wikantik module, which keeps the PDFBox and POI dependencies out of `wikantik-main`.
- `wikantik-main` is not allowed to grow new `WikiEngine#getManager` callers or late-bound `mgr_*` fields. `DecompositionArchTest` enforces this; register services with `engine.setManager(Type.class, impl)` from the owning `*WiringHelper` ([ADR-0008](../adr/0008-late-bound-service-registration.md)). The test's ArchUnit freeze store changes even when the test fails, so restore it from git before you retry.

## Know the core components

- `WikiEngine` (`com.wikantik.WikiEngine`) is the central orchestrator and one instance per web application.
- `WikiContext` is the request-scoped object: current page, session, request state.
- `WikiSession` holds authentication state and principals and integrates with JAAS.
- Managers: each subsystem has an interface (in `wikantik-api`) and a default implementation, for example `PageManager`, `AttachmentManager`, `PluginManager`, `FilterManager`, `SearchManager`, `RenderingManager`.
- Providers abstract storage (`PageProvider`, `AttachmentProvider`, `SearchProvider`).
- Events (`wikantik-event`) let components react to page changes and user actions without direct references.
- Commands model UI actions; `CommandResolver` maps URLs to commands.

## Follow the rendering pipeline

1. `MarkdownParser` (`com.wikantik.parser.markdown`) converts Markdown to a Flexmark AST.
2. Page filters run before and after rendering.
3. Plugins insert dynamic content through `[{Plugin}]` syntax.
4. `MarkdownRenderer` (`com.wikantik.render.markdown`) produces HTML.

Render caches skip viewer-dependent output: a render that consults the caller's session sets `Context.VAR_VIEWER_SENSITIVE` and stays out of the shared caches.

## Keep the three edge types apart

| Subsystem | Nodes and edges | Code | Surfaces |
|-----------|-----------------|------|----------|
| Page Graph | Pages; edges are real wikilinks parsed from page bodies. `canonical_id` and `cluster:` live beside it | `com.wikantik.pagegraph.*`, `com.wikantik.api.pagegraph` | `/page-graph`, `/admin/page-graph/*` |
| Knowledge Graph | LLM-extracted entities; co-mention or typed-relation edges | `com.wikantik.knowledge.*`, `wikantik-knowledge`, `kg_*` tables | `/admin/knowledge-graph/*`, `/knowledge-mcp` |
| Citation edges | Derived: `cite://` claims in a source page grounded in a target section, parsed at save into the `citations` table | `com.wikantik.citation.*` | `/admin/drift/citations`, `list_stale_citations` |

Do not use the bare word "graph" in identifiers; say Page Graph, Knowledge Graph, `kg_*` or `pagegraph`. The long explanation is in [PageGraphVsKnowledgeGraph](../wikantik-pages/PageGraphVsKnowledgeGraph.md).

## Count the HTTP surface

Counted from `web.xml` as of 2.4.53:

| Prefix | url-patterns | Distinct servlet classes |
|--------|--------------|--------------------------|
| `/api/*` | 38 | 36 |
| `/admin/*` | 37 | 29 |

`web.xml` declares 78 servlets and 91 servlet mappings in total. Recount with a script when you change it; do not edit these numbers without recounting.

## Find the design decisions

- ADRs live in `docs/adr/`; see [the ADR index](../adr/README.md). `CONTEXT.md` at the repository root is the domain glossary.
- [StructuralSpineDesign](../wikantik-pages/StructuralSpineDesign.md) describes the machine-queryable structural index. `Main.md` is generated from `Main.pins.yaml`; edit the pins file, never `Main.md`.
- [ADR-0009](../adr/0009-cluster-taxonomy-is-frontmatter-projection-not-filesystem-hierarchy.md) records that a cluster is a frontmatter projection, not a directory tree.
- Schema changes need a numbered migration under `bin/db/migrations/` (`V<NNN>__description.sql`, idempotent, DDL only); see `bin/db/migrations/README.md`.
- Configuration keys are declared in `wikantik-main/src/main/resources/ini/wikantik.properties`, and `ConfigSurfaceDriftTest` in `wikantik-war` fails the build on drift.
