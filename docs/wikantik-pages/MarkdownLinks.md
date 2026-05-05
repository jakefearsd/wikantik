---
status: official
cluster: wikantik-development
type: reference
title: Markdown Links in Wikantik
date: '2026-05-04'
summary: A guide to Wikantik's internal and external link syntax, including wiki brackets,
  canonical IDs, and attachments.
canonical_id: 01KQTD30XED8A95NHYMKK106KY
verified_at: '2026-05-04T21:10:44.598011331Z'
verified_by: gemini-cli-mcp-client
---
# Markdown Links in Wikantik

Wikantik uses an enhanced CommonMark parser (Flexmark) that supports both standard Markdown links and wiki-style shortcuts.

## Internal Wiki Links

Internal links should point to a page's **slug** (CamelCase name) or its **canonical_id**.

### 1. Wiki Shortcuts (Recommended)
Use double brackets for simple, readable internal links.
- `[[PageName]]` — Renders as [PageName]()
- `[[PageName|Display Text]]` — Renders as [Display Text](PageName)

### 2. Standard Markdown Links
Standard `[text](url)` syntax also works for internal pages.
- `[PageName]()` — The empty URL `()` triggers an internal slug resolution.
- `[Display Text](PageName)` — Links directly to the slug.

### 3. Linking by Canonical ID
For maximum stability across page renames, use the stable ULID.
- `[Display Text](id:01KQ44QTJGS34S8VKGDMPTK9TC)`
- Wikantik will resolve the ID to the current slug at render time.

## External Links

External links use standard Markdown syntax.
- `[Google](https://google.com)`
- `https://google.com` (Auto-linked)

## Linking to Attachments

To link to a file attachment on the current page:
- `[Download PDF](attachment:Proposal.pdf)`
- `![Embedded Image](attachment:Diagram.png)`

## Linking to Clusters and Tags

You can link to system-generated lists using special prefixes (if configured):
- `[Dev Cluster](cluster:wikantik-development)`
- `[Security Pages](tag:security)`

## Best Practices

1. **Prefer Slugs over IDs for Humans:** Slugs are easier to read and type for human editors.
2. **Use IDs for Automated Tools:** When agents or scripts generate links, they should prefer `id:` links to prevent breakage during future renames.
3. **Avoid Extensions:** Never link to `.md` or `.txt` files directly (e.g., `[Page](Page.md)`). Use the slug only.
