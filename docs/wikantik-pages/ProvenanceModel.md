---
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
status: active
title: Provenance Model
cluster: wikantik-development
canonical_id: 01KQ0P44TZMB0TWR020ASY4NP5
type: article
tags:
- development
- knowledge-graph
- provenance
- trust
summary: 'How the Knowledge Graph records trust: four provenance values plus a machine/human
  review tier on every node and edge.'
---
# Provenance Model

Every node and edge in the Knowledge Graph carries two independent trust markers: a **provenance** value recording how it entered the graph, and a review **tier** recording how far it has been vetted. Both are columns on `kg_nodes` and `kg_edges` (provenance since V004, tier since V024). This keeps AI-generated content clearly distinguished from human-curated content.

## Provenance values

| Value | Meaning | Written by |
|-------|---------|------------|
| `human-authored` | A person authored or confirmed the entity | Node upserts from the admin REST surface and `curate_nodes`; confirming an AI-inferred entity in the page editor's Knowledge panel |
| `human-curated` | An admin explicitly curated or confirmed the edge | Edge upserts and `confirm` from the admin REST surface, `curate_edges`, and the page Knowledge panel |
| `ai-inferred` | Produced by the extraction and proposal pipeline | Every row materialized from a proposal, whether judge-approved (tier `machine`) or later human-approved (tier `human`) |
| `ai-reviewed` | Judge-approved AI content awaiting human curation (the label the admin UI badge describes) | No current write path assigns it; it remains a valid value for provenance filters and legacy rows, and the review state now lives in the tier |

## Review tiers

| Tier | Meaning |
|------|---------|
| `machine` | Materialized after the asynchronous judge LLM approved the proposal; not yet confirmed by a person |
| `human` | Authored, confirmed or approved by a person. This is also the column default, so rows curators write directly start here |

A tier moves in one direction. A judge approval materializes rows at `machine`; a human approval of the same proposal promotes them to `human` without changing their provenance; a human rejection retracts them. A curator can also elevate an edge in place (`curate_edges` `confirm`, or the Edge Explorer's confirm action), which sets tier `human` and provenance `human-curated` and writes an audit row to `kg_edge_audit`.

## Consumption filtering

- **Tier:** `min_tier` is `machine` by default, which shows both tiers; `human` gives the strict, person-vetted-only view. The `traverse` and `search_knowledge` tools on `/knowledge-mcp` accept `min_tier`, and so does `GET /api/knowledge/graph`, whose default comes from `wikantik.kg.read.default_min_tier` (`machine` unless changed).
- **Provenance:** `query_nodes` and `search_knowledge` take an optional `provenance_filter` list; omitting it applies no provenance filter.
- **Inclusion:** independently of provenance and tier, the agent-facing `/knowledge-mcp` hides entities whose source page the [KgInclusionPolicy](KgInclusionPolicy) excludes. Admin-MCP reads bypass that filter.

## Write-back and the ontology

Approving a `new-edge` proposal also writes the relationship into the source page's frontmatter (see [KnowledgeProposals](KnowledgeProposals)); the edge keeps its `ai-inferred` provenance. In the RDF ontology the tier surfaces as `prov:wasAttributedTo` (`wk:MachineAgent` or `wk:HumanAgent`) on each entity.

[{Relationships}]
