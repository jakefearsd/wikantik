---
summary: Final removal of JSP templates and the /app/ URL prefix for a single SPA entry point
tags:
- development
- migration
- jsp
- routing
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-29'
depends-on:
- JspToReactMigration
supersedes:
- JspToReactMigration
related:
- WikantikDevelopment
---
# Remove JSP and /app/ Prefix

This cleanup step completed the JSP-to-React migration by removing all remaining JSP templates and consolidating the URL structure. Previously, the React SPA was served under `/app/` while legacy JSP pages remained at the root. After full feature parity was achieved, all JSP templates were deleted and the SPA was promoted to serve from the root URL.

## Changes

- Removed all JSP template files from the WAR
- Updated SpaRoutingFilter to serve the React SPA from `/` instead of `/app/`
- Redirected legacy `/app/` URLs to root for backwards compatibility
- Cleaned up web.xml servlet and filter mappings for removed JSP servlets

[{Relationships}]
