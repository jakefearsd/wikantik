---
canonical_id: 01KQ0P44SB1JGDBZKMCYXWCNA0
summary: Designed MCP tools that would power automated content auditing
tags:
- development
- mcp
- audit
- tools
type: article
status: designed
cluster: wikantik-development
date: '2026-03-20'
part-of:
- McpIntegration
enables:
- WikiAuditSkill
related:
- WikantikDevelopment
---
# MCP Audit Tools

The MCP audit tools are a designed but not yet built set of MCP tools that would power the [wiki audit skill](WikiAuditSkill). These tools would provide structured access to content quality metrics and validation results.

## Design Status

A design spec exists at `docs/superpowers/specs/2026-03-20-mcp-audit-tools-design.md`. The tools would be added to the existing authoring MCP server at `/mcp`.

## Planned Tools

- **scan_broken_links** — Find pages with links to non-existent pages
- **scan_missing_frontmatter** — Find pages missing required metadata fields
- **scan_orphaned_pages** — Find pages with no inbound references
- **validate_frontmatter** — Check frontmatter against schema conventions
- **audit_cluster** — Comprehensive quality report for a page cluster

[{Relationships}]
