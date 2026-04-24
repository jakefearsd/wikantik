---
canonical_id: 01KQ0P44RJSE08WJSB88N3A2JV
summary: Property graph over wiki content with PostgreSQL storage, frontmatter synchronization,
  MCP tools, and proposal-based AI enrichment
tags:
- development
- knowledge-graph
- postgresql
- mcp
- ai
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
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
depends-on:
- McpIntegration
documents:
- AiAugmentedWorkflows
---
# Knowledge Graph Core

The knowledge graph is a semantic layer over wiki content that enables AI agents to discover relationships, traverse connections, and propose new knowledge — all grounded in human-authored content.

## Architecture

Four PostgreSQL tables form the graph:

| Table | Purpose |
|-------|---------|
| `kg_nodes` | Entities (wiki pages, concepts, stubs) with JSONB properties |
| `kg_edges` | Typed relationships between nodes with provenance tracking |
| `kg_proposals` | Pending AI-suggested enrichments awaiting human review |
| `kg_rejections` | Negative knowledge preventing re-proposals |

## Sub-Features

- **[GraphProjector](GraphProjector)** — PageFilter that synchronizes frontmatter to the graph on every page save
- **[Knowledge Proposals](KnowledgeProposals)** — Proposal/approval/rejection workflow with frontmatter write-back
- **[Knowledge Admin UI](KnowledgeAdminUi)** — Three-tab admin panel for proposals, node explorer, and edge explorer
- **[Provenance Model](ProvenanceModel)** — Three-tier trust model (human-authored → ai-inferred → ai-reviewed)
- **[Frontmatter Conventions](FrontmatterConventions)** — Rules for how frontmatter keys become properties vs. edges

## MCP Integration

Two separate MCP endpoints serve different use cases:
- `/mcp` (authoring) — 3 knowledge tools: `propose_knowledge`, `list_proposals`, `list_rejections`
- `/knowledge-mcp` (consumption) — 5 read-only tools: `discover_schema`, `query_nodes`, `traverse`, `get_node`, `search_knowledge`

[{Relationships}]
