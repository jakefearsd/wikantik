---
summary: Rules governing how YAML frontmatter keys are interpreted as graph properties, edges, and metadata
tags:
- development
- knowledge-graph
- frontmatter
- conventions
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
---
# Frontmatter Conventions

The FrontmatterRelationshipDetector determines whether each YAML frontmatter key becomes a node property or a graph edge. This convention-based approach requires no configuration — just follow the rules below.

## Detection Rules

A frontmatter key becomes a **graph edge** if:
1. Its value is a list of strings (e.g., `related: [PageA, PageB]`)
2. The key is NOT in the property-only exclusion list

Everything else becomes a **node property** stored in the JSONB `properties` column.

## Property-Only Keys

These keys are always treated as properties, never edges, even if their values are lists:

`tags`, `keywords`, `type`, `summary`, `date`, `author`, `cluster`, `status`, `title`, `description`, `category`, `language`

## Relationship Types

Any key not in the exclusion list that has a list value becomes an edge. Current relationship types in use:

| Key | Meaning | Example |
|-----|---------|---------|
| `related` | General association | `related: [McpIntegration, About]` |
| `depends-on` | Requires this to function | `depends-on: [DatabaseBackedPermissions]` |
| `part-of` | Sub-feature relationship | `part-of: [KnowledgeGraphCore]` |
| `enables` | Unlocks a capability | `enables: [AdminSecurityUi]` |
| `supersedes` | Replaces a predecessor | `supersedes: [JspToReactMigration]` |

## Status Vocabulary

The `status` field tracks development lifecycle:

| Status | Meaning |
|--------|---------|
| `idea` | Concept only, no spec |
| `designed` | Has a design spec |
| `planned` | Has spec + implementation plan |
| `active` | Currently being built |
| `deployed` | Running in the system |

## Node Type

The `type` frontmatter field maps to the node's `node_type` column. Common values: `hub`, `article`.

[{Relationships}]
