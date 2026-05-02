---
canonical_id: 01KQ0P44XMGA8E1E7GAT4AYV43
title: Text Formatting Rules
type: article
cluster: wikantik-development
status: active
date: '2026-04-29'
summary: Comprehensive reference for authoring Wikantik pages — Markdown syntax, internal wiki links, math, plugins, ACLs, page variables, and the YAML frontmatter schema.
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
---
# Text Formatting Rules

Wikantik pages are CommonMark-flavoured Markdown stored as plain `.md` files. The renderer is [Flexmark](https://github.com/vsch/flexmark-java) with a small set of extensions and a Wikantik-specific bracket syntax for plugins, ACLs, and variables. Every page may carry a YAML frontmatter block that the engine treats as structured metadata.

This page is the working reference for authors. Treat it as authoritative when there's any disagreement with older docs.

## TL;DR

```markdown
---
title: Example Page
type: article
cluster: example-cluster
status: active
date: '2026-04-29'
summary: One-sentence description.
tags: [example, reference]
related:
  - SomeOtherPage
---

# Example Page

Body text in **CommonMark Markdown**. Internal wiki link: [Some Other Page](SomeOtherPage).
External link: <https://example.com>.

[{TableOfContents}]
```

The body uses standard Markdown. The fenced YAML at the top is optional, but every page maintained through the structural spine is expected to carry one. The `[{...}]` form is Wikantik's plugin syntax — covered below.

## Markdown core

Wikantik uses the **CommonMark** parser profile with these Flexmark extensions enabled by default:

| Extension | Effect |
|---|---|
| `Tables` | GitHub-style pipe tables |
| `Footnotes` | `[^1]` references and `[^1]: …` definitions |
| `Definition` | `term\n: definition` definition lists |
| `TocExtension` | Built-in `[TOC]` markers (also see the `TableOfContents` plugin below) |
| `Attributes` | `{.class #id key=value}` attribute spans on inline and block nodes |
| `GitLab` (block math only) | `$$ … $$` display math, plus a custom `$ … $` inline parser |

Anything that works in standard CommonMark works here. The notes below cover the cases worth being deliberate about.

### Headings

ATX headings only. The renderer expects `#` through `######` followed by a space:

```markdown
# Page Title (only one per page; usually mirrors `title:` in frontmatter)
## Section
### Subsection
```

Setext headings (`===` / `---` underlines) parse but conflict visually with the YAML frontmatter delimiter — avoid them.

### Paragraphs and line breaks

Blank line separates paragraphs. A trailing `  ` (two spaces) or a `\` at end-of-line forces a hard line break inside a paragraph.

### Emphasis

```markdown
*italic*  or  _italic_
**bold**  or  __bold__
***bold italic***
~~strikethrough~~ is NOT enabled — Wikantik disables the GitLab del-parser.
`inline code`
```

### Lists

```markdown
- Bulleted item
- Second item
  - Nested (two-space indent)

1. Numbered item
2. Second
   1. Nested
```

Continuation lines on a list item must be indented to align with the first character after the marker.

### Block quotes

```markdown
> Standard CommonMark blockquote.
> Multi-line continues with another `>`.
```

The GitLab blockquote parser (`>>>`) is **disabled**.

### Code

Indented code blocks (4-space indent) work but are discouraged. Prefer fenced code blocks with a language hint:

````markdown
```java
final var foo = bar();
```

```bash
mvn clean install
```
````

A bare ` ``` ` fence renders as a generic code block. Languages enable syntax highlighting in the React renderer.

### Tables

```markdown
| Column A | Column B |
|----------|----------|
| value    | value    |
| value    | value    |
```

Alignment with `:---`, `---:`, `:---:` works. Tables don't need leading/trailing pipes but pages in this wiki use them by convention.

### Footnotes

```markdown
Body text with a footnote.[^name]

[^name]: The footnote body — can span lines as long as continuation lines are indented.
```

Footnote IDs can be numbers or words. Wikantik renders them with a `wikipage` CSS class for styling.

### Definition lists

```markdown
Term
: Definition for the term.
: A second definition for the same term.

Another term
: Definition.
```

### Horizontal rules

`---`, `***`, or `___` on their own line. **Do not** use a bare `---` immediately after the opening `---` of frontmatter — the parser will treat it as the closing frontmatter delimiter. Use `***` for in-body rules.

### Inline HTML

HTML escaping is **on by default** (`wikantik.translatorReader.allowHTML = false`). Raw `<script>` and other HTML is rendered as text. Operators can lift this restriction site-wide; on default deployments, assume HTML is escaped and use Markdown.

### Attributes

The Attributes extension lets you tack class/id/style onto an inline or block:

```markdown
A highlighted span{.callout #intro}.

## Section heading {.muted}
```

Used sparingly across the wiki. Most styling comes from the React layer, not author-applied classes.

### Math

Wikantik registers its own inline math parser so the standard convention works:

```markdown
Inline: $E = mc^2$
```

Display math uses `$$ … $$` on lines of their own:

```markdown
$$
\nabla \cdot \mathbf{E} = \frac{\rho}{\varepsilon_0}
$$
```

The display preprocessor rewrites `$$ … $$` into a fenced ` ```math ` block before parsing. Indentation under list items is preserved, so list-nested math works.

The inline `$ … $` form requires the content to **not** start or end with a space. `$ x $` is treated as literal text; `$x$` renders as math.

## Internal links and wiki references

### Plain Markdown link forms

Wikantik treats Markdown links specially based on the URL:

| Form | Rendered as |
|---|---|
| `[Display](PageName)` | Internal wiki link to `PageName` |
| `[PageName]()` | Internal wiki link; display equals the URL slug |
| `[Display](https://…)` | External link |
| `[Display](#anchor)` | Anchor on the current page |
| `[Display](attachment.png)` | Attachment link, if `attachment.png` exists on the page |
| `<https://example.com>` | Auto-linked URL |

The wiki resolver runs after Flexmark's link parser. An empty URL (`[Foo]()`) means "the page named `Foo`." A non-empty URL that doesn't start with a scheme is also treated as an internal page name; an attachment match takes precedence over a page lookup.

### Linking by canonical_id

Slugs change when pages get renamed. Canonical IDs are stable for the life of the page. When you need a permanent reference (frontmatter `relations:`, runbook `references:`, agent context), prefer the canonical_id. The structural index resolves it:

```markdown
[{Relationships}]
```

In prose, `[Display](PageName)` is fine — but if you find yourself fixing link rot, it's a sign the link should have been written against a stable identifier. See [CitingAWikiPage](CitingAWikiPage) for the procedure.

### Section anchors

Headings get auto-generated slug anchors. Link to them with `[Display](PageName#section-slug)` for cross-page links or `[Display](#section-slug)` within the same page. The slug is the heading text lowercased with non-alphanumerics replaced by `-`.

## Wikantik bracket syntax

Four constructs use `[{ … }]` brackets. The parser auto-appends `()` so they parse as Markdown links and survive Flexmark's link-reference normalization:

| Form | Purpose |
|---|---|
| `[{PluginName param=value}]` | Invoke a plugin |
| `[{ALLOW <perm> <principal>}]` | Page-level access control entry |
| `[{$variableName}]` | Inline a wiki variable |
| `[{SET key=value}]` | Set a page-scoped variable |

You **don't** need to write the trailing `()` yourself — the parser adds it. Both `[{TableOfContents}]` and `[{TableOfContents}]()` work; the first form is the convention.

### Page ACLs

```markdown
[{ALLOW view All}]
[{ALLOW edit Admin,Editors}]
[{ALLOW delete Admin}]
```

Permissions: `view`, `comment`, `edit`, `modify`, `upload`, `rename`, `delete`. Principals are role names, group names, or user logins. Multiple principals comma-separated, no space. Multiple `ALLOW` lines combine.

ACL entries inside the body apply to the whole page. They can sit anywhere; convention is to place them at the very top, before the `# Heading`.

### Page variables

```markdown
[{SET categories='History,Naval History'}]
[{SET alias='SomeOtherPage'}]
```

Reading a variable:

```markdown
Welcome to [{$applicationname}].
```

Common built-ins: `$applicationname`, `$pagename`, `$baseurl`. The full list lives in `WikiVariables` (when present); for ad-hoc data, use frontmatter rather than `SET`.

## Plugins

Plugins are inline functions that render dynamic content. The general form:

```markdown
[{PluginName param1=value1 param2='quoted value'}]
```

Quote a value if it contains spaces, commas, or punctuation. Boolean parameters take `true` / `false`. Plugins resolve from `com.wikantik.plugin` by default; the search path is configurable in `wikantik.properties` (`wikantik.plugin.searchPath`).

A complete `INSERT` form also works for plugins outside the search path:

```markdown
[{INSERT com.wikantik.plugin.Image WHERE src='diagram.png' caption='Architecture'}]
```

### Plugins shipped with Wikantik

| Plugin | Purpose |
|---|---|
| `TableOfContents` | Renders a TOC built from the page's headings. Common on long reference pages. |
| `Image` | Embeds an image with explicit attributes (`src`, `align`, `width`, `height`, `alt`, `caption`, `link`, `target`, `style`, `class`, `border`, `title`). |
| `InsertPage` | Inlines another page's rendered content. Useful for shared sidebars. |
| `IfPlugin` | Conditional rendering based on user, group, page state. *Not* a security control — use ACLs for that. |
| `RecentChangesPlugin` | Date-sorted list of recently changed pages. |
| `RecentArticles` | Modern article-listing variant with excerpts and metadata. |
| `LatestArticle` | The single most recent blog entry for the current user. |
| `ArticleListing` | Lists blog entries with date, title, optional excerpt. Used on `Blog.md`. |
| `BlogListing` | Discovers and lists every blog in the wiki. |
| `IndexPlugin` | Page index by name pattern; defaults to all pages. |
| `Search` | Embed a search-results widget on a page. |
| `ReferringPagesPlugin` | Pages that link **to** this one. |
| `ReferredPagesPlugin` | Pages this one links **to**. |
| `ReferringUndefinedPagesPlugin` | Pages with dead outbound links. |
| `UndefinedPagesPlugin` | Page names that are linked-to but don't yet exist. |
| `UnusedPagesPlugin` | Pages with no inbound references. |
| `HubSetPlugin` | Renders all pages belonging to a named hub (`hub='SomeHub'`). |
| `RelationshipsPlugin` | Renders the page's typed knowledge-graph relations as navigable links. Convention: place `[{Relationships}]` at the bottom of the body. |
| `AliasPlugin` / `[{ALIAS target}]` | Redirects this page to `target`. |
| `CurrentTimePlugin` | Current date/time in a `SimpleDateFormat` pattern. |
| `JDBCPlugin` | Read-only `SELECT` queries against a configured datasource, rendered as an HTML table. |

Per-plugin parameter docs live in the source tree under `wikantik-main/src/main/java/com/wikantik/plugin/`. The Javadoc on each class is the source of truth.

### Plugin examples

```markdown
[{TableOfContents}]

[{Image src='diagram.png' caption='System architecture' width='600' align='center'}]

[{InsertPage page='SharedFooter'}]

[{HubSet hub='JavaHub'}]

[{ReferringPagesPlugin max=20}]

[{IfPlugin group='Admin'}]
This block only renders for admins.
[{IfPlugin}]

[{CurrentTimePlugin format='yyyy-MM-dd HH:mm'}]

[{Relationships}]
```

## Attachments and images

The simplest image embed is a Markdown image:

```markdown
![Alt text](attachment.png)
```

If `attachment.png` is uploaded to the current page, the renderer resolves it. For control over alignment, sizing, captions, or click-through links, use the `Image` plugin shown above.

External images are allowed if the URL matches the configured inline-image patterns (set per deployment). Otherwise they render as a link.

## Other rendering notes

- **Sanitization.** The HTML output is run through `WikantikHtmlSanitizer` and `SafeLinkAttributeProvider`. Anchors get `rel="nofollow"` for external URLs; on-disk links get the right page/edit URL. Trying to bypass sanitization with raw HTML won't work on default deployments.
- **ReferenceManager.** Internal links are tracked at parse time so the wiki knows which pages reference which. This powers `ReferringPagesPlugin`, broken-link reports, and the structural-spine cross-reference checker.
- **Cache.** Rendered HTML lives in `wikantik.renderCache` (1h TTL, 10K entries). Saves invalidate the cache for the affected page; plugin output that depends on other pages may briefly be stale until those pages are re-rendered.

---

## Frontmatter

Every page may begin with a YAML block delimited by `---` lines. Example minimum:

```yaml
---
title: Some Page
type: article
cluster: example-cluster
status: active
---
```

The parser is forgiving — pages without frontmatter render fine — but the structural spine, knowledge graph, agent-grade projection, and admin tooling all read frontmatter. A page without it is invisible to those systems.

### Parser rules

- The opening `---` must be on the first line of the file.
- The closing `---` must be on its own line.
- Both LF and CRLF line endings are supported.
- Empty frontmatter (`---\n---\n`) parses as an empty metadata map.
- Malformed YAML logs a warning and the page is treated as having no frontmatter — the body is **not** lost.
- The `StructuralSpinePageFilter` runs in `preSave` and **auto-injects a `canonical_id`** if you save a page without one. You don't have to assign ULIDs by hand.

### Standard fields

These appear across most pages. Some are required by validators or filters; the rest are conventions everyone follows.

| Field | Type | Notes |
|---|---|---|
| `canonical_id` | string (ULID) | Stable lifetime-of-page identifier. Auto-assigned on save if absent. **Never edit.** |
| `title` | string | Display title. Mirrors `# Heading` at top of body. |
| `type` | string | `article`, `hub`, `runbook`, `design`, `index`. Drives validators. |
| `cluster` | string | Thematic grouping (kebab-case): `personal-finance`, `java`, `agentic-ai`, `wikantik-development`, … |
| `status` | string | `active`, `draft`, `deprecated`, `stale`. Lifecycle state. |
| `date` | string (`YYYY-MM-DD`) | Original publication or last-significant-edit date. |
| `summary` | string | One-sentence description. Used in agent projections, search snippets, and hub indexes. |
| `tags` | list[string] | Lowercase, kebab-case. Topic tags. Controlled-ish vocabulary; avoid one-off tags. |
| `related` | list[string] | Slug references to peer pages. Conventionally bidirectional with hubs. |
| `hubs` | list[string] | Hub-page slugs this page belongs to (e.g. `JavaHub`, `DataStructuresHub`). |
| `audience` | string or list | `humans`, `agents`, or `[humans, agents]`. Default `humans`. |
| `aliases` | list[string] | Alternate slugs that resolve here. |

Slug references in `related:` and `hubs:` use the page's filename without the `.md` extension. Hub references use the canonical `TopicHub` form (no `+`, no spaces) — see the existing hub pages for examples.

### Verification metadata

Pages that have been reviewed by a human (or trusted-author check) carry verification fields. Authors stamp these via the `mark_page_verified` MCP tool; you can also write them by hand:

```yaml
verified_at: 2026-04-26T16:45:00Z
verified_by: jakefear
confidence: authoritative
```

`confidence` is one of:

- `authoritative` — verified by a trusted author within the staleness window
- `provisional` — unverified or verified-by an untrusted source
- `stale` — verification older than `wikantik.verification.stale_days` (default 90 days)

Most authors leave `confidence` unset and let `ConfidenceComputer` derive it from `verified_at` plus the trusted-authors registry. Pin it explicitly only when you need to override the computation.

### Knowledge-graph inclusion

Pages can opt in or out of knowledge-graph extraction:

```yaml
kg_include: true   # force include even if cluster policy excludes
kg_include: false  # force exclude even if cluster policy includes
```

Default behaviour follows the cluster's `kg_cluster_policy` row (default-exclude, configurable per cluster). Authors override per-page only when they have a reason; see [KgInclusionPolicy](KgInclusionPolicy).

### Type-specific blocks

#### `type: hub`

Hub pages curate a cluster. Conventional shape:

```yaml
title: ExampleHub
type: hub
cluster: example
status: active
date: '2026-04-29'
summary: Index of pages on …
tags:
  - hub
  - example
related:
  - PeerHub1
  - PeerHub2
```

The hub's body lists member pages under `## Section` headings. Hub names follow the `TopicHub` convention (no `+`, no internal spaces). Members opt in by listing the hub in their `hubs:` field.

#### `type: runbook`

Runbooks (the agent-cookbook cluster) require a structured `runbook:` block with six keys. `RunbookValidationPageFilter` rejects saves that don't conform:

```yaml
type: runbook
audience: [agents, humans]
runbook:
  when_to_use:
    - Trigger condition 1
    - Trigger condition 2
  inputs:
    - What the agent needs to have on hand
  steps:
    - Numbered procedure
    - Step 2
  pitfalls:
    - Things that go wrong
  related_tools:
    - search_knowledge        # bare snake_case tool name
    - /api/pages/by-id/{id}   # or an endpoint pattern
  references:
    - 01H8G3Z1PRN5Q3X4T9M2V7K0AB   # canonical_id
    - SomePageName                  # or page title resolvable via PageManager
```

`references:` entries resolve to either canonical_ids (via the structural index) or page titles (via `PageManager.pageExists`). `related_tools:` entries match `/api`, `/knowledge-mcp`, `/wikantik-admin-mcp`, `/tools/*`, or a bare snake_case tool name.

The same validator runs at read time when assembling the `/for-agent` projection — invalid runbooks land with `runbook: null` rather than poisoning the response.

### Save-time enforcement

The following filters run on every page save:

- `StructuralSpinePageFilter` — auto-injects `canonical_id`; rejects invalid `relations:`. Toggle: `wikantik.structural_spine.enforcement.enabled` (default `true`).
- `RunbookValidationPageFilter` — runs only when `type: runbook`. Toggle: `wikantik.runbook.enforcement.enabled` (default `true`).
- `FrontmatterDefaultsFilter` — fills in conventional defaults (`status`, `audience`, etc.) when missing.

If a save fails validation, the filter raises a `FilterException` with a specific reason. Fix the frontmatter and save again — the page content is never silently dropped.

### What goes in frontmatter vs the body

- **Frontmatter**: structured, machine-readable, queryable. Every value is something an MCP tool, search index, or admin dashboard might filter on.
- **Body**: prose, code, examples — anything humans (or agents) read linearly.

Don't duplicate body content in frontmatter (`summary` is a one-line distillation, not a copy of the intro paragraph). Don't put structured data in the body when a frontmatter field exists for it.

## Further reading

- [FrontmatterAndKnowledgeGraphs](FrontmatterAndKnowledgeGraphs) — design rationale and patterns for frontmatter as graph data
- [StructuralSpineDesign](StructuralSpineDesign) — the structural-spine machinery that consumes `canonical_id` and `relations:`
- [AgentGradeContentDesign](AgentGradeContentDesign) — verification metadata, runbook type, `/for-agent` projection
- [KgInclusionPolicy](KgInclusionPolicy) — operator guide for `kg_include` and cluster policy
- [WikantikDevelopmentHub](WikantikDevelopmentHub) — broader cluster index
