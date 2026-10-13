---
summary: Authoritative reference for Wikantik page authoring—Markdown syntax, internal
  linking, math, plugins, and frontmatter schema.
date: 2026-05-02T00:00:00Z
cluster: wikantik-development
related:
- FrontmatterAndKnowledgeGraphs
- StructuralSpineDesign
- WikantikDevelopmentHub
auto-generated: false
canonical_id: 01KQ0P44XMGA8E1E7GAT4AYV43
title: Text Formatting Rules
type: article
tags:
- wikantik
- markdown
- authoring
- reference
- frontmatter
- plugins
status: active
---

# Text Formatting Rules

Wikantik pages use CommonMark-flavored Markdown stored as `.md` files. The rendering engine is based on [Flexmark](https://github.com/vsch/flexmark-java), extended with Wikantik-specific syntax for plugins, access control, and metadata. A page can start with a YAML frontmatter block for structured data; the wiki adds a `canonical_id` to it on save.

## I. Markdown Core

Wikantik supports standard CommonMark with the following active extensions:

| Extension | Syntax Example | Use Case |
| :--- | :--- | :--- |
| **Tables** | `| Col | Col |` | GitHub-style pipe tables. |
| **Footnotes** | `[^1]` and `[^1]:` | Citations and side-notes. |
| **Definitions** | `Term\n: Def` | Structural glossaries. |
| **Attributes** | `{.class}` | Appending CSS classes/IDs to blocks. |
| **Math** | `$E=mc^2$` | LaTeX-style inline and block math. |
| **Strikethrough** | `~~struck~~` | Double tilde only; a single `~x~` stays literal. |
| **Task lists** | `- [ ] to do`, `- [x] done` | Read-only checkboxes. |
| **Callouts** | `> [!note] Title` | Obsidian-style callout blockquote (`tip`, `warning`, `danger` and others). |

### Headings
Use ATX-style headings (`#` through `######`). 
*   **Recommendation:** Use exactly one `# Level 1` heading per page, mirroring the `title` field in the frontmatter.
*   **Avoid:** Setext headings (`===` or `---` underlines). A `---` directly under a line of text turns that line into a heading, so leave a blank line before a horizontal rule.

### Code Blocks
Prefer fenced code blocks with language hints for syntax highlighting:
````markdown
```java
public class HelloWorld { ... }
```
````

### Mathematical Notation
*   **Inline:** `$x + y = z$`. Do not include spaces between the delimiters and the content (e.g., use `$x$` not `$ x $`).
*   **Block:** Use `$$` delimiters on their own lines, with a blank line before and after the block. See [Mathematical Notation](https://github.com/jakefearsd/wikantik/blob/main/docs/user/MathematicalNotation.md).

## II. Internal Linking and References

### Wiki Links
Internal links resolve to page names (slugs) within the wiki. Both forms below work:
*   `[[PageName]]`, `[[PageName|label]]`, `[[PageName#Heading]]` (wikilinks; see [Linking](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Linking.md))
*   `[Architecture Guide](ArchitectureCritique)`
*   `![[PageName]]` on its own line embeds that page
*   `[Page Name]()` (legacy self-labeling link)

### Canonical IDs
Page slugs can change during refactoring. For permanent references in code, documentation, or runbooks, use the `canonical_id`.
*   **Resolution:** `GET /api/pages/by-id/{canonical_id}` and the `get_page_by_id` MCP tool resolve a ULID to the page's current name. Wiki links in a page body name pages, not IDs.
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
An ACL can sit anywhere in the page text outside a code span or code block. Valid permissions are `view`, `comment`, `edit`, `modify`, `upload`, `rename`, and `delete`; principals are login names, group names or roles, separated by commas.
*   **Example:** `[{ALLOW edit Admin,Editors}]`. Name individual people by their login name.

## IV. Core Plugins

Plugins extend the static Markdown with dynamic content.

*   **Table of contents:** there is no TOC plugin. The reader builds a contents rail from the page's `##` and `###` headings automatically.
*   **`Image`**: Provides fine-grained control over images (width, alignment, captions).
    *   `[{Image src='diagram.png' width='400' caption='System Flow'}]`
*   **`HubSetPlugin`**: Lists all pages belonging to a specific cluster hub. A plugin is named by its class, so `HubSet` alone does not resolve.
    *   `[{HubSetPlugin hub='JavaHub'}]()`
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
status: draft | active | archived
date: YYYY-MM-DD
summary: A one-sentence description.
tags: [tag1, tag2]
related: [PageSlug1, PageSlug2]
---
```

### Type-Specific Requirements
*   **`type: hub`**: Must include a `cluster` name. Hubs serve as the primary index for a topic.
*   **`type: runbook`**: Requires a structured `runbook:` block with at least one `when_to_use` entry, at least two `steps` and at least one `pitfalls` entry; `inputs`, `related_tools` and `references` are optional. A save that breaks these rules is refused.

### Verification Metadata
Stamp pages as reviewed using the `mark_page_verified` tool or manual entry:
*   `verified_at`: ISO timestamp.
*   `verified_by`: User login.
*   `confidence`: `authoritative` | `provisional` | `stale`.

## VI. Best Practices
1.  **Summary Quality:** Keep summaries between 50 and 160 characters. They drive search results and agent projections.
2.  **Prefer Markdown to HTML:** Raw HTML is rendered after it is sanitised (scripts, frames, forms, event-handler attributes and unsafe link schemes are removed). Use Markdown primitives or plugins for styling where you can.
3.  **Frontmatter vs. Body:** Store machine-queryable data (dates, types, relations) in frontmatter; store prose and examples in the body.
4.  **Authoring help:** See [Editing](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Editing.md) for the editor and [Frontmatter](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Frontmatter.md) for every field.
