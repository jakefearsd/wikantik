---
cluster: wikantik-development
type: reference
tags:
- wikantik
- readme
- getting-started
- architecture
- jspwiki
title: Wikantik README
canonical_id: 01KQ0P44V3CF88SP9QA9VGC4W7
summary: Wikantik is a Markdown wiki engine forked from Apache JSPWiki, with hybrid
  retrieval, a Knowledge Graph and MCP servers for agents.
status: active
date: '2026-10-13'
---
# Wikantik

Wikantik is a Markdown-native knowledge base for people and AI agents. It is a Java wiki engine that stores every page as Markdown with YAML frontmatter, serves a React single-page app and a REST API to humans, and exposes two Model Context Protocol (MCP) servers and an OpenAPI tool server to agents. Search is hybrid (Lucene BM25 fused with dense vectors), and the same content is also projected into a Page Graph, an LLM-extracted Knowledge Graph and a queryable RDF/OWL ontology.

Wikantik is a fork of **Apache JSPWiki**. The project started in early 2026 as an experiment to see whether a legacy enterprise wiki could become an agent-ready knowledge base. Most of the visible surface has since been replaced (JSP rendering became a React app, XML security policy became database-backed grants, and the knowledge layer was added), while the page-provider foundations, the plugin and filter interfaces and the Lucene core remain. The full story is in [History and Evolution: From JSPWiki to Wikantik](WikantikEvolutionFromJSPWiki). The repository directory is still called `jspwiki`; the product, the Maven modules and the Java packages are all named Wikantik.

## What it does

- **Markdown wiki with frontmatter.** Pages are CommonMark Markdown with YAML frontmatter (`type`, `tags`, `cluster`, `summary`, `canonical_id` and more), wikilinks, Obsidian-style embeds and LaTeX math. Frontmatter is validated on every save path and also drives SEO metadata, JSON-LD, feeds and the text that gets embedded for retrieval. See [Text Formatting Rules](TextFormattingRules) for the markup.
- **Clusters.** A hub page declares a cluster, and other pages join it through the `cluster:` frontmatter field (one or several memberships).
- **Page Graph.** The graph of real page-to-page wikilinks, with rename-stable `canonical_id` identifiers. It is deliberately kept separate from the Knowledge Graph; see [Page Graph vs Knowledge Graph](PageGraphVsKnowledgeGraph).
- **Knowledge Graph.** Entities and relations extracted from page content by an LLM, stored in `kg_*` tables with pgvector embeddings, a reviewer queue for proposals, and a cluster-based inclusion policy.
- **RDF/OWL ontology.** An Apache Jena ontology with SHACL shapes, projected from the database and served read-only and public at `/sparql`, `/id/{type}/{id}` and `/export/*`, with restricted pages kept out of the public dataset.
- **Hybrid retrieval.** Lucene BM25 and dense vector search (pgvector or Lucene HNSW) fused by reciprocal rank fusion, with a fail-closed fallback to BM25 when the embedder is unavailable. See [Hybrid Retrieval](HybridRetrieval).
- **Cited context bundle.** `GET /api/bundle?q=` (and the `assemble_bundle` MCP tool) returns ranked, de-duplicated, version-pinned sections with citations. It never synthesizes an answer. `GET /api/briefing` produces a session-start briefing for coding agents. See [RAG Context Bundle](WikantikRagContextBundle).
- **Citations.** Inline `cite://` markup is parsed at save time into version-pinned, span-hashed citation edges so stale quotes can be found and fixed.
- **MCP servers for agents.** An admin endpoint at `/wikantik-admin-mcp` (write, curation and analytics tools) and a read-only endpoint at `/knowledge-mcp` (retrieval, Knowledge Graph, ontology and structure tools). Tool lists live in [MCP Integration](McpIntegration), which is the source of truth for counts.
- **OpenAPI tool server.** `/tools/*` exposes `search_wiki` and `get_page` to OpenWebUI-style clients that do not speak MCP.
- **Identity and access.** Database-backed policy grants and groups, page-level ACLs, SSO (OIDC and SAML), SCIM 2.0 provisioning at `/scim/v2/*`, scoped API keys, and a tamper-evident audit log. See [Single Sign-On](WikantikSingleSignOn), [SCIM Provisioning](WikantikScimProvisioning) and [API Keys](WikantikApiKeys).
- **Connectors.** External sources (filesystem, web crawler, sitemap, RSS/Atom feed, Google Drive, GitHub, Confluence) sync into machine-managed derived pages, and documents can be ingested through `POST /api/ingest`. See [Connectors](WikantikConnectors).
- **Import, export and feeds.** Obsidian vault import and export, raw content at `/wiki/{slug}?format=md|json`, and an incremental change feed at `/api/changes?since=`.
- **Operations.** Health at `/api/health`, Prometheus metrics at `/metrics`, structured logs, an admin panel at `/admin/`, and a `wikantik.genai.mode` setting (`full`, `embeddings-only` or `none`) that caps LLM spend.

