---
title: Markdown Links in Wikantik
cluster: wikantik-development
date: '2026-05-15'
kg_include: true
status: active
type: reference
canonical_id: 01KQTD30XED8A95NHYMKK106KY
audience:
- humans
summary: 'Link syntax in Wikantik: [text](Page) and [[Page|alias]] wikilinks, heading
  anchors, attachments, ![[embeds]], external links, and the Page Graph.'
tags:
- markdown
- links
- wiki-syntax
- wikilinks
- wikantik-development
---
# Markdown Links in Wikantik

Wikantik uses an enhanced CommonMark parser (Flexmark) that supports both standard Markdown links and wiki-style shortcuts. A page can link to other pages with standard Markdown links or with Obsidian-style `[[wikilinks]]`; both are first-class and neither is being phased out. Every link to a page becomes an edge of the Page Graph, described at the end. The examples below use [WikiName](WikiName) as the target page.

## Linking to another page

Internal links point at a page's **slug**, its CamelCase name.

- `[label](WikiName)` — a Markdown link, shown as `label`. The target is matched against page names exactly (plural forms are matched too).
- `[WikiName]()` — a legacy form: empty parentheses make the link text the page name.
- `[[WikiName]]` — a wikilink, shown as `WikiName`.
- `[[WikiName|label]]` — a wikilink shown as `label`. Inside a table cell write the separator as `\|`.
- `[[WikiName#Heading]]` — a link to a heading on that page, shown as `WikiName > Heading`.
- `[[#Heading]]` — a link to a heading on the current page.

Rules for wikilinks:

- The text between the brackets cannot be empty, cannot start with a space and cannot span a line, so shell conditionals such as `[[ -f x ]]` stay plain text.
- A wikilink inside a code span or fenced code block, or with a backslash before the opening bracket, is plain text.
- A wikilink target is resolved in order: the exact page name, then the page name ignoring case, then a page `title` or an entry in `aliases` ignoring case. When two pages tie, the one that sorts first alphabetically wins. If the structural index is still warming up, only the exact match is tried. Markdown links use the exact match only.
- A link to a page that does not exist is still a link: it carries the `createpage` CSS class and points at the editor, so following it starts the page. Links to existing pages carry the `wikipage` class.

## Headings and same-page anchors

- `[label](WikiName#Heading)` links to a heading on another page. The heading is matched by its slugified text, so write the heading's text, not an id.
- A Markdown link whose target starts with `#` (for example `#some-anchor`) is a same-page fragment link. It is passed through as written and is never looked up as a page.
- `[[#Heading]]` is the wikilink form of a same-page heading link.

## Attachments and images

- A Markdown link whose target is an attachment's file name (`file.pdf`) on the page that owns the attachment, or `PageName/file.pdf` from anywhere, links to the attachment. An image link with the same kind of target shows an image attached to the current page.
- `[[PageName/file.pdf]]` and `[[PageName/file.pdf|label]]` are the wikilink forms. A target without a slash is an attachment of the current page only when that page has an attachment with exactly that name; otherwise it is read as a page name.
- `![[PageName/photo.png]]` shows the image inline. Add a size after a pipe: `![[PageName/photo.png|300]]` sets the width in pixels and `![[PageName/photo.png|300x200]]` sets width and height.

## Embedding a page or a section

`![[WikiName]]` shows another page's body inside this one, with a title link above it. `![[WikiName#Heading]]` embeds just that section.

- The embed must stand alone in its paragraph (several embeds on consecutive lines are fine). With other text on the same line it renders as an ordinary link instead.
- Embeds respect the viewer's permissions. A reader who cannot view the target sees "You don't have access to this page."
- An embed that would loop back to a page already being embedded stops with "Embed loop stopped", and nesting is limited to 3 levels.
- A missing page shows a "Not created yet" link, and a missing section shows "Section not found".
- A long page is cut at a block boundary before `wikantik.embed.maxChars` (default 20000 characters) and followed by a "Continue reading" link.
- The rendered body is available at `GET /api/pages/{name}/embed`.

## External links and InterWiki links

- `[label](https://example.com)` is an external link. Any scheme on the recognised list works (`http:`, `https:`, `ftp:`, `mailto:`, `tel:` and others), and the link gets the `external` class.
- A Markdown link whose target is `Edit:WikiName` is an InterWiki link: the part before the colon names a `wikantik.interWikiRef.<Name>` property whose value is a URL template. The shipped properties include `Edit`, `Raw`, `Reader`, `Group`, `RSS`, `WikiWikiWeb` and `TWiki`.
- Targets starting with `./` or `../` are left as written for the browser to resolve.

The server classifies a Markdown link in this order: access rule or plugin syntax, external, InterWiki, same-page fragment or footnote number, relative path, and finally local page. One consequence: **any link target containing a colon is read as InterWiki.** Earlier revisions of this page described `id:`, `tag:`, `cluster:` and `attachment:` link prefixes. They do not exist and never rendered as links. To reach a page by its stable `canonical_id`, use `GET /api/pages/by-id/{canonical_id}` or the `get_page_by_id` MCP tool, not an inline link.

## What links feed

Every Markdown link to a page, every `[WikiName]()` link, and every `[[ ]]` wikilink or `![[ ]]` embed in a page body is read as a real page-to-page link. Those links are the edges of the **Page Graph**: they populate each page's backlinks, the broken-link and orphaned-page reports (`get_backlinks`, `get_outbound_links`, `get_broken_links`, `get_orphaned_pages` on the admin MCP server), and they are rewritten when a page is renamed with `updateLinks`. Frontmatter fields such as `related` are not Page Graph edges, and neither are the entities the Knowledge Graph extracts; see [Page Graph vs Knowledge Graph](PageGraphVsKnowledgeGraph).

For agents, `GET /wiki/{page}?format=md` returns the raw Markdown with wikilinks rewritten to standard Markdown links with absolute URLs, and page embeds turned into `Embedded:` links rather than expanded.

## Best Practices

1. **Prefer slugs:** link to the page's CamelCase name, as it is easy to read and type.
2. **Avoid extensions:** never link to `Page.md` or `Page.txt`. Use the slug only.
3. **Use real page names in examples:** the scanner that builds the Page Graph reads Markdown links with a regular expression and does not skip code spans or fenced blocks. A Markdown-link example in code therefore counts as a real link, and shows up in `get_broken_links` if its target is not a page. Wikilink examples in code are skipped.
4. **Check after writing:** `verify_pages` reports broken links and pages with no backlinks.

---
**See Also:**
- [Wiki Name](WikiName) — Understanding slugs.
- [Text Formatting Rules](TextFormattingRules) — The full syntax guide.
- [Frontmatter Conventions](FrontmatterConventions) — the metadata block above the body.
- [Knowledge Management with Obsidian](KnowledgeManagementWithObsidian) — importing and exporting vaults, which keep `[[ ]]` links.
