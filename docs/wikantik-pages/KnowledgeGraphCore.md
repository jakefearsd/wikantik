---
status: active
type: article
summary: LLM-extracted entities and typed relations in PostgreSQL kg_* tables, with
  a proposal, judge and review pipeline and MCP tools for agents.
related:
- WikantikDevelopment
- McpIntegration
- DatabaseBackedPermissions
- About
- ArtificialIntelligence
- GraphProjector
- KnowledgeProposals
- KnowledgeAdminUi
- ProvenanceModel
- FrontmatterConventions
title: Knowledge Graph Core
cluster: wikantik-development
tags:
- development
- knowledge-graph
- postgresql
- mcp
- ai
depends-on:
- McpIntegration
date: '2026-04-04'
canonical_id: 01KQ0P44RJSE08WJSB88N3A2JV
documents:
- AiAugmentedWorkflows
---
# Knowledge Graph Core

The Knowledge Graph (KG) is a semantic layer over wiki content that enables AI agents to discover relationships, traverse connections, and propose new knowledge — all grounded in human-authored content. Its nodes are LLM-extracted entities and its edges are typed relations between them; it is distinct from the Page Graph, whose edges are real wikilinks (see [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph)).

## Architecture

The KG lives in PostgreSQL, and every table is defined by a numbered migration under `bin/db/migrations/`. The core tables:

| Table | Purpose |
|-------|---------|
| `kg_nodes` | Entities: a unique `name`, a `node_type` (one of the 9 entity classes), the `source_page` they were extracted from, JSONB `properties`, a `provenance` and a review `tier` |
| `kg_edges` | Typed relationships between nodes. `relationship_type` is limited by a CHECK constraint to a closed vocabulary of 21 predicates; each edge carries a provenance and a tier |
| `kg_proposals` | Pending AI-suggested enrichments awaiting review, with the machine-judge verdict columns |
| `kg_proposal_reviews` | One row per machine or human verdict on a proposal |
| `kg_rejections` | Negative knowledge preventing re-proposals |
| `kg_edge_audit` | Append-only audit trail of admin-driven edge changes |
| `kg_judge_timeouts` | Per-proposal judge timeout tracking |
| `kg_content_chunks`, `chunk_entity_mentions`, `kg_node_embeddings` | The page chunks entities are extracted from, the bridge recording which chunk mentions which node, and a node-level embedding cache |
| `kg_cluster_policy`, `kg_policy_audit`, `kg_excluded_pages` | The inclusion policy that decides which pages feed the KG |

## How Entities Get In

Entities and relations enter mainly through two routes: the LLM extractors (chunk-level and page-level, running inside the wiki or offline through `wikantik-extract-cli`), which file proposals that a machine judge and then a person review; and direct curation by admins and agents (`curate_nodes`, `curate_edges`, the admin panel, the page editor's Knowledge panel). Page frontmatter and body links are not projected into the KG — the GraphProjector that once did this was retired in April 2026 (see [GraphProjector](GraphProjector)).

## Sub-Features

- **[GraphProjector](GraphProjector)** — the retired frontmatter-to-graph PageFilter, and the ontology projectors that exist today
- **[Knowledge Proposals](KnowledgeProposals)** — Proposal, judge, approval and rejection workflow with frontmatter write-back for approved edges
- **[Knowledge Admin UI](KnowledgeAdminUi)** — The eight-tab admin panel at `/admin/knowledge-graph`
- **[Provenance Model](ProvenanceModel)** — Provenance values and the machine/human review tier
- **[Frontmatter Conventions](FrontmatterConventions)** — Frontmatter field rules (see also [FrontmatterAndKnowledgeGraphs](FrontmatterAndKnowledgeGraphs) for how frontmatter relates to the KG)

## MCP Integration

Two separate MCP endpoints serve different use cases:

- **`/wikantik-admin-mcp`** (authoring + curation, 29 tools on the server overall). The KG-facing subset is `propose_knowledge`, `list_proposals`, `inspect_proposals`, `review_proposals`, `curate_nodes`, `curate_edges`, `list_orphaned_kg_nodes`, plus admin-bypass copies of `query_nodes` and `search_knowledge` so curators see entities they have just created.
- **`/knowledge-mcp`** (consumption, 21 read-only tools on the server overall when fully wired). The KG-facing subset is `discover_schema`, `query_nodes`, `traverse` (a co-mention traversal), `get_node`, `search_knowledge`, and `find_similar`; the ontology tools `get_ontology` and `sparql_query` sit beside them.

> The authoring endpoint is `/wikantik-admin-mcp`. An older `/mcp` path appears in early notes; it no longer exists. Curation writes are refused when an edge violates a SHACL shape.

The Relationships plugin at the foot of this page renders the KG edges of the node whose name equals the page name, and renders nothing when there is no such node.

[{Relationships}]
