---
status: active
date: '2026-03-29'
summary: Final removal of JSP templates and the /app/ URL prefix for a single SPA
  entry point
tags:
- development
- migration
- jsp
- routing
type: article
canonical_id: 01KQ0P44VGY08Z06TE936CPZCJ
cluster: wikantik-development
related:
- WikantikDevelopment
title: Remove JSP and /app/ Prefix
---
# Remove JSP and /app/ Prefix

> **Status (2026-10-13):** Done in March 2026 and still the current design. The SPA routing described below now lives in `SpaRoutingFilter` in the `wikantik-rest` module, which forwards recognised SPA paths (`/wiki/`, `/edit/`, `/diff/`, `/admin/` and a short list of exact routes such as `/search` and `/login`) to `/index.html`. See `docs/developer/FrontendArchitecture.md` in the repository.

This cleanup step completed the JSP-to-React migration by removing all remaining JSP templates and consolidating the URL structure. Previously, the React SPA was served under `/app/` while legacy JSP pages remained at the root. After full feature parity was achieved, all JSP templates were deleted and the SPA was promoted to serve from the root URL.

## Changes

- Removed all JSP template files from the WAR
- Updated SpaRoutingFilter to serve the React SPA from `/` instead of `/app/`
- Redirected the bare entry points `/`, `/wiki` and `/wiki/` to `/wiki/Main` (there is no `/app/` redirect: the filter dropped `/app/` handling outright)
- Cleaned up web.xml servlet and filter mappings for removed JSP servlets

[{Relationships}]

---

**Cluster Hub:** [[WikantikDevelopmentHub|Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent Briefings]] (`wikantik-development`)
