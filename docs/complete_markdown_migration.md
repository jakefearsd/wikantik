# Complete Markdown Migration — COMPLETED

All migration steps have been completed. The legacy wiki-syntax parser has been fully
removed and Markdown is the only rendering pipeline.

## Completed Steps

### 1. Collapse `wikantik-markdown` module into `wikantik-main`
- Moved all Java sources, tests, JS, and SPI files from `wikantik-markdown` to `wikantik-main`
- Added Flexmark dependencies to `wikantik-main`
- Removed `wikantik-markdown` from all parent/BOM/WAR pom.xml references
- Deleted the `wikantik-markdown` module

### 2. Remove legacy parser (Phase 7)
- Deleted `WikantikMarkupParser` and its 6 handler classes (~2,000 lines)
- Deleted `WikantikMarkupParserTest` (303 tests)
- Updated `DefaultRenderingManager` to use only MarkdownParser (removed legacy fallback)
- Updated `DefaultPageRenamer` to handle Markdown link syntax `[text](target)`
- Updated `AbstractReferralPlugin` to check configured parser instead of classpath availability
- Converted all test fixtures and test content from wiki-syntax to Markdown
- Updated test properties to use `MarkdownParser` and `MarkdownRenderer`

### 3. Convert test fixtures
- Deleted `.txt` test fixture files (`.md` versions already existed)
- Converted all wiki-syntax links, headings, and formatting in test content strings

### 4. Remove `wiki-snips-wikantik.js`
- Deleted the legacy editor snippets JavaScript file
- Updated `wro.xml` to only include the markdown snippets group
- Updated `TemplateManagerTest` to reference `wiki-snips-markdown.js`

## Remaining Optional Item

### Migration script for existing deployments
Users with deployed wikis full of `.txt` pages would benefit from a batch conversion
tool, though this is a convenience rather than a blocker since legacy `.txt` pages
would need manual conversion. A script could:
- Convert wiki syntax to Markdown (links, headings, formatting, lists, tables)
- Convert `%%class ... /%` blocks to `<div class="...">` HTML
- Move `[{ALLOW/DENY}]` ACLs to YAML frontmatter
- Rename `.txt` files to `.md`
