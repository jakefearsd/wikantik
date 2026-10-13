---
status: active
type: article
tags:
- development
- knowledge-graph
- admin
- ui
- react
canonical_id: 01KQ0P44RG9F6EFB0MPVN81K6S
kg_include: true
date: '2026-04-04'
depends-on:
- AdminSecurityUi
- KnowledgeProposals
audience:
- humans
cluster: wikantik-development
part-of:
- KnowledgeGraphCore
title: 'Knowledge Admin UI: Proposals, Explorers, Extraction and Hubs'
summary: 'The Knowledge Graph admin panel at /admin/knowledge-graph: eight tabs for
  proposals, extraction, nodes, edges, embeddings, hubs and LLM activity.'
---
# Knowledge Admin UI

The Knowledge Graph admin panel lives at `/admin/knowledge-graph` (admin sidebar, Knowledge & Search, Knowledge Graph). Its neighbour, `/admin/kg-policy` ("KG Policy"), is the cluster inclusion dashboard described in [KgInclusionPolicy](KgInclusionPolicy). Everything under `/admin/*` is protected by `AdminAuthFilter` (requires `AllPermission`), including the REST endpoints under `/admin/knowledge-graph/*` that the tabs call. When the Knowledge Graph subsystem is switched off (`wikantik.knowledge.enabled=false`) the panel shows a "disabled" notice and those endpoints answer 503. The panel curates the Knowledge Graph only; it does not touch the Page Graph (see [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph)).

## The eight tabs

| Tab | What it does |
|-----|--------------|
| **Proposals** | The review queue. Filter by All, Awaiting machine review, Machine approved, Machine rejected or Machine abstained. Each row shows the proposal type, source page, details, confidence, and a machine-verdict badge you can expand to read the judge's reasoning. Per-row and bulk actions: Approve, Reject (with a reason), Reject without a reason, and Judge (run the machine judge now). A status line shows how many proposals are still unjudged and how the last judge pass went, and a "Run judge runner" button starts a pass immediately. |
| **Extraction** | Runs the LLM entity extractor across every page (a batch of `POST /admin/knowledge-graph/extract-mentions`) and shows progress by pages and chunks. "Force re-extract" reprocesses pages that were already done; Cancel lets the in-flight page finish and then stops. Parallelism comes from `wikantik.knowledge.extractor.concurrency`. |
| **Node Explorer** | Browse nodes with a name search and node-type and status filters, plus a schema summary of node, edge and pending-proposal counts. The detail panel shows a node's properties, outbound and inbound edges, the chunks that mention it, and similar nodes (cosine similarity over the mention-centroid embeddings). Nodes with no source page are flagged as stubs. Delete removes a node and cascades to its edges; rows can be multi-selected for bulk delete. |
| **Edge Explorer** | Browse and search edges by source or target name and relationship type, with a provenance badge on each. Actions: New edge (written as `human-curated` and checked against the SHACL shapes), Confirm (elevate an edge in place to `human-curated`), Delete, "Delete + Prevent" (delete and record a rejection so the triple is not proposed again), an audit history per edge, and bulk delete. |
| **Content Embeddings** | Shows whether the mention-centroid index is ready, its vector dimension, and how many nodes have mentions. It also lists pages that have no frontmatter and offers "Backfill Frontmatter", which writes default frontmatter (`title`, `type: article`, extracted `tags` and `summary`, `auto-generated: true`) into them; it does not assign a `cluster`. |
| **Hub Proposals** | Generate and manage hub-membership proposals, with approve and reject individually, in bulk, or by a percentile threshold. |
| **Hub Discovery** | Run cluster-based discovery to find candidate topic hubs, accept or dismiss them (dismissed ones are remembered), and manage existing hub memberships. See `docs/admin/HubDiscovery.md`. |
| **LLM Activity** | A live view of recent and in-flight LLM calls (extraction, proposal judging, embeddings) for roughly the last hour. It is held in memory and cleared on restart. |

A "Clear all KG data" button in the toolbar deletes every node, edge, proposal, review and rejection, and cascades to mentions and node embeddings. It leaves wiki pages and the Page Graph alone and cannot be undone.

## Other Knowledge Graph surfaces

- **Reader graph view:** `/knowledge-graph` is a Cytoscape-based viewer over `GET /api/knowledge/graph`, with a filter panel, a `tier` toggle (`machine` or the stricter `human`) and a details drawer. The endpoint is public and returns only ids and relationship types, not page bodies.
- **Page editor:** the Knowledge tab next to Frontmatter shows the entities and relations for one page and lets an editor confirm, retype, add or remove them through `/api/page-knowledge/*`, under the page's edit permission and the same SHACL gate.
- **Querying the ontology:** there is no in-app SPARQL workbench. Use the public `/sparql` endpoint or the `sparql_query` tool on `/knowledge-mcp` (see [WikantikKnowledgeGraph](WikantikKnowledgeGraph)).
- **Administering the graph day to day:** [WikantikKnowledgeGraphAdmin](WikantikKnowledgeGraphAdmin).
