# Wikantik Markdown Internal Link Syntax

This document describes how to create links to other pages on the same wiki when using Wikantik's Markdown parser.

## Basic Syntax

In Wikantik's Markdown parser, the **empty link** syntax is used for internal wiki page links:

```markdown
[PageName]()
```

The empty parentheses `()` tell Wikantik that the link text is the wiki page name.

## Examples

| Syntax | Result |
|--------|--------|
| `[Main]()` | Links to the Main page |
| `[PageName#section]()` | Links to a section on PageName |
| `[Click here](TargetPage)` | Custom text linking to TargetPage |
| `[NonExistentPage]()` | Creates an "edit" link (red link) |

## Other Link Types

### External Links

```markdown
[text](https://example.com)
```

### InterWiki Links

```markdown
[text](Wikantik:About)
```

### Attachment Links

```markdown
[text](PageName/attachment.txt)
```

## How It Works

The Markdown parser uses Wikantik's custom empty link syntax extension. When the URL portion is empty `()`, Wikantik uses the link text as the wiki page name. When the URL has content, that content is the target.

The system classifies links in this order:

1. **External links** - Starts with protocol (http:, https:, ftp:, etc.)
2. **InterWiki links** - Contains colon notation (WikiName:PageName)
3. **Footnote links** - Starts with # or is numeric
4. **Local wiki links** - Everything else (checked against page existence)

## CSS Classes

Links are automatically styled with CSS classes:

- `wikipage` - Links to existing pages
- `createpage` - Links to non-existent pages (edit links)
- `external` - External URLs
- `interwiki` - InterWiki links
- `attachment` - Attachment links

## Special Features

- **HTML/Markdown inside links**: `[Link **bold**]()` works and renders the markdown
- **Custom attributes**: `[text](url){target=blank}`
- **Section anchors**: `[PageName#SectionName]()`

## Source Files

The link processing is implemented in:

- `jspwiki-markdown/src/main/java/org/apache/wiki/markdown/nodes/WikantikLink.java`
- `jspwiki-markdown/src/main/java/org/apache/wiki/markdown/extensions/jspwikilinks/attributeprovider/WikantikLinkAttributeProvider.java`
- `jspwiki-markdown/src/main/java/org/apache/wiki/markdown/extensions/jspwikilinks/attributeprovider/LocalLinkAttributeProviderState.java`
