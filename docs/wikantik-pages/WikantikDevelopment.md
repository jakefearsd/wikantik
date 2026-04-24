---
canonical_id: 01KQ0P44YWV8Q0JMN1H2H5EGDX
cluster: wikantik-development
tags:
- development
- architecture
- engineering
- platform
type: hub
status: deployed
summary: Hub page for Wikantik platform development — architecture decisions, feature implementations, and engineering history
related:
- About
- JspToReactMigration
- DatabaseBackedPermissions
- KnowledgeGraphCore
- BlogFeature
- AttachmentManagement
- McpIntegration
- AdminSecurityUi
- KnowledgeGraphDogfooding
- TestStubConversion
- ConstructorInjection
- McpIntegrationTestFix
- UserProfileBio
- WikiToMarkdownConverter
- RemoveJspAndAppPrefix
- WikiAuditSkill
- McpAuditTools
- BlogEditorSplitView
- FullOAuth
---

# Wikantik Development

This cluster documents the development history of the Wikantik platform itself — the architecture decisions, feature implementations, and engineering patterns that have shaped the system over time.

## Architecture Evolution

Wikantik began as a fork of Apache JSPWiki, a traditional Java wiki engine with JSP-based rendering, file-based storage, and XML security policies. Through a sustained modernization effort in March–April 2026, the platform was transformed into a modern full-stack application:

- **Frontend**: JSP templates replaced with a React SPA (Vite + React Router)
- **Storage**: File-based providers augmented with PostgreSQL for users, groups, permissions, and the knowledge graph
- **Security**: XML policy files migrated to database-backed `policy_grants` table with admin UI
- **AI Integration**: Two dedicated Model Context Protocol (MCP) servers — `/wikantik-admin-mcp` for writes and analytics (16 tools) and `/knowledge-mcp` for read-only retrieval and graph traversal (10 tools) — plus an OpenAPI tool server at `/tools/*` for OpenWebUI-compatible non-MCP clients (2 tools)
- **Knowledge Graph**: Property graph over wiki content with proposal-based enrichment workflow, backed by PostgreSQL + pgvector

## Module Architecture

The system is organized into 18 Maven modules with clear separation of concerns:

| Module | Responsibility |
|--------|---------------|
| `wikantik-bom` | Dependency BOM |
| `wikantik-api` | Public interfaces, data models, frontmatter parsing |
| `wikantik-main` | Core engine, rendering, providers, knowledge-graph service, entity extraction |
| `wikantik-admin-mcp` | Admin MCP server (writes + analytics) — 16 tools, 6 resources, 8 prompts, 3 completions at `/wikantik-admin-mcp` |
| `wikantik-knowledge` | Knowledge MCP server (hybrid retrieval + graph) — 10 tools at `/knowledge-mcp`; also hosts the knowledge-graph service |
| `wikantik-tools` | OpenAPI 3.1 tool server (2 tools) at `/tools/*` |
| `wikantik-extract-cli` | Offline entity-extractor CLI |
| `wikantik-rest` | REST API (`/api/*`) and admin endpoints (`/admin/*`) |
| `wikantik-http` | Servlet filters — CSRF, CORS, CSP, SPA routing, `/wiki/{slug}?format=*` |
| `wikantik-event` | Event system for decoupled communication |
| `wikantik-util` | Utilities |
| `wikantik-cache` / `wikantik-cache-memcached` | Caching layers |
| `wikantik-observability` | Health, Prometheus, correlation |
| `wikantik-frontend` | React SPA (Vite) |
| `wikantik-war` | WAR packaging, bundles frontend and wires servlets/filters |
| `wikantik-wikipages` | Default pages for fresh installs |
| `wikantik-it-tests` | Integration tests |

## Key Design Decisions

1. **ADR-001: Extract Manager Interfaces to API** — Decoupled the MCP modules from `wikantik-main` by moving 8 interfaces to `wikantik-api`, reducing cross-module imports from 15 to 5.

2. **Frontmatter as Source of Truth** — Wiki page YAML frontmatter is the canonical source for knowledge graph relationships. The GraphProjector synchronizes frontmatter to graph nodes/edges on every page save.

3. **[Provenance Model](ProvenanceModel)** — All knowledge graph content tracks its origin: `human-authored` (from frontmatter), `ai-inferred` (proposals), `ai-reviewed` (approved proposals written back to frontmatter).

## Feature Timeline

| Date | Feature |
|------|---------|
| 2026-03-12 | MCP integration test fixes |
| 2026-03-23 | Test stub conversion for decoupled testing |
| 2026-03-28 | JSP → React SPA migration |
| 2026-03-28 | Database-backed permissions |
| 2026-03-28 | Admin security UI |
| 2026-03-29 | URL cleanup (remove /app/ prefix) |
| 2026-03-29 | Constructor injection for testability |
| 2026-04-01 | Wiki-to-Markdown converter |
| 2026-04-03 | Blog feature |
| 2026-04-03 | Attachment management |
| 2026-04-03 | User profile bios |
| 2026-04-04 | Knowledge graph core |

[{Relationships}]
