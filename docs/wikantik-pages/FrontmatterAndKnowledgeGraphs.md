---
status: active
date: '2026-04-26'
title: Frontmatter and Knowledge Graphs
related:
- WikiPageTemplates
- WikiSearchOptimization
- PropertyGraphModel
canonical_id: 01KQ0P44QG244NCE3SC0M5EM18
cluster: wikantik-development
type: article
tags:
- frontmatter
- knowledge-graph
- structured-data
- wikantik-development
summary: How structured frontmatter feeds knowledge graphs — entities, relationships,
  metadata as first-class data — and the patterns that turn a wiki into a...
---
# Frontmatter and Knowledge Graphs

A wiki page is text. With frontmatter, it's also structured data. With consistent frontmatter conventions, the wiki becomes a queryable knowledge graph.

This page covers how frontmatter feeds knowledge graphs and the patterns for designing useful schema.

> **In Wikantik (2026-10-13):** page frontmatter does **not** populate the Knowledge Graph. The Knowledge Graph (`kg_*` tables) holds LLM-extracted entities built from page text; the projector that once turned frontmatter into graph nodes and edges was retired in April 2026 (see [GraphProjector](GraphProjector) and [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph)). Frontmatter instead drives the structural index (clusters, tags, canonical ids), the RDF ontology's page and concept graphs, the agent projection at `/api/pages/for-agent/{id}`, and one Knowledge Graph control, `kg_include`. The generic sections below describe the general model; read "edges" there as links between pages, not Knowledge Graph edges.

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

Validation enforces presence. (In Wikantik the frontmatter schema has no required fields: `canonical_id` is assigned automatically on first save, and field-value checks are advisory.)

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

## Knowledge graph mechanics (the generic model)

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

Wikantik's structural spine + agent-grade content design uses frontmatter heavily:

- `canonical_id`: stable identifier (ULID), assigned on first save and kept across renames
- `cluster`: thematic grouping; a scalar, or a list on non-hub pages. The one page with `type: hub` and a `cluster:` value declares that cluster (see [ClusterDeclarationDesign](ClusterDeclarationDesign))
- `tags`: topic tags
- `related`: cross-references
- `type`: page type (article, hub, reference, runbook, design)
- `status`: lifecycle state (draft, active, archived)
- `verified_at`, `verified_by`, `confidence`: verification metadata
- `audience`: humans / agents / both
- `kg_include`: per-page override of Knowledge Graph inclusion (see [KgInclusionPolicy](KgInclusionPolicy))

These feed:
- The structural-spine index
- The RDF ontology's page and concept graphs (tags and clusters become SKOS concepts)
- Agent-grade content projection (`/api/pages/for-agent/{id}`)
- Verification dashboards
- Knowledge Graph inclusion (`cluster` and `kg_include`), but not Knowledge Graph content

## Design principles

### Schema first

Decide what fields exist; what they mean; what values are valid. Then write pages following the schema.

Without schema, frontmatter is inconsistent; queries don't work.

### Validation

Tools validate frontmatter at save time. Pages with invalid schema reject (or warn).

Wikantik uses save-time enforcement via `StructuralSpinePageFilter` (it assigns a missing `canonical_id` and rejects an invalid `kg_include`) and `RunbookValidationPageFilter`. The schema-driven field checks (non-canonical `type` or `status`, odd dates, non-kebab clusters) are advisory warnings, and malformed YAML is rejected.

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

In Wikantik, a hub page has `type: hub` and a scalar `cluster:` value that declares the cluster; member pages join it by carrying the same `cluster:` value. The earlier `hubs:` membership field is retired (see [ClusterDeclarationDesign](ClusterDeclarationDesign)), so there is one authoritative mechanism rather than two lists to keep in step.

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
4. One authoritative membership mechanism (in Wikantik, the hub declares the cluster and members carry `cluster:`)
5. Periodic graph audit
6. Tool-supported updates (don't expect humans to maintain manually)

The Wikantik approach (structural spine + agent-grade content) shows mature implementation.

## Further Reading

- [WikiPageTemplates](WikiPageTemplates) — Templates encode frontmatter conventions
- [WikiSearchOptimization](WikiSearchOptimization) — Frontmatter affects search
- [PropertyGraphModel](PropertyGraphModel) — Knowledge graph foundations
