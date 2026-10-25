---
kg_include: true
summary: 'How the JSPWiki-to-Markdown converter works: line-by-line regex rules, the
  syntax mapping, the wiki-syntax heuristic, and the API and script that use it.'
type: article
status: active
date: '2026-04-01'
audience:
- humans
canonical_id: 01KQ0P44Z5CB0MC0BGG3KXX5XC
tags:
- wikantik
- markdown
- jspwiki
- converter
- wikantik-development
cluster: wikantik-development
title: 'Wiki to Markdown Converter: JSPWiki Syntax to Markdown'
related:
- WikantikDevelopment
---
# Wiki to Markdown Converter: JSPWiki Syntax to Markdown

`WikiToMarkdownConverter` (`wikantik-main/src/main/java/com/wikantik/content/WikiToMarkdownConverter.java`) translates legacy JSPWiki markup (such as `[LinkName]`, `! Heading`, `__bold__` and `{{code}}`) into Markdown. It is a line-by-line, regex-based converter with a small state machine (normal, code block, table), not a lexer or AST transpiler. It has two public entry points: `convert(String)`, which returns the Markdown plus a list of warnings, and `isLikelyWikiSyntax(...)`, a scoring heuristic that decides whether a page still looks like wiki markup.

---

## 1. Syntax mapping

| Construct | JSPWiki markup | Markdown output |
|-----------|----------------|-----------------|
| Top heading | `!!! Title` | `# Title` |
| Second heading | `!! Title` | `## Title` |
| Third heading | `! Title` | `### Title` |
| Bold | `__text__` | `**text**` |
| Italic | `''text''` | `*text*` |
| Inline code | `{{text}}` | `` `text` `` |
| Code block | `{{{` ... `}}}` | fenced code block |
| Bare wiki link | `[WikiName]` | `[WikiName]()` |
| Plugin, ACL or variable | `[{Name args}]` | `[{Name args}]()` |
| Escaped bracket | `[[` | `\[` |
| Unordered list | `* item`, `** item` | `* item`, nested items indented two spaces per level |
| Ordered list | `# item`, `## item` | `1. item`, nested items indented three spaces per level |
| Horizontal rule | `----` (four or more dashes) | `---` |
| Definition | `;term:definition` | `**term**: definition` |
| Line break | `\\` | two trailing spaces |

A labelled link `[Label|WikiName]` becomes `[Label](WikiName)`. A bare `[WikiName]` becomes `[WikiName]()`: an empty-target link that Wikantik's wikilink extension resolves to the page at render time. A wiki table uses `||` for header cells and `|` for data cells, and becomes a GFM pipe table; when the source has no `||` header row the output gets an empty header row so the table is still valid Markdown.

---

## 2. How the conversion runs

```
 wikiText.split("\n")
        |
        v   one line at a time
 +-----------------------------------------------------------+
 | state CODE_BLOCK : copy lines verbatim until a line whose |
 |                    trimmed text is }}}  -> closing fence  |
 | line starts {{{  : flush any table, emit opening fence    |
 | table row        : accumulate; flush as a GFM table when  |
 |                    a non-table line (or the end) arrives  |
 | anything else    : block rules, then inline rules         |
 +-----------------------------------------------------------+
        |
        v
 ConversionResult(markdown, warnings)
```

Block rules are tried in order: headings, horizontal rule, definition list, unordered list, ordered list. Whatever is left (and every table cell) goes through the inline passes in a fixed order:

1. `[[` is protected so it can never become a native wikilink.
2. Plugin syntax `[{...}]` becomes `[{...}]()`. This runs before link conversion so a plugin is not mistaken for a link.
3. Labelled links, then bare links.
4. Bold, italic, inline code, and `\\` line breaks.
5. The protected `[[` is restored as `\[`.

The only warning the converter emits today is for an unclosed code block, which is auto-closed. It does not parse into an AST, normalise page names, or touch `$` characters, so currency and math in the source pass through unchanged.

---

## 3. Detecting wiki syntax

`isLikelyWikiSyntax` adds up evidence and reports true at a score of 3 or more: a `!`-style heading (+2), `''italic''` (+2), a `[a|b]` pipe link (+2), a plugin still missing its `()` (+2), `{{{` (+1) and `__bold__` (+1). The result is memoised by content hash in a bounded Caffeine cache (10,000 entries), so the page-read path pays for it only when a page body changes.

---

## 4. Where it is used

- `POST /api/convert/wiki-to-markdown` (`ConvertResource` in `wikantik-rest`) takes `{"content": "..."}` from an authenticated caller and returns `{"markdown": ..., "warnings": [...]}`. The same servlet's `markdown-to-html` action renders a preview with plugins disabled.
- `GET /api/pages/{name}` reports a `markupSyntax` of `markdown`, `wiki` (legacy `.txt` pages) or `likely-wiki` (a Markdown page the heuristic flags). The editor reads it and offers the conversion, calling the endpoint above.
- `bin/wiki2markdown.py <directory> [--dry-run]` bulk-converts `.txt` files to `.md`, using a Python copy of the same heuristic and its own implementation of the conversion rules, and deletes each original `.txt`.

After converting, check any table or math-heavy page by eye. The converter is rule-based, so unusual nesting of bold, italic and links in one line can need a manual touch-up.

---

**Cluster Hub:** [[WikantikDevelopmentHub|Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent Briefings]] (`wikantik-development`)
