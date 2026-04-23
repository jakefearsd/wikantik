# Wikantik

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

The license file can be found in LICENSE.


## What is Wikantik?

Wikantik is a modular Java-based knowledge base platform built on JEE technologies. It combines a Markdown-native authoring system with a React single-page application, a REST API, a Model Context Protocol server for AI agent integration, a knowledge graph visualiser, and a full observability stack. Content is organised into thematic clusters with structured frontmatter metadata, indexed by Lucene for full-text and faceted search.

Key capabilities:

- **Markdown rendering** with Flexmark — fenced code blocks, tables, footnotes, definition lists, TOC generation, wiki-style internal links, and LaTeX math (see [MathematicalNotation.md](docs/MathematicalNotation.md))
- **React SPA** served at `/` — editorial magazine aesthetic with dark mode, metadata chips, change history, similar-pages panel, and inline editing
- **Knowledge graph** at `/graph` — interactive Cytoscape visualisation of page relationships (backlinks, frontmatter, clusters) with semantic zoom, edge-type filtering, and parallel-edge merging
- **REST API** at `/api/` — full CRUD for pages, attachments, search, history, diffs, backlinks, and the knowledge graph snapshot, with ACL-based permission enforcement
- **MCP server** at `/mcp/` — 22 tools (page read/search, export/import workflow, link analysis, metadata queries, knowledge proposals) plus resources, prompts, and completions for AI-assisted wiki operations
- **Admin panel** at `/admin/` — user management, content management (orphaned pages, broken links, version purging, cache stats), security management (groups and policy grants)
- **Database-backed authorisation** — policy grants and groups stored in PostgreSQL, manageable through the admin UI, with bootstrap admin override for recovery
- **Observability** — health checks, Prometheus metrics at `/metrics`, structured logging with request correlation, IP-restricted to internal networks
- **Content clusters** — thematic article groupings with hub pages, sub-clusters, cross-references, and automated structural auditing
- **NIST 800-63B password validation** — blocklist-checked password strength enforcement for account creation
- **Frontmatter metadata** — YAML frontmatter for type, tags, summary, cluster, status, and related articles, indexed in Lucene for semantic navigation


## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js + npm | 18+ | Required — WAR build runs `npm install` + `vite build` automatically |
| PostgreSQL | 15+ | For local deployment; unit tests use in-memory H2 |
| pgvector | 0.5+ | PostgreSQL extension — required for the knowledge graph (see below) |

### Installing pgvector

