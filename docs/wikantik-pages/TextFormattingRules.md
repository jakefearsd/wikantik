---
canonical_id: 01KQ0P44XMGA8E1E7GAT4AYV43
title: Text Formatting Rules
type: article
cluster: wikantik-development
status: active
date: 2026-05-02T00:00:00Z
summary: Authoritative reference for Wikantik page authoring—Markdown syntax, internal linking, math, plugins, and frontmatter schema.
tags:
  - wikantik
  - markdown
  - authoring
  - reference
  - frontmatter
  - plugins
related:
  - FrontmatterAndKnowledgeGraphs
  - StructuralSpineDesign
  - WikantikDevelopmentHub
auto-generated: false
---

# Text Formatting Rules

Wikantik pages use CommonMark-flavored Markdown stored as `.md` files. The rendering engine is based on [Flexmark](https://github.com/vsch/flexmark-java), extended with Wikantik-specific syntax for plugins, access control, and metadata. Every page must start with a YAML frontmatter block for structured data.

## I. Markdown Core

Wikantik supports standard CommonMark with the following active extensions:

| Extension | Syntax Example | Use Case |
| :--- | :--- | :--- |
| **Tables** | `| Col | Col |` | GitHub-style pipe tables. |
| **Footnotes** | `[^1]` and `[^1]:` | Citations and side-notes. |
| **Definitions** | `Term\n: Def` | Structural glossaries. |
| **Attributes** | `{.class}` | Appending CSS classes/IDs to blocks. |
| **Math** | `$E=mc^2$` | LaTeX-style inline and block math. |

### Headings
Use ATX-style headings (`#` through `######`). 
*   **Recommendation:** Use exactly one `# Level 1` heading per page, mirroring the `title` field in the frontmatter.
*   **Avoid:** Setext headings (`===` or `---` underlines) as they conflict with frontmatter delimiters.

### Code Blocks
Prefer fenced code blocks with language hints for syntax highlighting:
````markdown
```java
public class HelloWorld { ... }
```
````

### Mathematical Notation
*   **Inline:** `$x + y = z$`. Do not include spaces between the delimiters and the content (e.g., use `$x$` not `$ x $`).
*   **Block:** Use `$$` delimiters on their own lines for display math.

## II. Internal Linking and References

### Wiki Links
Internal links use standard Markdown syntax but resolve to page names (slugs) within the wiki:
*   `[Architecture Guide](ArchitectureCritique)`
*   `[Page Name]()` (Self-labeling link)

### Canonical IDs
Page slugs can change during refactoring. For permanent references in code, documentation, or runbooks, use the `canonical_id`.
*   **Resolution:** The structural index resolves ULIDs to current slugs automatically.
*   **Syntax:** Links to sections use the heading slug: `[Security](#security-patterns)`.

## III. Wikantik Bracket Syntax

Bracket syntax `[{ ... }]` is used for dynamic functions and system controls.

| Function | Syntax | Description |
| :--- | :--- | :--- |
| **Plugins** | `[{PluginName param=val}]` | Execute a rendering plugin. |
| **ACLs** | `[{ALLOW view All}]` | Define page-level permissions. |
| **Variables** | `[{$variableName}]` | Inline a system or page variable. |
| **Setters** | `[{SET key=val}]` | Define a page-scoped variable. |

### Access Control Lists (ACLs)
Place ACLs at the top of the file, before the first heading. Valid permissions include `view`, `edit`, `modify`, `upload`, `rename`, and `delete`.
*   **Example:** `[{ALLOW edit Admin,Editors}]`

## IV. Core Plugins

Plugins extend the static Markdown with dynamic content.

*   **`TableOfContents`**: Generates a linked TOC from headings.
*   **`Image`**: Provides fine-grained control over images (width, alignment, captions).
    *   `[{Image src='diagram.png' width='400' caption='System Flow'}]`
*   **`HubSet`**: Lists all pages belonging to a specific cluster hub.
    *   `[{HubSet hub='JavaHub'}]`
*   **`RecentArticles`**: Renders a list of the most recently modified pages with excerpts.
*   **`ReferringPagesPlugin`**: Displays a "backlinks" list for the current page.

## V. Frontmatter Schema

The YAML frontmatter is the "Structural Spine" of the wiki.

```yaml
---
canonical_id: 01H8G3... (Auto-injected on save)
title: Page Title
type: article | hub | runbook | design | reference
cluster: thematic-grouping
status: active | draft | deprecated
date: YYYY-MM-DD
summary: A one-sentence description.
tags: [tag1, tag2]
related: [PageSlug1, PageSlug2]
---
```

### Type-Specific Requirements
*   **`type: hub`**: Must include a `cluster` name. Hubs serve as the primary index for a topic.
*   **`type: runbook`**: Requires a structured `runbook:` block containing `when_to_use`, `inputs`, `steps`, `pitfalls`, and `references`.

### Verification Metadata
Stamp pages as reviewed using the `mark_page_verified` tool or manual entry:
*   `verified_at`: ISO timestamp.
*   `verified_by`: User login.
*   `confidence`: `authoritative` | `provisional` | `stale`.

## VI. Best Practices
1.  **Summary Quality:** Keep summaries between 50 and 160 characters. They drive search results and agent projections.
2.  **No HTML:** Wikantik escapes raw HTML by default for security. Use Markdown primitives or plugins for all styling.
3.  **Frontmatter vs. Body:** Store machine-queryable data (dates, types, relations) in frontmatter; store prose and examples in the body.
