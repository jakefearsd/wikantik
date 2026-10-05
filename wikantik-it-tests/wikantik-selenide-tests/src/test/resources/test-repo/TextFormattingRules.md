# Text Formatting Rules

Pages are written in Markdown (CommonMark with GitHub-style tables, footnotes and a few Wikantik additions). This page is a quick reference; the guides linked at the end go deeper.

## Text

| You write | You get |
|-----------|---------|
| `**bold**` | **bold** |
| `*italic*` | *italic* |
| `~~struck~~` | ~~struck~~ |
| a word in backticks | inline code |
| `# Heading` to `###### Heading` | headings (use one `#` heading per page) |
| `---` on its own line | horizontal rule |

Lists start with `* ` or `1. `; indent by four spaces to nest. A blank line starts a new paragraph. Put code in a fenced block with the language after the opening fence.

## Tables

```markdown
| Name | Value |
|------|-------|
| One  | 1     |
```

## Links

| Syntax | Meaning |
|--------|---------|
| `[[PageName]]` | link to a page |
| `[[PageName\|label]]` | link with your own text |
| `[[PageName#Heading]]` | link to a heading |
| `[label](PageName)` | Markdown form of a page link |
| `[label](https://example.com)` | external link |
| `![[PageName]]` | embed another page (alone on its line) |

A link to a page that does not exist yet marks the page as missing; follow it to create the page. See [WikiName](WikiName) for how names resolve and [Linking](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Linking.md) for attachments and embeds.

## Math

Write inline math as `$x^2$` with no space inside the dollar signs. Write display math with `$$` on its own line before and after the formula, with blank lines around the block. See [Mathematical Notation](https://github.com/jakefearsd/wikantik/blob/main/docs/user/MathematicalNotation.md).

## Plugins and page rules

* `[{TableOfContents}]()` and other plugins use the `[{Plugin}]()` form. [PageIndex](PageIndex) and [RecentChanges](RecentChanges) are built this way.
* `[{ALLOW view Admin}]()` restricts who may view a page, and `[{ALLOW edit Alice,Bob}]()` who may edit it.
* Raw HTML is not rendered.

## Metadata

A page can start with a YAML frontmatter block between two `---` lines holding its type, tags, cluster, summary and aliases. The editor has a form for it. See [Frontmatter](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Frontmatter.md).
