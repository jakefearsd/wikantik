# Wikantik REST API

REST/JSON endpoints for the Wikantik wiki engine: the public `/api/*`
surface (pages, search, frontmatter schema, context bundle and briefing,
session and collaboration endpoints) and the `/admin/*` surface (content management,
embeddings status and backfill, ontology status, cluster tooling, connector
administration, drift dashboards, audit log). All endpoints enforce
permissions through `RestServletBase.checkPagePermission()`; admin endpoints
are additionally gated by `AdminAuthFilter`.

This module enables alternative frontends and programmatic access on top of
the same manager APIs the React SPA uses.