## Module map

The root `pom.xml` lists these Maven modules in its default reactor. The React frontend lives in `wikantik-frontend`, which is not a Maven module: `wikantik-war` runs its npm build and bundles the output. A further module, `wikantik-coverage-report`, is added only under `-Pcoverage`.

| Module | What it is |
|--------|------------|
| `wikantik-wikipages` | Default wiki pages shipped with a fresh install. |
| `wikantik-util` | Shared helpers and crypto utilities. |
| `wikantik-jdbc` | The one data-access primitive (`Jdbc`, `JdbcSupport`); its test-jar provides the pgvector test database. |
| `wikantik-event` | The decoupled `WikiEvent` bus. |
| `wikantik-api` | Public contracts: manager interfaces, frontmatter, Page Graph, Knowledge Graph and bundle types. |
| `wikantik-cache` | EhCache-based caching layer. |
| `wikantik-cache-memcached` | Opt-in Memcached adapter for multi-node deployments; not wired into the WAR. |
| `wikantik-http` | Servlet filters: CSRF, CORS, CSP, security headers, session-cookie policy. |
| `wikantik-ingest` | Apache Tika document extraction for derived pages, isolated from the engine. |
| `wikantik-connectors` | External-source connector runtime and sync orchestration. |
| `wikantik-main` | The engine: `WikiEngine`, rendering, providers, auth, search, entity extraction, Obsidian import. |
| `wikantik-mcp-core` | Shared MCP substrate: tool interface, endpoint bootstrap, access filter. |
| `wikantik-admin-mcp` | Admin MCP server at `/wikantik-admin-mcp`. |
| `wikantik-tools` | OpenAPI 3.1 tool server at `/tools/*`. |
| `wikantik-scim` | SCIM 2.0 provisioning server at `/scim/v2/*`. |
| `wikantik-knowledge` | Knowledge MCP server at `/knowledge-mcp`, the Knowledge Graph service, the hybrid retriever and the context-bundle assembler. |
| `wikantik-ontology` | Jena RDF/OWL ontology, SHACL shapes and the Postgres-to-RDF projectors. |
| `wikantik-observability` | Health checks, Prometheus metrics, request correlation and admission filters. |
| `wikantik-insights` | Content Intelligence: search-visibility facts, content-opportunity rules and effect measurement. |
| `wikantik-rest` | REST API under `/api/*`, admin API under `/admin/*`, and the public RDF servlets. |
| `wikantik-extract-cli` | Offline entity-extractor, derived-page batch ingester and corpus-divergence tools. |
| `wikantik-war` | WAR packaging: bundles the React build and wires every servlet and filter. |
| `wikantik-it-tests` | Integration suites (Selenide browser, REST, SSO, custom JDBC, knowledge-disabled, dense retrieval, opt-in SCIM full loop). Only built with `-Pintegration-tests`. |
| `wikantik-bom` | Bill of materials pinning shared dependency versions. |

For layering rules and data flow, see [Wikantik System Architecture](WikantikArchitecture).

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 25 or newer | Enforced by the build; the JVM that runs Tomcat must also be 25+. |
| Maven | 3.9 or newer recommended | The build enforces a floor of 3.5. |
| Node.js and npm | 20.19+ or 22.12+ | Needed by Vite 8; the WAR build runs `npm ci` and `npm run build` (Vite) for you. |
| PostgreSQL | 15 or newer, with the pgvector extension | The Docker path uses the `pgvector/pgvector:pg18` image. |
| Docker with Compose | Any recent version | Required for the container path and for the integration tests. The unit tests that touch the database also start a pgvector container, and skip locally without Docker. |
| Ollama (optional) | n/a | Supplies embeddings (the default model is `qwen3-embedding:0.6b`, 1024 dimensions) and, if you enable it, entity extraction. Without an embedder search falls back to BM25 only. |

## Quick start

### Fastest: Docker Compose

```bash
cp .env.example .env              # then set POSTGRES_PASSWORD (it must not stay CHANGEME)
bin/container.sh build            # build the wikantik image
bin/container.sh -e base up -d    # start Tomcat 11 + PostgreSQL/pgvector
```

On first start the container applies the database migrations, seeds the admin account and starts Tomcat. Check it with `curl -fsS http://localhost:8080/api/health`.

### Bare metal (development)

```bash
# 1. Create the database, application role and schema (run as a PostgreSQL superuser).
#    DB_APP_USER / DB_APP_PASSWORD must match POSTGRES_USER / POSTGRES_PASSWORD in .env.
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
    DB_APP_PASSWORD='choose-a-real-password' \
    bin/db/install-fresh.sh --no-migrate-role

# 2. Build every module and the React frontend into wikantik-war/target/Wikantik.war
mvn clean install -DskipTests

# 3. First run copies .env.example to .env and stops; set POSTGRES_PASSWORD, then run again.
bin/deploy-local.sh
bin/deploy-local.sh
```

