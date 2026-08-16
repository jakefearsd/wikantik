# Wikantik REST API

REST/JSON endpoints for the Wikantik wiki engine: the public `/api/*`
surface (pages, search, frontmatter schema, context bundle, page-scoped
Knowledge Graph curation) and the `/admin/*` surface (content management,
Knowledge Graph policy, ontology status, cluster tooling, connector
administration, drift dashboards, audit log). All endpoints enforce
permissions through `RestServletBase.checkPagePermission()`; admin endpoints
are additionally gated by `AdminAuthFilter`.

This module enables alternative frontends and programmatic access on top of
the same manager APIs the React SPA uses.
