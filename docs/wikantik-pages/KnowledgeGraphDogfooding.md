---
summary: Using the wiki's own knowledge graph to document and drive its development
tags:
- development
- knowledge-graph
- dogfooding
type: article
status: active
cluster: wikantik-development
date: '2026-04-05'
related:
- KnowledgeGraphCore
- WikantikDevelopment
depends-on:
- KnowledgeAdminUi
- FrontmatterConventions
---
# Knowledge Graph Dogfooding

This initiative makes the wiki the source of truth for its own development by documenting all features as wiki pages with rich frontmatter relationships. The knowledge graph then becomes a queryable map of the system — what exists, what depends on what, and what's been designed but not yet built.

## Goals

- Decompose the Knowledge Graph into focused sub-feature pages (GraphProjector, Proposals, Admin UI, Provenance, Frontmatter Conventions)
- Backfill wiki pages for all features that have design specs or plans but no wiki representation
- Introduce a status vocabulary (`idea` → `designed` → `planned` → `active` → `deployed`) to track development lifecycle
- Add new relationship types (`part-of`, `enables`, `supersedes`) alongside existing `related` and `depends-on`
- Build a RelationshipsPlugin that renders a page's graph edges as navigable links
- Add status filtering to the Node Explorer for development tracking

## Why

Rich frontmatter relationships create a navigable graph that reduces the need for expensive code exploration. New ideas start as wiki pages and flow through the design/plan/build lifecycle. AI agents can query the graph to understand the system before diving into code.

## Design Spec

Full design: `docs/superpowers/specs/2026-04-05-kg-dogfooding-design.md`

[{Relationships}]