The knowledge graph and hub-discovery features store machine-learning
embeddings directly in PostgreSQL using the [`pgvector`](https://github.com/pgvector/pgvector)
extension. Without it, migration `V004` will fail (`CREATE EXTENSION vector`)
and the knowledge-graph endpoints under `/graph`, `/api/knowledge/*`, and
`/admin/knowledge/*` will not function.

**What pgvector is used for in Wikantik**

- `kg_embeddings.embedding vector(100)` — ComplEx knowledge-graph embeddings
  (real and imaginary components of 50-dimensional complex vectors concatenated
  into a single 100-D real vector) powering link prediction and merge candidates.
- `kg_content_embeddings.embedding vector(512)` — dense text embeddings over
  page content used for similarity search and hub-proposal clustering.
- `hubs.centroid vector(512)` — per-hub centroid for near-miss / drilldown
  queries in the hub overview admin UI.
- Cosine-distance operators (`<=>`) are used in-database for k-NN retrieval,
  which is far cheaper than shipping vectors to the JVM.

The extension must be installed on the PostgreSQL server that hosts the
application database. `install-fresh.sh` (run as the `postgres` superuser)
issues `CREATE EXTENSION vector` — this only succeeds if the extension
binaries are already present on the server.

**Ubuntu / Debian**

The PGDG apt repository ships a `postgresql-<MAJOR>-pgvector` package that
matches your installed PostgreSQL major version. Install the one that
corresponds to your server (check with `psql --version`):

```bash
# Ensure the PGDG repository is configured (usually already present if you
# installed PostgreSQL from apt.postgresql.org). If not:
sudo apt install -y curl ca-certificates gnupg
curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    | sudo gpg --dearmor -o /usr/share/keyrings/pgdg.gpg
echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
    | sudo tee /etc/apt/sources.list.d/pgdg.list
sudo apt update

# Then install pgvector matching your PostgreSQL major version, e.g. 16:
sudo apt install -y postgresql-16-pgvector

# Restart PostgreSQL so new extension files are visible:
sudo systemctl restart postgresql
```

**Fedora / RHEL / Rocky / AlmaLinux**

The PGDG yum repository provides `pgvector_<MAJOR>`:

```bash
# Install the PGDG repository RPM (adjust for your distro; Fedora 40 shown):
sudo dnf install -y \
    https://download.postgresql.org/pub/repos/yum/reporpms/F-40-x86_64/pgdg-fedora-repo-latest.noarch.rpm

# Install pgvector matching your PostgreSQL major version, e.g. 16:
sudo dnf install -y pgvector_16

# Restart PostgreSQL:
sudo systemctl restart postgresql-16
```

For RHEL / Rocky / Alma, substitute the appropriate repo RPM from
https://yum.postgresql.org/repopackages/ and use `postgresql-<MAJOR>-server`
service naming.

**macOS (Homebrew)**

Homebrew ships pgvector as a standalone formula that links against the
Homebrew PostgreSQL build:

```bash
# Install PostgreSQL (skip if you already have one from Homebrew):
brew install postgresql@16

# Install pgvector — it autodetects the Homebrew PostgreSQL install:
brew install pgvector

# Start/restart PostgreSQL:
brew services restart postgresql@16
```

If you run PostgreSQL from Postgres.app or another non-Homebrew source, build
pgvector from source against that installation's `pg_config`:

```bash
git clone --branch v0.7.4 https://github.com/pgvector/pgvector.git
cd pgvector
# Point make at the right pg_config if it is not first on PATH:
PG_CONFIG=/Applications/Postgres.app/Contents/Versions/16/bin/pg_config make
PG_CONFIG=/Applications/Postgres.app/Contents/Versions/16/bin/pg_config sudo make install
```

**Verifying the install**

After installing pgvector and restarting PostgreSQL, confirm the extension is
available to the server:

```bash
psql -h localhost -U postgres -c \
    "SELECT name, default_version FROM pg_available_extensions WHERE name='vector';"
```

You should see a row listing `vector` with a version of `0.5.x` or newer.
If the row is missing, the extension binaries are not on this server — re-check
that you installed the package matching the PostgreSQL major version actually
running (not just the client you have on your PATH).

## Quick Start (Local Development)

```bash
# 1. Create the database, application role, and full schema (idempotent)
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' \
    bin/db/install-fresh.sh

# 2. Build (includes React frontend via npm)
mvn clean install -Dmaven.test.skip -T 1C

# 3. Bootstrap Tomcat, configure, and deploy. deploy-local.sh runs migrate.sh
#    so any pending schema migrations are applied automatically.
bin/deploy-local.sh

# 4. Set your PostgreSQL password in the context file (path shown by script output)

# 5. Start Tomcat
tomcat/tomcat-11/bin/startup.sh
# Access at http://localhost:8080/ — default login: admin / admin123
# React SPA at http://localhost:8080/, knowledge graph at http://localhost:8080/graph
```

Database schema lives in [`bin/db/migrations/`](bin/db/migrations/README.md).
To bring an existing database up to date (including production), run
`bin/db/migrate.sh` with connection env vars set.

See [PostgreSQLLocalDeployment.md](docs/PostgreSQLLocalDeployment.md) for the full guide.

## Using Docker

```bash
docker compose up -d
```

Then open http://localhost:8080/. See [DockerDeployment.md](docs/DockerDeployment.md) for backups, data persistence, and the full container guide.

## Module Structure

| Module | Purpose |
|--------|---------|
| `wikantik-bom` | Bill-of-materials POM pinning shared dependency versions |
| `wikantik-api` | Core interfaces and contracts (manager interfaces, frontmatter, page save, knowledge graph service) |
| `wikantik-main` | Main implementation — Markdown rendering, providers, auth, search, references, math parser |
| `wikantik-event` | Event system for decoupled communication |
| `wikantik-util` | Utility classes and helpers |
| `wikantik-cache` | EhCache-based caching layer |
| `wikantik-cache-memcached` | Distributed cache adapter for Memcached |
| `wikantik-http` | Servlet filters — CSRF, CORS, CSP, security headers, SPA routing |
| `wikantik-rest` | REST/JSON API and admin panel endpoints |
| `wikantik-mcp` | MCP server for AI agent integration (22 tools plus resources, prompts, completions) |
| `wikantik-knowledge` | Knowledge graph service — page-relationship snapshot generation and proposals |
| `wikantik-observability` | Health checks, Prometheus metrics, request correlation |
| `wikantik-frontend` | React SPA (Vite build) — reader, editor, admin panel, knowledge graph viewer |
| `wikantik-war` | WAR packaging and deployment config; bundles the frontend build output |
| `wikantik-wikipages` | Default wiki pages shipped with a fresh install |
| `wikantik-it-tests` | Integration tests (Selenide browser automation, REST API, custom providers) |

## Documentation

### Development Setup

- [PostgreSQLLocalDeployment.md](docs/PostgreSQLLocalDeployment.md) — Local dev environment with PostgreSQL and Tomcat
- [DevelopingWithPostgresql.md](docs/DevelopingWithPostgresql.md) — Full PostgreSQL schema, JDBC, and JNDI configuration
- [MvnCheatSheet.md](docs/MvnCheatSheet.md) — Maven build, test, and debug commands
- [LoggingConfig.md](docs/LoggingConfig.md) — Log4j2 external configuration
- [IndexRebuild.md](docs/IndexRebuild.md) — Search index rebuild guide for local and Docker deployments

### Deployment & Operations

- [DockerDeployment.md](docs/DockerDeployment.md) — Docker Compose setup, backups, and restoration
- [production-container-architecture.md](docs/production-container-architecture.md) — Production architecture with Cloudflare, Tomcat, and PostgreSQL
- [ci-cd-step-by-step.md](docs/ci-cd-step-by-step.md) — CI/CD pipeline setup with self-hosted runner
- [SendingEmailFromTheWiki.md](docs/SendingEmailFromTheWiki.md) — SMTP relay setup (Brevo, SendGrid, Mailjet, SES, Resend)
- [ObservabilityDesign.md](docs/ObservabilityDesign.md) — Grafana, Prometheus, and Loki observability stack

### Features

- [MarkdownLinks.md](docs/MarkdownLinks.md) — Markdown internal and external link syntax
- [MathematicalNotation.md](docs/MathematicalNotation.md) — LaTeX math rendering (`$…$`, `$$…$$`, ```` ```math ````) via Flexmark + KaTeX
- [NewUI.md](docs/NewUI.md) — React SPA design and architecture (reader, editor, admin, knowledge graph)
- [DatabaseUpdates.md](docs/DatabaseUpdates.md) — Knowledge graph schema and index layout
- [KnowledgeGraphRerank.md](docs/KnowledgeGraphRerank.md) — Configuration, verification, and tuning guide for the entity extractor, unified embeddings, and graph-aware search rerank
- [RelationalUserDatabase.md](docs/RelationalUserDatabase.md) — PostgreSQL user and group database configuration
- [Sitemap.md](docs/Sitemap.md) — Sitemap.xml and Atom feed servlets
- [OAuthImplementation.md](docs/OAuthImplementation.md) — OAuth SSO implementation plan (Google, GitHub)
- [FullOAuth.md](docs/FullOAuth.md) — OAuth/OpenID Connect detailed design

### Security

- Database-backed authorization — policy grants and groups managed via admin UI (see [design spec](docs/superpowers/specs/2026-03-28-database-backed-permissions-design.md))
- Page-level ACLs via inline `[{ALLOW view Admin}]` syntax in page content
- REST API permission enforcement — all endpoints check ACLs and policy grants
- NIST 800-63B password validation with common-password blocklist
- CSRF protection (synchronizer token pattern for forms, Content-Type protection for REST/admin endpoints)
- Deserialization filtering — ObjectInputFilter whitelists on all ObjectInputStream usage
- Bootstrap admin override — `wikantik.admin.bootstrap` property guarantees admin access during initial setup

### Architecture & Design

- [RefactorToPatterns.md](docs/RefactorToPatterns.md) — GoF design patterns applied across the codebase
- [PerformanceEvaluation.md](docs/PerformanceEvaluation.md) — I/O, indexing, and rendering bottleneck analysis
- [complete_markdown_migration.md](docs/complete_markdown_migration.md) — Migration from legacy wiki syntax to Markdown-only rendering
- [semantic_wiki_thoughts.md](docs/semantic_wiki_thoughts.md) — AI-augmented semantic wiki vision
- [full_rebrand_project.md](docs/full_rebrand_project.md) — Contributor reference for the JSPWiki → Wikantik rebrand and naming conventions
- [ADR-001: Extract manager interfaces to API](docs/adrs/001-extract-manager-interfaces-to-api.md)

### MCP Integration

The `wikantik-mcp` module provides a Model Context Protocol server at `/mcp/` for AI-assisted wiki operations — reading, searching, link and backlink analysis, history and diffs, metadata querying, recent changes, an export/import workflow for bulk editing (replacing legacy per-page CRUD), structural-verification checks, and knowledge-graph proposals. The server exposes 22 tools plus resource templates, prompts, and completions. See `wikantik-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java` for the authoritative tool list.

### Research

- [research_history.md](docs/research_history.md) — Log of research sessions and article clusters published to the wiki

### Legal Templates

- [PrivacyPolicy.md](docs/PrivacyPolicy.md) — Privacy policy template
- [TermsOfService.md](docs/TermsOfService.md) — Terms of service template


## Building

```bash
# Standard build with tests
mvn clean install

# Parallel build, unit tests only (fastest for development)
mvn clean install -T 1C -DskipITs

# Build without tests
mvn clean install -Dmaven.test.skip

# Integration tests (MUST be sequential — no -T flag)
mvn clean install -Pintegration-tests -fae
```

## Contact

Questions can be asked to the Wikantik team via the wikantik-users
mailing list: See https://wikantik.apache.org/community/mailing_lists.html.
