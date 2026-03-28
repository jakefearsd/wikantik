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

Wikantik is a modular Java-based knowledge base platform built on JEE technologies. It combines a Markdown-native authoring system with a React single-page application, a REST API, an MCP server for AI agent integration, and a full observability stack. Content is organized into thematic clusters with structured frontmatter metadata, indexed by Lucene for full-text and faceted search.

Key capabilities:

- **Markdown rendering** with Flexmark — fenced code blocks, tables, footnotes, definition lists, TOC generation, and wiki-style internal links
- **React SPA** at `/app/` — editorial magazine aesthetic with dark mode, metadata chips, change history, and inline editing
- **REST API** at `/api/` — full CRUD for pages, attachments, search, history, diffs, and backlinks with ACL-based permission enforcement
- **MCP server** at `/mcp/` — 37 tools, 6 resources, 8 prompts for AI-assisted wiki operations including cluster publishing, auditing, and content management
- **Admin panel** — user management, content management (orphaned pages, broken links, version purging, cache stats), security management (groups and policy grants)
- **Database-backed authorization** — policy grants and groups stored in PostgreSQL, manageable through the admin UI, with bootstrap admin override for recovery
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

## Quick Start (Local Development)

```bash
# 1. Create the PostgreSQL database
sudo -u postgres psql -c "CREATE DATABASE wikantik;"
sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/postgresql.ddl
sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/postgresql-permissions.ddl

# 2. Build (includes React frontend via npm)
mvn clean install -Dmaven.test.skip -T 1C

# 3. Bootstrap Tomcat, configure, and deploy
./deploy-local.sh

# 4. Set your PostgreSQL password in the context file (path shown by script output)

# 5. Start Tomcat
tomcat/tomcat-11/bin/startup.sh
# Access at http://localhost:8080/ — default login: admin / admin123
# React SPA at http://localhost:8080/app/
```

See [PostgreSQLLocalDeployment.md](docs/PostgreSQLLocalDeployment.md) for the full guide.

## Using Docker

```bash
docker compose up -d
```

Then open http://localhost:8080/. See [DockerDeployment.md](docs/DockerDeployment.md) for backups, data persistence, and the full container guide.

## Module Structure

| Module | Purpose |
|--------|---------|
| `wikantik-api` | Core interfaces and contracts (manager interfaces, frontmatter, page save) |
| `wikantik-main` | Main implementation — rendering, providers, auth, search, references |
| `wikantik-event` | Event system for decoupled communication |
| `wikantik-util` | Utility classes and helpers |
| `wikantik-bootstrap` | Initialization and bootstrap |
| `wikantik-cache` | EhCache-based caching layer |
| `wikantik-cache-memcached` | Distributed cache adapter for Memcached |
| `wikantik-http` | Servlet filters — CSRF, CORS, CSP, security headers |
| `wikantik-rest` | REST/JSON API and admin panel endpoints |
| `wikantik-mcp` | MCP server for AI agent integration (37 tools) |
| `wikantik-observability` | Health checks, Prometheus metrics, request correlation |
| `wikantik-war` | WAR packaging, React frontend build, deployment config |
| `wikantik-wikipages` | Default wiki pages (en, es, ru) |
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
- [NewUI.md](docs/NewUI.md) — React SPA design and architecture
- [RelationalUserDatabase.md](docs/RelationalUserDatabase.md) — PostgreSQL user and group database configuration
- [Sitemap.md](docs/Sitemap.md) — Sitemap.xml and Atom feed servlets
- [OAuthImplementation.md](docs/OAuthImplementation.md) — OAuth SSO implementation plan (Google, GitHub)
- [FullOAuth.md](docs/FullOAuth.md) — OAuth/OpenID Connect detailed design

### Security

- Database-backed authorization — policy grants and groups managed via admin UI (see [design spec](docs/superpowers/specs/2026-03-28-database-backed-permissions-design.md))
- Page-level ACLs via inline `[{ALLOW view Admin}]` syntax in page content
- REST API permission enforcement — all endpoints check ACLs and policy grants
- NIST 800-63B password validation with common-password blocklist
- CSRF protection (synchronizer token pattern for JSP forms, Content-Type protection for REST/admin endpoints)
- Deserialization filtering — ObjectInputFilter whitelists on all ObjectInputStream usage
- Bootstrap admin override — `wikantik.admin.bootstrap` property guarantees admin access during initial setup

### Architecture & Design

- [RefactorToPatterns.md](docs/RefactorToPatterns.md) — GoF design patterns applied across the codebase
- [PerformanceEvaluation.md](docs/PerformanceEvaluation.md) — I/O, indexing, and rendering bottleneck analysis
- [complete_markdown_migration.md](docs/complete_markdown_migration.md) — Migration from legacy wiki syntax to Markdown-only rendering
- [semantic_wiki_thoughts.md](docs/semantic_wiki_thoughts.md) — AI-augmented semantic wiki vision
- [ADR-001: Extract manager interfaces to API](docs/adrs/001-extract-manager-interfaces-to-api.md)

### MCP Integration

The `wikantik-mcp` module provides a Model Context Protocol server for AI-assisted wiki operations — article authoring, cluster management, structural auditing, and content publishing. 37 tools, 6 resources, 8 prompts, and 3 completions.

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
