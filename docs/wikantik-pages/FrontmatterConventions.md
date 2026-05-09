---
status: official
cluster: wikantik-development
type: reference
title: Frontmatter Conventions
date: '2026-05-04'
summary: The definitive guide to YAML frontmatter standards, mandatory fields, and
  Knowledge Graph integration in Wikantik.
canonical_id: 01KQTD2K72YBHBZHHANDBZ65MJ
verified_at: '2026-05-04T21:10:44.598011331Z'
verified_by: gemini-cli-mcp-client
---
# Frontmatter Conventions

In Wikantik, YAML frontmatter is the "Structural Spine" of a page. It provides the machine-readable metadata that powers the knowledge graph, search reranking, and agent-grade content projection.

## Mandatory Fields

Every page MUST begin with a YAML block containing these fields:

```yaml
---
canonical_id: 01... (ULID)
title: "Human-Readable Title"
type: article | hub | runbook | reference | design
cluster: thematic-cluster-name
status: draft | provisional | official | stale
date: 'YYYY-MM-DD'
summary: "A one-sentence summary for search and previews."
---
```

### The Canonical ID
The `canonical_id` is a stable, 26-character ULID that identifies the page regardless of its slug or title. 
- **Auto-Injection:** If you create a page without an ID, the `StructuralSpinePageFilter` will inject one on the first save.
- **Never Modify:** Do not change or delete this ID once assigned, as it will break all incoming graph relationships.

## Optional & Special Fields

### Tags
A list of topic tags for granular discovery.
```yaml
tags:
- networking
- security
- java
```

### Relations (Knowledge Graph Edges)
Explicitly define how this page relates to other entities using their `canonical_id`.
```yaml
- target: 01KQ... (ULID)
  relationship: part-of | implements | example-of | prerequisite-for | supersedes
```

### Verification Metadata
Used to track content trustworthiness.
```yaml
verified_at: '2026-05-04T12:00:00Z'
verified_by: jakefear
confidence: authoritative | provisional | stale
```

### Agent-Specific Metadata
```yaml
audience: humans | agents | both
kg_include: true | false (override cluster-level KG policy)
```

## Best Practices

1. **Keep Summaries Concise:** Summaries are used in tool outputs and link previews. Aim for high density and low fluff.
2. **Use Stable Clusters:** Clusters should represent broad thematic areas (e.g., `wikantik-development`, `personal-finance`).
3. **Type Correctness:** Ensure `type` correctly reflects the page's purpose. `runbook` pages undergo additional schema validation.
4. **Relate Liberally:** The more explicit relations you define, the more useful the Knowledge Graph becomes for AI agents.
