# TableOfContents plugin retirement & TOC parameter wiring

**Date:** 2026-05-16
**Status:** Approved — ready for implementation plan

## Problem

`[{TableOfContents}]` already renders a populated table of contents. It is
**not** broken. `PluginLinkNodePostProcessorState.process()` matches the
markup by string prefix and calls `handleTableOfContentsPlugin()`, which
substitutes a Flexmark `TocBlock` (`[TOC] levels=1-3`); Flexmark's
`TocExtension` renders the real TOC. `MarkdownRendererTest`
.`testMarkupExtensionTOCPluginGetsSubstitutedWithMDTocExtension` proves it.

Two real defects remain:

1. **Dead code.** The `TableOfContents` *plugin class*
   (`com.wikantik.plugin.TableOfContents`), together with
   `com.wikantik.parser.Heading`, `com.wikantik.parser.HeadingListener`, and
   `MarkupParser.addHeadingListener` / `headingListenerChain`, is unreachable.
   Nothing ever fires `headingAdded`. The plugin class is only reached via
   `PluginManager.execute()` directly, which only `TableOfContentsCITest`
   does. These are the source of the SpotBugs `UWF` / `NP_UNWRITTEN`
   warnings on `Heading.titleSection` / `titleText` / `titleAnchor`.

2. **Ignored parameters.** `handleTableOfContentsPlugin` hardcodes
   `[TOC] levels=1-3` and carries a `// FIXME proper plugin parameters
   handling` comment. The `title` and `numbered` parameters are silently
   discarded in real rendering — `[{TableOfContents numbered=true}]` does
   nothing.

## Goals

- Delete the dead plugin class and its dead support types.
- Make `title` and `numbered` parameters take effect in the substitution path.
- No regression to the working `[{TableOfContents}]` render path.

## Non-goals

- `start` and `prefix` parameters are **dropped**. Flexmark's TOC has no
  equivalent (its option vocabulary is `levels`, `bullet`, `numbered`,
  `text`, `formatted`, `hierarchy`, `flat`, `reversed`, `increasing`,
  `decreasing`). They have been dead for the life of the substitution path.
- No new `levels` parameter — `levels=1-3` stays hardcoded (YAGNI; never a
  pre-existing plugin parameter).

## Part 1 — Delete dead code

Remove entirely:

- `wikantik-main/src/main/java/com/wikantik/plugin/TableOfContents.java`
- `wikantik-main/src/main/java/com/wikantik/parser/Heading.java`
- `wikantik-main/src/main/java/com/wikantik/parser/HeadingListener.java`
- `MarkupParser.addHeadingListener(...)` method and the
  `headingListenerChain` field.

Tests:

- Delete `TableOfContentsCITest.java` — it exists solely to drive the dead
  class, including calling `headingAdded()` via reflection-set fields.
- **Keep** `TableOfContentsTest.java` — it renders through the real path
  (`testEngine.getI18nHTML`) and stays valid. Strengthen its assertions as
  part of Part 3.
- Update `MarkupParserAdditionalTest` — drop any `addHeadingListener` cases.
- Update `PluginCoverageTest` — drop `TableOfContents` / `Heading`
  references; confirm it does not hard-reference the deleted class.

Keep the `tableofcontents.title` key in `PluginResources.properties` /
`PluginResources_ru.properties` — `handleTableOfContentsPlugin` still uses it
as the default `<h4>` text.

## Part 2 — Wire parameters in `handleTableOfContentsPlugin`

Parse plugin parameters with `PluginContent.parsePluginLine()`. That method
only tokenizes (plugin-name regex + `parseArgs`); it does not resolve the
plugin class, so it remains safe after `TableOfContents.java` is deleted.

| Plugin parameter | Behaviour |
|------------------|-----------|
| `title='X'`      | Used as the `<h4 id="section-TOC">` text via `TextUtil.replaceEntities(title)`, instead of the i18n default. |
| `numbered=true` \| `numbered=yes` | Append `numbered` to the `TocBlock` options sequence (currently `levels=1-3`). Case-insensitive. |
| `start`, `prefix`| Ignored (dropped — see Non-goals). |

Behaviour change to document in `News.md`: Flexmark's `numbered` option
produces a nested ordered list (browser renders `1.`, `1.` per level), not
the old plugin's literal `1.1.1` text. This is acceptable.

Error handling: if `parsePluginLine` fails to match or throws, fall back to
the current default (`levels=1-3`, i18n title) — never propagate the
exception. Log at `LOG.warn` with context per the project's no-empty-catch
rule.

## Part 3 — Tests (TDD)

Write tests before the code change, in `MarkdownRendererTest` (or alongside
the existing TOC test):

1. **Characterization** — `[{TableOfContents}]` over a small heading set
   produces the current populated-TOC HTML. Locks the baseline before
   deletion.
2. `[{TableOfContents title='My Contents'}]` →
   `<h4 id="section-TOC">My Contents</h4>`.
3. `[{TableOfContents numbered=true}]` → ordered-list TOC markup.
4. Plain `[{TableOfContents}]` output unchanged from baseline.

Strengthen `TableOfContentsTest` assertions from `contains("toc")` to
assert on the actual anchor structure.

## Risks

- `parsePluginLine` must match `{TableOfContents title='x'}` via the
  plugin-name regex. If it misses, the fallback path keeps current
  behaviour — verified by test 4.
- `PluginCoverageTest` may discover plugins by classpath scan. Deleting the
  class drops it from discovery cleanly; confirm no hard reference remains.

## Verification

- `mvn test -pl wikantik-main` (targeted) during development.
- `mvn clean install -Pintegration-tests -fae` before commit.
- SpotBugs: the `UWF` / `NP_UNWRITTEN` warnings on `Heading.*` disappear
  because the class is gone.
