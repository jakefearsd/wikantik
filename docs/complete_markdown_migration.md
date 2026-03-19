# Complete Markdown Migration — Optional Remaining Steps

These steps are deferred from the initial migration. The system is fully functional
without them — Markdown is the default parser, legacy `.txt` pages still render via
the retained `WikantikMarkupParser`.

## 1. Remove legacy parser (Phase 7)

Remove `WikantikMarkupParser` and its 6 handler classes (~2,000 lines). Currently
blocked by a circular dependency: `wikantik-main` tests need a parser on their
classpath, but `MarkdownParser` lives in `wikantik-markdown` which depends on
`wikantik-main`.

**Resolution options:**
- Move `MarkdownParser` and `MarkdownRenderer` into `wikantik-main`, collapsing the
  `wikantik-markdown` module
- Add a minimal test-only stub parser in `wikantik-main`
- Restructure modules to break the circular dependency

**Files to remove once unblocked:**
- `wikantik-main/src/main/java/com/wikantik/parser/WikantikMarkupParser.java`
- `wikantik-main/src/main/java/com/wikantik/parser/WikiLinkHandler.java`
- `wikantik-main/src/main/java/com/wikantik/parser/WikiFormattingHandler.java`
- `wikantik-main/src/main/java/com/wikantik/parser/WikiListHandler.java`
- `wikantik-main/src/main/java/com/wikantik/parser/WikiTableHandler.java`
- `wikantik-main/src/main/java/com/wikantik/parser/WikiHeadingHandler.java`
- `wikantik-main/src/main/java/com/wikantik/parser/LinkParser.java`
- `wikantik-main/src/test/java/com/wikantik/parser/WikantikMarkupParserTest.java`

## 2. Convert integration test fixtures

9 `.txt` files in `wikantik-it-tests/wikantik-selenide-tests/src/test/resources/test-repo/`
and 3 in `wikantik-main/src/test/resources/`. Low risk to leave as-is since the legacy
parser still handles `.txt` files transparently.

## 3. Migration script for existing deployments

Users with deployed wikis full of `.txt` pages need a batch conversion tool. The
legacy parser handles existing `.txt` pages seamlessly, so this is a convenience
rather than a blocker. A script should:
- Convert wiki syntax to Markdown (links, headings, formatting, lists, tables)
- Convert `%%class ... /%` blocks to `<div class="...">` HTML
- Move `[{ALLOW/DENY}]` ACLs to YAML frontmatter
- Rename `.txt` files to `.md`

## 4. Remove `wiki-snips-wikantik.js`

The legacy editor snippets file is no longer the default but still ships in the WAR.
Can be removed once the legacy parser is fully retired.
