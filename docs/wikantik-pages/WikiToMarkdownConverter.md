---
cluster: wikantik-development
title: Wiki to Markdown Converter
related:
- WikantikDevelopment
type: article
summary: Migration tool converting legacy JSPWiki syntax pages to Markdown format
status: active
date: '2026-04-01'
uses:
- BlogFeature
canonical_id: 01KQ0P44Z5CB0MC0BGG3KXX5XC
enables:
- BlogFeature
- KnowledgeGraphCore
tags:
- development
- migration
- markdown
- converter
---
# Wiki-to-Markdown Converter

The wiki-to-markdown converter migrated legacy JSPWiki syntax pages to Markdown format. This was a prerequisite for the knowledge graph (which parses YAML frontmatter from Markdown pages) and the [blog feature](BlogFeature) (which uses Markdown rendering).

## Conversion Rules

- JSPWiki headings (`!!!`, `!!`, `!`) converted to Markdown headings (`#`, `##`, `###`)
- Wiki links (`[PageName]`) converted to [Markdown links](MarkdownLinks)
- Inline formatting (bold, italic) converted to Markdown equivalents
- Plugin syntax preserved as-is (Flexmark handles plugin rendering)

## Frontmatter Addition

During conversion, YAML frontmatter was added to pages that lacked it, with basic metadata fields (summary, tags, type, status).

[{Relationships}]
