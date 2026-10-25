---
kg_include: true
type: article
summary: April 2026 initiative to document Wikantik's own features as wiki pages with
  rich frontmatter, with a 2026-10 status note on what changed.
status: active
audience:
- humans
date: '2026-04-05'
canonical_id: 01KQ0P44RJAHVGMNSEMFWA09NY
tags:
- development
- knowledge-graph
- dogfooding
cluster: wikantik-development
title: Knowledge Graph Dogfooding
related:
- KnowledgeGraphCore
- WikantikDevelopment
---
# Knowledge Graph Dogfooding

> **Status (2026-10-13):** Partly shipped, partly superseded. The sub-feature pages exist ([GraphProjector](GraphProjector), [KnowledgeProposals](KnowledgeProposals), [KnowledgeAdminUi](KnowledgeAdminUi), [ProvenanceModel](ProvenanceModel), [FrontmatterConventions](FrontmatterConventions)), the Relationships plugin exists, and the Node Explorer has a status filter. The mechanism the plan rested on did not survive: the GraphProjector, which turned frontmatter relationships into Knowledge Graph edges, was retired on 2026-04-24, and `kg_edges.relationship_type` is now a closed vocabulary of 21 predicates. Frontmatter keys such as `part-of`, `enables` or `supersedes` are ordinary page metadata today, not graph edges, and the Relationships plugin renders edges only for a Knowledge Graph node whose name matches the page. The Knowledge Graph is now LLM-extracted entities ([WikantikKnowledgeGraph](WikantikKnowledgeGraph)); machine-queryable structure over the wiki's own pages comes from the structural index, the Page Graph ([PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph)) and the ontology. The text below is the original April 2026 plan.

This initiative makes the wiki the source of truth for its own development by documenting all features as wiki pages with rich frontmatter relationships. The knowledge graph then becomes a queryable map of the system — what exists, what depends on what, and what's been designed but not yet built.

## Goals

- Decompose the Knowledge Graph into focused sub-feature pages (GraphProjector, Proposals, Admin UI, Provenance, [Frontmatter Conventions](FrontmatterConventions))
- Backfill wiki pages for all features that have design specs or plans but no wiki representation
- Introduce a status vocabulary (`idea` → `designed` → `planned` → `active` → `deployed`) to track development lifecycle
- Add new relationship types (`part-of`, `enables`, `supersedes`) alongside existing `related` and `depends-on`
- Build a RelationshipsPlugin that renders a page's graph edges as navigable links
- Add status filtering to the Node Explorer for development tracking

## Why

Rich frontmatter relationships create a navigable graph that reduces the need for expensive code exploration. New ideas start as wiki pages and flow through the design/plan/build lifecycle. AI agents can query the graph to understand the system before diving into code.

## Design Spec

Full design: `docs/superpowers/specs/2026-04-05-kg-dogfooding-design.md` (no longer in the repository)

[{Relationships}]

---

**Cluster Hub:** [[WikantikDevelopmentHub|Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent Briefings]] (`wikantik-development`)
