---
canonical_id: 01KQ0P44QRKREJBW4VZ326B1F7
summary: PageFilter that synchronizes YAML frontmatter to the knowledge graph on every page save
tags:
- development
- knowledge-graph
- synchronization
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
depends-on:
- FrontmatterConventions
---
# Graph Projector

The GraphProjector is a PageFilter registered with the WikiEngine that fires on every page save. It bridges the gap between human-authored wiki content and the knowledge graph by automatically projecting YAML frontmatter into graph nodes and edges.

## How It Works

1. **Parse** — Extracts YAML frontmatter from the saved page via FrontmatterParser
2. **Detect** — Passes frontmatter to FrontmatterRelationshipDetector, which separates properties from relationships
3. **Upsert node** — Creates or updates the page's node in `kg_nodes` with detected properties, node type from the `type` field, and `human-authored` provenance
4. **Upsert edges** — For each detected relationship, resolves the target node by name (creating a stub if it doesn't exist) and upserts the edge
5. **Diff and remove** — Compares current edges against what was just projected and removes stale `human-authored` edges that no longer appear in frontmatter
6. **Preserve AI edges** — Stale-edge removal skips `ai-inferred` and `ai-reviewed` edges so approved proposals survive frontmatter edits

## Idempotency

Re-saving a page with unchanged frontmatter is a no-op — the upsert logic uses `ON CONFLICT` (PostgreSQL) or `MERGE INTO` (H2) to update timestamps without creating duplicates.

## Bulk Projection

The admin panel provides a "Project All Pages" button that iterates every wiki page and runs the projector. This is used to seed the graph after initial deployment or schema changes.

[{Relationships}]
