---
canonical_id: 01KQ0P44TZMB0TWR020ASY4NP5
summary: Three-tier trust model tracking the origin and review status of knowledge graph content
tags:
- development
- knowledge-graph
- provenance
- trust
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
---
# Provenance Model

Every node and edge in the knowledge graph carries a provenance value that tracks its origin and trust level. This three-tier model ensures that AI-generated content is clearly distinguished from human-authored content.

## Provenance Tiers

| Tier | Value | Meaning |
|------|-------|---------|
| 1 | `human-authored` | Originated in page YAML frontmatter, written by a human |
| 2 | `ai-inferred` | Proposed by an AI agent, awaiting human review |
| 3 | `ai-reviewed` | Proposed by AI, approved by a human, written back to frontmatter |

## Upgrade Path

Content moves through provenance tiers in one direction:
- `ai-inferred` → `ai-reviewed` (on proposal approval)
- `human-authored` is the terminal state for content created directly in frontmatter

## Consumption Filtering

The read-only consumption MCP endpoint (`/knowledge-mcp`) defaults to showing only `human-authored` and `ai-reviewed` content. Agents must explicitly opt in to see `ai-inferred` (speculative) content. This ensures agents querying the graph for grounded knowledge are not misled by unreviewed proposals.

## Write-Back Semantics

When an `ai-inferred` proposal is approved, it becomes `ai-reviewed`. The frontmatter write-back adds the relationship to the source page. If the source page is re-saved, the GraphProjector recognizes the existing `ai-reviewed` edge and does not downgrade it to `human-authored`.

[{Relationships}]
