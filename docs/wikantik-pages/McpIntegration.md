---
summary: Model Context Protocol server enabling AI agents to read, write, search, and propose knowledge through 50+ tools
tags:
- development
- mcp
- ai
- agent-integration
- tools
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-12'
related:
- WikantikDevelopment
- KnowledgeGraphCore
- About
depends-on:
- KnowledgeGraphCore
---
# MCP Integration

The Model Context Protocol (MCP) integration is the bridge between AI agents and the wiki. Two MCP endpoints provide different levels of access:

## Authoring MCP (`/mcp`)

The primary MCP server exposes 47+ tools organized by capability:

- **Page operations** — read, create, edit, rename, delete pages
- **Search** — full-text search, reference lookups, backlink queries
- **Frontmatter** — read/write YAML metadata
- **Attachments** — upload, list, delete file attachments
- **Knowledge proposals** — propose new nodes/edges, list pending proposals, check rejections
- **Resources** — 6 MCP resources for schema discovery
- **Prompts** — 8 guided prompts for common workflows

## Consumption MCP (`/knowledge-mcp`)

A read-only endpoint for agents that only need to query the knowledge graph:

- `discover_schema` — Learn what node types, relationships, and properties exist
- `query_nodes` — Filter and search nodes
- `traverse` — BFS graph traversal with depth and provenance controls
- `get_node` — Detailed node information with edges
- `search_knowledge` — Full-text search across node names and properties

## Design Principles

1. **Separate endpoints** — Authoring and consumption are isolated so read-only agents can't modify content
2. **Provenance filtering** — Consumption MCP defaults to human-authored + ai-reviewed only
3. **Permission inheritance** — All tools respect the wiki's JAAS permission model

[{Relationships}]
