# Linking Pages and Embedding Content

This guide is for people who write wiki pages. It covers every way to link to another page, a heading, an attachment or an external site; how to embed one page inside another; what a link to a page that does not exist looks like; and the `[{Plugin}]` syntax for dynamic content. All examples assume a Markdown page, which is what the editor creates.

## Link to another page

You have three ways to link to a page on the same wiki. They produce the same kind of link, so pick the one you prefer.

| Syntax | Result |
|--------|--------|
| `[[PageName]]` | Link to `PageName`, shown as `PageName` |
| `[[PageName\|label]]` | Link to `PageName`, shown as `label` |
| `[[PageName#Heading]]` | Link to a heading on `PageName`, shown as `PageName > Heading` |
| `[[#Heading]]` | Link to a heading on the current page, shown as `Heading` |
| `[label](PageName)` | Link to `PageName`, shown as `label` |
| `[PageName]()` | Legacy form: empty parentheses make the link text the page name |

A few rules for `[[ ]]` links (`WikiLinkSyntax`):

- The text between the brackets cannot start with a space and cannot span a line.
- A `[[ ]]` inside a code span or fenced code block, or with a backslash before the opening bracket, is plain text.
- To put a literal `|` in a target, write `\|`.
- A target is matched in this order: the exact page name (including plural forms), then the page name ignoring case, then the page `title` or an entry in `aliases` ignoring case (`WikiLinkResolver`). If two pages tie, the one that sorts first alphabetically wins.

### Complete a link while you type

In the editor, typing `[[` opens a page picker, `[[Page#` lists the headings of that page, and `](` after a Markdown link label lists pages and the page's attachments. See [Editing.md](Editing.md#link-to-other-pages-while-you-type).

## Link to an attachment

Attachments belong to the page they are uploaded to. Link to one with either form:

```markdown
[Quarterly figures](PageName/figures.pdf)
[[PageName/figures.pdf]]
[[PageName/figures.pdf|the figures]]
```

In a Markdown link on the page that owns the attachment, the file name alone also works (`[diagram](diagram.png)`). The reader resolves it to `/attach/{PageName}/{fileName}` and marks a file that is not attached with the `missing-attachment` CSS class (`wikantik-frontend/src/utils/remarkAttachments.js`).

An attachment name can be at most 40 characters: letters, digits, `-` and `_`, one dot and an extension (`attachmentNameValidator.js`). The editor renames files you paste or drop to fit.

## Embed content

### Embed a page or a section

Put `![[PageName]]` on its own line to show another page's body inside this one. `![[PageName#Heading]]` embeds just that section.

```markdown
Intro text.

![[ReleaseChecklist#Before you ship]]

More text.
```

Rules that decide whether you get an embed or just a link:

- The embed must be alone in its paragraph. Several `![[ ]]` embeds on consecutive lines are fine. If the line also holds other text, the server renders an ordinary link instead.
- The embedded body is fetched from `GET /api/pages/{name}/embed` and respects the viewer's permissions. A reader who cannot view the target sees "You don't have access to this page."
- An embed that would loop back to a page already being embedded stops with "Embed loop stopped", and nesting is limited to 3 levels (`WikiEmbedRenderer.MAX_DEPTH`).
- A section name that does not exist shows "Section not found: ...".
- A long page is cut at the last block boundary before `wikantik.embed.maxChars` (default 20000 characters) and followed by a "Continue reading" link.

### Embed an image from an attachment

`![[PageName/photo.png]]` shows an attachment image inline. Add a number after `|` to set its size in pixels: `![[PageName/photo.png|300]]` sets the width, and `![[PageName/photo.png|300x200]]` sets width and height. An attachment that does not exist renders as its file name in a "missing" style. You can also use Markdown: `![alt](photo.png)`.

## Links to pages that do not exist yet

A link to a page that does not exist is still a link. In a rendered page it points at the editor for that name and carries the `createpage` CSS class, so clicking it starts a new page. In the editor preview and when you hover a link, the card reads "Not created yet". A `![[Missing]]` embed shows a "Not created yet" link to the editor.

## Other link types

| Syntax | Kind |
|--------|------|
| `[text](https://example.com)` | External link (any scheme such as `http:` or `https:`) |
| `[text](Wikantik:About)` | InterWiki link: `WikiName:Page`, where the prefix is a `wikantik.interWikiRef.<Name>` property |
| `[text](PageName#Heading)` | Link to a heading on another page |
| `[text](url){target=blank}` | Attributes after a link are passed through to the HTML |

The server classifies a Markdown link in this order: external, then InterWiki, then footnote (starts with `#` or is numeric), then local page. The resulting CSS classes are `wikipage` (existing page), `createpage` (missing page), `external`, `interwiki` and `attachment` (`MarkupParser` `CLASS_*` constants).

## Preview a link without leaving the page

Hover a wiki link in a rendered page for about 0.4 seconds, or focus it with the keyboard, to see a card with the target's title, type, cluster and summary (or the start of its text) (`GET /api/pages/{name}/preview`). In the editor, hold Ctrl (Cmd on a Mac) while hovering a link in the source. Ctrl/Cmd-click opens a link in a new tab.

## Run a plugin

A plugin inserts generated content such as a list of recent changes. The syntax is `[{PluginName parameter=value}]`:

```markdown
[{ReferringPagesPlugin max=10}]
[{RecentChangesPlugin}]
```

A bare name is looked up in the `com.wikantik.plugin` package, then in any package added through `wikantik.plugin.searchPath`. The plugins shipped in that package are `AliasPlugin`, `CurrentTimePlugin`, `HubSetPlugin`, `IfPlugin`, `Image`, `IndexPlugin`, `InsertPage`, `JDBCPlugin`, `RecentArticles`, `RecentChangesPlugin`, `ReferredPagesPlugin`, `ReferringPagesPlugin`, `ReferringUndefinedPagesPlugin`, `RelationshipsPlugin`, `Search`, `UndefinedPagesPlugin` and `UnusedPagesPlugin`. `JDBCPlugin` runs SQL and is disabled unless an administrator sets `wikantik.plugin.jdbc.enabled=true`.

The same `[{ ... }]` brackets carry page access rules such as `[{ALLOW view Admin}]`. See [Editing.md](Editing.md#restrict-who-can-see-or-edit-a-page).

## Math in Markdown

LaTeX math is written with `$...$`, `$$...$$` or a ```` ```math ```` fence. See [MathematicalNotation.md](MathematicalNotation.md).

## How links feed the rest of the wiki

Every `[[ ]]` link, `![[ ]]` embed and `[text](Page)` link is read as a real page-to-page link. Those links are the edges of the Page Graph and the source of the **Referenced by** list on the target page; see [Reading.md](Reading.md#see-how-a-page-connects-to-the-rest-of-the-wiki). Renaming a page rewrites links that point at it.

## Source files

- `wikantik-api/src/main/java/com/wikantik/api/parser/WikiLinkSyntax.java` — `[[ ]]` and `![[ ]]` grammar
- `wikantik-main/src/main/java/com/wikantik/wikilink/WikiLinkResolver.java` — target resolution
- `wikantik-main/src/main/java/com/wikantik/wikilink/WikiEmbedRenderer.java` — page embeds
- `wikantik-main/src/main/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiLinkPostProcessor.java` — `[[ ]]` rendering
- `wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/attributeprovider/WikantikLinkAttributeProvider.java` — link classes
- `wikantik-frontend/src/utils/wikiLinkSyntax.js`, `wikantik-frontend/src/utils/remarkWikiLinks.js` — editor preview
