---
canonical_id: 01KQ0P44QG244NCE3SC0M5EM18
title: Frontmatter and Knowledge Graphs
type: article
cluster: wikantik-development
status: active
date: '2026-04-26'
summary: How structured frontmatter feeds knowledge graphs — entities, relationships,
  metadata as first-class data — and the patterns that turn a wiki into a queryable
  knowledge base.
tags:
- frontmatter
- knowledge-graph
- structured-data
- wikantik-development
related:
- WikiPageTemplates
- WikiSearchOptimization
- PropertyGraphModel
---
# Frontmatter and Knowledge Graphs

A wiki page is text. With frontmatter, it's also structured data. With consistent frontmatter conventions, the wiki becomes a queryable knowledge graph.

This page covers how frontmatter feeds knowledge graphs and the patterns for designing useful schema.

## What frontmatter is

YAML (or similar) at the top of a markdown file:

```yaml
---
title: Page Title
tags:
  - tag1
  - tag2
related:
  - OtherPage
status: published
---
```

The metadata is structured; the body is unstructured. Tools can use the metadata without reading the body.

## What frontmatter enables

### Filtering

"Show all pages tagged 'security'" is a query against frontmatter. No body parsing needed.

### Cross-reference graphs

`related:` field links pages. Across the wiki, this builds a graph.

### Lifecycle management

`status: deprecated` lets tools surface old pages.

### Search relevance

Title, tags, and other frontmatter fields can be weighted in search. See [WikiSearchOptimization](WikiSearchOptimization).

### Knowledge-graph queries

With consistent schemas, the wiki answers questions:
- "Which pages reference this concept?"
- "Which pages are stale (old + low confidence)?"
- "What's the dependency graph for this topic?"

## Frontmatter schemas

### Required fields

Some fields every page needs:

```yaml
title: ...
date: ...
type: article | hub | runbook | ...
```

Validation enforces presence.

### Optional fields

Fields that may or may not apply:

```yaml
tags: ...           # for topic indexing
related: ...        # cross-references
status: ...         # lifecycle
canonical_id: ...   # stable identifier
```

### Type-specific fields

A page with `type: runbook` might have a structured `runbook:` block:

```yaml
type: runbook
runbook:
  when_to_use: [...]
  steps: [...]
  references: [...]
```

A page with `type: article` doesn't need that.

The Wikantik agent-cookbook uses this pattern.

## Knowledge graph mechanics

### Nodes

Each page is a node.

### Edges

Frontmatter `related:`, `links_to:`, embedded references — these are edges.

### Properties

Frontmatter fields are properties of the node.

### Querying

Tools query the graph:

```
Find all pages where:
  type = "runbook"
  AND tags contain "security"
  AND status = "published"
```

This is a structured query; doesn't require text search.

## Wikantik specifics

Per CLAUDE.md, Wikantik's structural spine + agent-grade content design uses frontmatter heavily:

- `canonical_id`: stable identifier (ULID)
- `cluster`: thematic grouping
- `tags`: topic tags
- `related`: cross-references
- `hubs`: hub page memberships
- `type`: page type (article, hub, runbook)
- `status`: lifecycle state
- `verified_at`, `verified_by`, `confidence`: verification metadata
- `audience`: humans / agents / both

These feed:
- The structural-spine index
- Knowledge-graph traversal
- Agent-grade content projection (`/api/pages/for-agent/{id}`)
- Verification dashboards

## Design principles

### Schema first

Decide what fields exist; what they mean; what values are valid. Then write pages following the schema.

Without schema, frontmatter is inconsistent; queries don't work.

### Validation

Tools validate frontmatter at save time. Pages with invalid schema reject (or warn).

Wikantik uses save-time enforcement via `StructuralSpinePageFilter` and `RunbookValidationPageFilter`.

### Consistency

A field's name and meaning don't change. Consistent across all pages.

Renaming `related` → `links_to` is a major migration. Avoid unless necessary.

### Extensibility

New fields can be added; existing pages without them still work.

For required fields with defaults, this is straightforward. For required fields without defaults, migration is needed.

## Specific patterns

### Tag taxonomy

Tags should be from a controlled vocabulary. Otherwise users invent variants ("security", "Security", "infosec", "SecurityRelated").

Either:
- Suggest tags from existing vocabulary
- Auto-correct to canonical form
- Periodic taxonomy review

### Cross-reference enforcement

`related:` entries point to other pages. Validate the targets exist.

Tools surface broken cross-references.

### Hub pages

Hub pages have `type: hub` and list cluster members. The cluster's pages reference back via `hubs:` field.

Bidirectional links: hub lists members; members reference hub.

### Confidence and verification

Pages can have:

```yaml
verified_at: 2026-04-26
verified_by: alice
confidence: authoritative | provisional | stale
```

Tools surface stale or unverified pages.

### Cluster membership

Frontmatter `cluster: name` groups pages. Hub pages organize the cluster.

For 5+ pages on a topic, a cluster + hub provides structure.

## Tooling

### Frontmatter linters

Validate schema on save. Reject invalid pages.

### Index builders

Read all pages' frontmatter; build searchable index. Periodic rebuild.

### Cross-reference checkers

Find broken `related:` links; missing hub members.

### Visualization

Graph visualization of the knowledge graph. Useful for understanding wiki structure.

## Common failure patterns

### Inconsistent schemas

Different fields used; same fields with different values. Queries unreliable.

### No validation

Bad frontmatter slips in. Subtle issues compound.

### Tag drift

Tags multiply; no curation; eventual mess.

### Frontmatter as afterthought

Pages created without thinking about metadata. Later, can't be queried.

### Hand-maintained graphs

Manual updates to `related:` lists. Drift; missing cross-references.

## A reasonable approach

For wikis using frontmatter for knowledge graphs:

1. Define schema explicitly
2. Validate on save
3. Tags from controlled vocabulary
4. Bidirectional cross-references (hubs ↔ members)
5. Periodic graph audit
6. Tool-supported updates (don't expect humans to maintain manually)

The Wikantik approach (structural spine + agent-grade content) shows mature implementation.

## Further Reading

- [WikiPageTemplates](WikiPageTemplates) — Templates encode frontmatter conventions
- [WikiSearchOptimization](WikiSearchOptimization) — Frontmatter affects search
- [PropertyGraphModel](PropertyGraphModel) — Knowledge graph foundations
