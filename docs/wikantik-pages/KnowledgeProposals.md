---
summary: Proposal workflow for AI-suggested knowledge enrichment with human approval and frontmatter write-back
tags:
- development
- knowledge-graph
- ai
- workflow
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
depends-on:
- ProvenanceModel
---
# Knowledge Proposals

The proposal system allows AI agents to suggest new nodes, edges, and property changes without directly modifying the knowledge graph. All proposals go through human review before becoming trusted knowledge.

## Proposal Types

- **new-node** — Suggest a new entity for the graph
- **new-edge** — Suggest a relationship between two existing or new nodes
- **new-property** — Suggest adding or modifying a node property
- **modify-property** — Suggest changing an existing property value

## Lifecycle

1. **Submit** — An AI agent submits a proposal via the MCP `propose_knowledge` tool or the REST API, including confidence score and reasoning
2. **Pending** — The proposal appears in the admin panel's Proposals tab for review
3. **Approve** — A human reviewer approves the proposal, which triggers edge/node creation in the graph with `ai-reviewed` provenance
4. **Reject** — A human reviewer rejects the proposal with a reason, which creates a rejection record preventing the same proposal from being resubmitted

## Frontmatter Write-Back

When a `new-edge` proposal with a `source_page` is approved, the system automatically updates the source page's YAML frontmatter to include the new relationship. For example, approving an edge from `WikantikDevelopment` to `ArtificialIntelligence` with relationship type `related` adds `ArtificialIntelligence` to the `related:` list in `WikantikDevelopment.md`. The subsequent page save triggers the GraphProjector, which recognizes the edge already exists at `ai-reviewed` provenance and avoids duplication.

## Rejection Memory

The `kg_rejections` table stores negative knowledge — relationships that were explicitly rejected. AI agents can query rejections via the MCP `list_rejections` tool to avoid re-proposing relationships that have already been evaluated and declined.

[{Relationships}]
