# Gemini Development Guide for Wikantik

This document orients the Gemini agent to the Wikantik codebase. For authoritative project rules (coding conventions, test policy, token-efficiency rules, commit etiquette) read **[CLAUDE.md](CLAUDE.md)** — it is the canonical agent-development guide and the rules there apply to every agent working in this repo.

## Project overview

Wikantik is a modular Java 21 / Jakarta EE wiki-and-knowledge-base engine. It serves 1000+ Markdown pages from a version-controlled content root (`docs/wikantik-pages/`) through:

- A **React SPA** (`wikantik-frontend`, Vite) served at `/`
- A **REST/JSON API** (`wikantik-rest`) at `/api/*` and admin endpoints at `/admin/*`
- Two independent **MCP servers** at `/wikantik-admin-mcp` (writes + analytics) and `/knowledge-mcp` (read-only retrieval + graph)
- An **OpenAPI 3.1 tool server** (`wikantik-tools`) at `/tools/*` for OpenWebUI-compatible non-MCP clients
- A **Tomcat 11** servlet container with **PostgreSQL 15+ and pgvector** as the primary datastore

Unit tests use JUnit 5 with in-memory H2. Integration tests use Selenide + Cargo against PostgreSQL + pgvector.

## Module map (authoritative)

| Module | Role |
|--------|------|
| `wikantik-bom` | Dependency BOM |
| `wikantik-api` | Core interfaces and contracts (`com.wikantik.api.*`) |
| `wikantik-main` | Main engine: rendering, providers, auth, search, references, math parser, entity extraction |
| `wikantik-event` | Event system (`WikiEvent`, listeners) |
| `wikantik-util` | Utilities and helpers |
| `wikantik-cache` | EhCache-based caching |
| `wikantik-cache-memcached` | Memcached adapter |
| `wikantik-http` | Servlet filters (CSRF, CORS, CSP, SPA routing, `/wiki/{slug}?format=*` content filter) |
| `wikantik-rest` | REST `/api/*` and admin `/admin/*` endpoints |
| `wikantik-admin-mcp` | Admin MCP server (16 tools + 6 resources + 8 prompts + 3 completions) |
| `wikantik-knowledge` | Knowledge MCP server (10 tools) + knowledge-graph service (pgvector, co-mention graph, hub discovery) |
| `wikantik-tools` | OpenAPI 3.1 tool server (2 tools) |
| `wikantik-extract-cli` | Offline entity-extractor CLI |
| `wikantik-observability` | Health, Prometheus, correlation |
| `wikantik-frontend` | React SPA (Vite) |
| `wikantik-war` | WAR packaging |
| `wikantik-wikipages` | Default pages for a fresh install |
| `wikantik-it-tests` | Selenide + REST + Cargo integration tests |

## Extension points

Extend Wikantik by implementing interfaces from `wikantik-api` (not `jspwiki-api` — the JSPWiki rebrand is complete; see [docs/full_rebrand_project.md](docs/full_rebrand_project.md)):

- **Plugins** — `com.wikantik.api.plugin.Plugin`: dynamic content rendered from `[{PluginName param=value}]` markup
- **Providers** — `com.wikantik.api.providers.PageProvider`, `AttachmentProvider`, `WikiProvider`: swap how pages/attachments are stored
- **Filters** — `com.wikantik.api.filters.PageFilter`: intercept and mutate page content before display or save
- **MCP tools** — implement `com.wikantik.mcp.tools.McpTool` (admin) or add to `com.wikantik.knowledge.mcp.*` (read-only retrieval)
- **REST resources** — extend `com.wikantik.rest.RestServletBase` (enforces ACLs + policy grants)

### Example: a minimal `Plugin`

```java
package com.example.wiki.plugins;

import java.util.Map;
import com.wikantik.api.core.Context;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.api.plugin.PluginException;

public class HelloWorldPlugin implements Plugin {
    @Override
    public String execute(Context context, Map<String, String> params) throws PluginException {
        String name = params.getOrDefault("name", "World");
        return "Hello " + name + "!";
    }
}
```

1. Compile and JAR as usual.
2. Drop the JAR into `tomcat/tomcat-11/webapps/ROOT/WEB-INF/lib/` (or add as a Maven dep to `wikantik-war`).
3. Add your package to `jspwiki.plugin.searchPath` in `wikantik-custom.properties`.
4. Restart Tomcat.
5. Use on any wiki page: `[{HelloWorldPlugin name='Wikantik Developer'}]`.

## Commands you actually need

```bash
# Build everything (React frontend + WAR + tests)
mvn clean install

# Fast compile-check of a single module
mvn compile -pl wikantik-admin-mcp -am -q

# Unit tests only, parallel
mvn clean install -T 1C -DskipITs

# Integration tests — MUST be sequential (no -T flag)
mvn clean install -Pintegration-tests -fae

# Single test method
mvn test -pl wikantik-main -Dtest=MarkdownRendererTest#testMarkupSimpleMarkdown

# Deploy to the local Tomcat 11 (never use Cargo outside of IT tests)
bin/deploy-local.sh
tomcat/tomcat-11/bin/startup.sh
```

**Do not use `cargo:run`** to bring the app up for manual testing. Cargo is reserved for integration-test harnesses in `wikantik-it-tests`. For local dev, the `tomcat/tomcat-11/` instance deployed by `bin/deploy-local.sh` is the only supported path.

## See also

- [CLAUDE.md](CLAUDE.md) — the canonical agent-development guide (rules, token-efficiency, test policy, security model, architecture overview)
- [README.md](README.md) — user-facing overview and quick-start
- [docs/wikantik-pages/GoodMcpDesign.md](docs/wikantik-pages/GoodMcpDesign.md) — design principles for MCP tool authors
- [docs/wikantik-pages/HybridRetrieval.md](docs/wikantik-pages/HybridRetrieval.md) — retrieval architecture
- [docs/wikantik-pages/StructuralSpineDesign.md](docs/wikantik-pages/StructuralSpineDesign.md) — planned structural-index surface
- [docs/wikantik-pages/AgentGradeContentDesign.md](docs/wikantik-pages/AgentGradeContentDesign.md) — planned agent-grade content layer and retrieval-quality CI
