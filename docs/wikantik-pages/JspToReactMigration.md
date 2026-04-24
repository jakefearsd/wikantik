---
canonical_id: 01KQ0P44REFP9483SXP4P9S0FG
summary: Migration from JSP server-rendered templates to a React SPA with Vite, React Router, and a REST API backend
tags:
- development
- react
- frontend
- migration
- spa
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-28'
related:
- WikantikDevelopment
- AdminSecurityUi
- DatabaseBackedPermissions
enables:
- AdminSecurityUi
- KnowledgeAdminUi
- BlogFeature
---
# JSP to React Migration

The most transformative change in the Wikantik modernization effort was replacing the JSP-based server-rendered UI with a React single-page application.

## Motivation

The legacy JSP UI was tightly coupled to the Java backend — each page view required a full server round-trip, templates were scattered across multiple JSP files with embedded Java scriptlets, and adding new UI features required changes to both Java controllers and JSP templates. Modern AI agents interacting via MCP needed a clean REST API, not server-rendered HTML.

## Implementation

The migration was executed in a single coordinated effort:

1. **REST API layer** (`wikantik-rest`) — New servlet-based endpoints exposing page CRUD, search, authentication, and admin operations as JSON
2. **React SPA** (`wikantik-frontend`) — Vite-built React application with React Router for client-side navigation
3. **SPA routing filter** (`wikantik-http`) — Server-side filter that serves `index.html` for all non-API routes, enabling client-side routing
4. **URL cleanup** — Removed the legacy `/app/` prefix, making the wiki serve directly from the root context

## Modules Affected

- `wikantik-rest` — New REST endpoints
- `wikantik-http` — SPA routing filter, CORS configuration
- `wikantik-war` — Vite build integration, static asset serving

[{Relationships}]
