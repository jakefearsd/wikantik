---
summary: Migration tool converting legacy JSPWiki syntax pages to Markdown format
tags:
- development
- migration
- markdown
- converter
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-01'
enables:
- BlogFeature
- KnowledgeGraphCore
related:
- WikantikDevelopment
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
