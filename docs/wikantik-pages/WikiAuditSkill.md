---
canonical_id: 01KQ0P44YZAEVHTS0BB2KS9ZQX
summary: Designed but not yet built skill for automated wiki content quality auditing
tags:
- development
- audit
- content-quality
- mcp
type: article
status: designed
cluster: wikantik-development
date: '2026-03-20'
depends-on:
- McpIntegration
related:
- McpAuditTools
- WikantikDevelopment
---
# Wiki Audit Skill

The wiki audit skill is a designed but not yet implemented capability for automated content quality auditing. An AI agent would use [MCP audit tools](McpAuditTools) to scan wiki pages for quality issues — broken links, missing frontmatter, orphaned pages, inconsistent formatting — and generate reports or proposals for fixes.

## Design Status

A design spec exists at `docs/superpowers/specs/2026-03-20-wiki-audit-skill-design.md`. No implementation plan has been created yet.

## Intended Capabilities

- Scan pages for broken internal links and missing references
- Identify pages missing required frontmatter fields
- Find orphaned pages with no inbound links
- Report formatting inconsistencies
- Propose frontmatter corrections via the knowledge proposal system

[{Relationships}]