`bin/deploy-local.sh` downloads Tomcat 11 into the gitignored `tomcat/` directory, renders the Tomcat context and `wikantik-custom.properties` from `.env`, deploys the WAR, applies migrations, seeds the admin account and starts Tomcat. Unless you set `WIKANTIK_LOCAL_EMBEDDINGS=false` in `.env`, it also starts a local Ollama embedder container through Docker; the first run pulls a model of roughly 600 MB, which is a download, not a hang. Afterwards, stop and start Tomcat with `tomcat/tomcat-11/bin/shutdown.sh` and `tomcat/tomcat-11/bin/startup.sh`, and use `bin/redeploy.sh` for the fast rebuild-and-swap loop.

Use `-DskipTests` rather than `-Dmaven.test.skip` for the build: the latter also skips the test-jar that other modules depend on.

### First login

Open **http://localhost:8080/** and sign in as `admin` with the seeded password `admin123`. The account is flagged so that you must choose a new password immediately (NIST 800-63B rules, so `admin123` itself is rejected); until you do, API calls answer `403 PASSWORD_CHANGE_REQUIRED`. The full walkthrough, with troubleshooting, is [Getting Started with Wikantik](WikantikGettingStarted).

## Configuration

`wikantik-main/src/main/resources/ini/wikantik.properties` declares every `wikantik.*` key with its default, type and description (MCP and tool-server keys are in `wikantik-mcp.properties` and `wikantik-tools.properties`). Override keys in `wikantik-custom.properties` or with `-D` system properties. The generated listing is [Wikantik Configuration Reference](WikantikConfigurationReference). Database schema changes exist only as numbered, idempotent migrations in `bin/db/migrations/`, applied by `bin/db/migrate.sh`.

## Testing

```bash
bin/run-tests.sh --parallel 4          # canonical pre-commit gate: all unit tests, then the IT modules 4-wide
mvn clean install -T 1C -DskipITs      # unit tests only
mvn test -pl wikantik-main -Dtest=MarkdownRendererTest   # one test class
```

The gate needs Docker: it starts a pgvector container per integration module and one shared CPU Ollama embedder for the dense-retrieval suite. See [Wikantik Testing Guide](WikantikTestingGuide).

## Deployment and releases

The `Dockerfile` builds a self-contained image (Tomcat 11 on a Java 25 runtime) and `bin/container.sh` wraps Docker Compose for the build, run, logs, psql, migrate, backup and restore steps. The `prod` overlay adds a backup sidecar. Pushing a version tag runs `.github/workflows/release.yml`, which builds the WAR and publishes `ghcr.io/jakefearsd/wikantik:X.Y.Z`; `bin/cut-release.sh X.Y.Z` prepares that tag, and `bin/deploy-release.sh X.Y.Z` pulls the image and ships it to a remote host (with automatic rollback if the health check fails). For container, remote and cloud details see [Wikantik on Docker](WikantikOnDocker), [Cloud Deployment](WikantikCloudDeployment), [Backup and Recovery](WikantikBackupAndRecovery) and [Release and Deploy Process](WikantikReleaseAndDeployProcess).

## License

Wikantik is licensed under the **Apache License, Version 2.0**; the full text is in `LICENSE`. It descends from Apache JSPWiki, so the `NOTICE` file preserves the Apache Software Foundation copyright notice and the attributions for bundled third-party components. See `SECURITY.md` for how to report a vulnerability and `CONTRIBUTING.md` and `CODE_OF_CONDUCT.md` for how to contribute.

## Where to go next

- [Wikantik Development Hub](WikantikDevelopmentHub): index of the developer documentation.
- [Wikantik System Architecture](WikantikArchitecture): modules, data model, retrieval stack and security model.
- [Getting Started with Wikantik](WikantikGettingStarted): first install, step by step.
- [Wikantik Operations](WikantikOperations): running, monitoring and maintaining an instance.
- [Wikantik Configuration Reference](WikantikConfigurationReference): every configuration key.
- [MCP Integration](McpIntegration): connecting agents to the MCP servers.
- [Wikantik Testing Guide](WikantikTestingGuide): unit, integration and coverage practice.
- [Release and Deploy Process](WikantikReleaseAndDeployProcess): cutting and shipping a release.
- [Text Formatting Rules](TextFormattingRules): the Markdown dialect, wikilinks and math.
- [History and Evolution: From JSPWiki to Wikantik](WikantikEvolutionFromJSPWiki): where the project came from.

In the repository, `docs/README.md` indexes the user, admin and developer documentation, `CHANGELOG.md` records every release and `ROADMAP.md` lists planned work.
