---
status: active
type: article
tags:
- development
- knowledge-graph
- graph-projector
- ontology
- apache-jena
canonical_id: 01KQ0P44QRKREJBW4VZ326B1F7
kg_include: true
date: '2026-04-04'
depends-on:
- FrontmatterConventions
audience:
- humans
cluster: wikantik-development
part-of:
- KnowledgeGraphCore
title: Graph Projector (Retired) and the Ontology Projectors
summary: The retired GraphProjector PageFilter (frontmatter to kg_nodes/kg_edges,
  removed 2026-04) and the Postgres-to-RDF ontology projectors of today.
---
# Graph Projector (Retired) and the Ontology Projectors

> **Status (2026-10-13):** The GraphProjector described in the first section was **retired and deleted on 2026-04-24** (agent-MCP redesign, cycle 6). Page frontmatter and body links no longer create Knowledge Graph nodes or edges, and the "Project All Pages" admin button is gone. The components that project data today are the four ontology projectors described below. This page keeps the slug so older links still land somewhere accurate.

## What the GraphProjector was

The GraphProjector was a PageFilter registered with the WikiEngine that fired on every page save and projected a page's YAML frontmatter into the Knowledge Graph (`kg_nodes` / `kg_edges`). In outline it:

1. parsed the saved page's frontmatter;
2. separated list-valued relationship keys from plain property keys with a `FrontmatterRelationshipDetector`;
3. upserted the page's own node (node type from the `type` field, `human-authored` provenance);
4. upserted an edge to each relationship target, creating a stub node when the target had no page;
5. added `links_to` edges for body links; and
6. removed stale `human-authored` edges while leaving `ai-inferred` and `ai-reviewed` edges alone.

It was unwired from the engine and its code deleted on 2026-04-24. Migration V012 (`retire_graph_projector_links_to_edges`) then purged every `links_to` edge it had written, and later migrations (V027, extended by V030) closed `kg_edges.relationship_type` to a vocabulary of 21 predicates, which excludes `links_to`. Wikilink structure now belongs to the Page Graph (see [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph)); the Knowledge Graph holds LLM-extracted entities (see [KnowledgeGraphCore](KnowledgeGraphCore)).

## What projects data today

The `wikantik-ontology` module (Apache Jena) contains four projectors. They read page metadata and PostgreSQL rows and write RDF into per-resource named graphs of a TDB2 store; they do not parse markdown bodies, and the public `/sparql` endpoint is read-only.

| Projector | Input | RDF it writes |
|-----------|-------|---------------|
| `PageProjector` | A page record (`canonical_id` plus frontmatter) | A page resource at `/id/page/{canonical_id}` typed `wk:Hub`, `wk:Article`, `wk:Reference`, `wk:Runbook` or `wk:DesignDoc`, with `dct:title`, `schema:url`, `dct:description`, `dct:created`, `dct:creator`, and one `dct:subject` for each tag and each cluster membership |
| `ConceptProjector` | The distinct tags and clusters across all pages | One `skos:Concept` per tag or cluster (with `skos:prefLabel`, in the `wk:WikiConcepts` scheme); a sub-cluster `parent/sub` gets `skos:broader` pointing at its parent |
| `EntityProjector` | A `kg_nodes` row and its outgoing edges | An entity resource at `/id/entity/{uuid}` typed by its entity class, with `rdfs:label`, `prov:wasAttributedTo` (`wk:MachineAgent` or `wk:HumanAgent` by tier) and `prov:wasDerivedFrom` the source page |
| `EdgeProjector` | A `kg_edges` row | One `wk:` predicate statement between two entity resources; an edge whose relationship type is outside the 21-predicate vocabulary is skipped and logged |

Each page, concept and entity graph is re-projected incrementally (`OntologyEventListener` on page save, rename and delete; `OntologyEntitySync` on Knowledge Graph changes), and a nightly rebuild from PostgreSQL acts as a reconciliation backstop. The operator guide is `docs/admin/OntologyManagement.md`; the layer is summarized in [WikantikKnowledgeGraph](WikantikKnowledgeGraph).
