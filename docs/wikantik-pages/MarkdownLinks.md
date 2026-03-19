---
type: article
tags:
- uncategorized
summary: JSPWiki Markdown Internal Link Syntax
---
1. JSPWiki Markdown Internal Link Syntax

This document describes how to create links to other pages on the same wiki when using JSPWiki's Markdown parser.

  1. Basic Syntax

In JSPWiki's Markdown parser, the **empty link** syntax is used for internal wiki page links:

```markdown
[PageName](PageName)()
```

The empty parentheses `()` tell JSPWiki that the link text is the wiki page name.

  1. Examples

| Syntax | Result |
|--------|--------|
| `[Main](Main)()` | Links to the Main page |
| `[PageName#section]()` | Links to a section on PageName |
| `[Click here](TargetPage)` | Custom text linking to TargetPage |
| `[NonExistentPage](NonExistentPage)()` | Creates an "edit" link (red link) |

  1. Other Link Types

    1. External Links

```markdown
[text](https://example.com)
```

    1. InterWiki Links

```markdown
[text](JSPWiki:About)
```

    1. Attachment Links

```markdown
[text](PageName/attachment.txt)
```

  1. How It Works

The Markdown parser uses JSPWiki's custom empty link syntax extension. When the URL portion is empty `()`, JSPWiki uses the link text as the wiki page name. When the URL has content, that content is the target.

The system classifies links in this order:

1. **External links** - Starts with protocol (http:, https:, ftp:, etc.)
2. **InterWiki links** - Contains colon notation (WikiName:PageName)
3. **Footnote links** - Starts with # or is numeric
4. **Local wiki links** - Everything else (checked against page existence)

  1. CSS Classes

Links are automatically styled with CSS classes:

- `wikipage` - Links to existing pages
- `createpage` - Links to non-existent pages (edit links)
- `external` - External URLs
- `interwiki` - InterWiki links
- `attachment` - Attachment links

  1. Special Features

- **HTML/Markdown inside links**: `[Link **bold**]()` works and renders the markdown
- **Custom attributes**: `[text](url){target=blank}`
- **Section anchors**: `[PageName#SectionName]()`

  1. Source Files

The link processing is implemented in:

- `jspwiki-markdown/src/main/java/org/apache/wiki/markdown/nodes/JSPWikiLink.java`
- `jspwiki-markdown/src/main/java/org/apache/wiki/markdown/extensions/jspwikilinks/attributeprovider/JSPWikiLinkAttributeProvider.java`
- `jspwiki-markdown/src/main/java/org/apache/wiki/markdown/extensions/jspwikilinks/attributeprovider/LocalLinkAttributeProviderState.java`
