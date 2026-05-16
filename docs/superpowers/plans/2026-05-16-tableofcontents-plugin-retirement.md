# TableOfContents Plugin Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `title` and `numbered` parameters of `[{TableOfContents}]` take effect, and delete the unreachable `TableOfContents` plugin class plus its dead heading-listener support code.

**Architecture:** `[{TableOfContents}]` is rendered by `PluginLinkNodePostProcessorState.handleTableOfContentsPlugin()`, which substitutes a Flexmark `TocBlock`. Task 1 parses the markup's parameters and feeds `title`/`numbered` into that substitution. Task 2 removes the legacy `TableOfContents` plugin class, `Heading`, `HeadingListener`, and `MarkupParser.addHeadingListener`/`headingListenerChain` — all unreachable in production.

**Tech Stack:** Java 21, Flexmark `flexmark-ext-toc` 0.64.8, JUnit 5, Maven.

**Reference spec:** `docs/superpowers/specs/2026-05-16-tableofcontents-plugin-retirement-design.md`

---

## File Structure

- **Modify** `wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/PluginLinkNodePostProcessorState.java` — parameter parsing + wiring.
- **Modify** `wikantik-main/src/test/java/com/wikantik/render/markdown/MarkdownRendererTest.java` — new parameter tests.
- **Delete** `wikantik-main/src/main/java/com/wikantik/plugin/TableOfContents.java`
- **Delete** `wikantik-main/src/main/java/com/wikantik/parser/Heading.java`
- **Delete** `wikantik-main/src/main/java/com/wikantik/parser/HeadingListener.java`
- **Modify** `wikantik-main/src/main/java/com/wikantik/parser/MarkupParser.java` — remove heading-listener members.
- **Delete** `wikantik-main/src/test/java/com/wikantik/plugin/TableOfContentsCITest.java`
- **Modify** `wikantik-main/src/test/java/com/wikantik/parser/MarkupParserAdditionalTest.java` — remove `addHeadingListener` test.
- **Modify** `wikantik-main/src/test/java/com/wikantik/plugin/PluginCoverageTest.java` — remove the `TableOfContentsTests` nested class.
- **Modify** `wikantik-main/src/test/java/com/wikantik/plugin/TableOfContentsTest.java` — strengthen assertions, add no-headings case.

---

## Task 1: Wire `title` and `numbered` parameters into the TOC substitution

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/PluginLinkNodePostProcessorState.java`
- Test: `wikantik-main/src/test/java/com/wikantik/render/markdown/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing tests**

Add these two methods to `MarkdownRendererTest.java`, next to the existing `testMarkupExtensionTOCPluginGetsSubstitutedWithMDTocExtension` (~line 268):

```java
    @Test
    public void testTocPluginHonorsTitleParameter() throws Exception {
        final String src = "[{TableOfContents title='My Contents'}]()\n" +
                            "# Header 1\n";
        final String html = translate( src );
        Assertions.assertTrue( html.contains( "<h4 id=\"section-TOC\">My Contents</h4>" ),
                "TOC should use the custom title; got: " + html );
    }

    @Test
    public void testTocPluginHonorsNumberedParameter() throws Exception {
        final String src = "[{TableOfContents numbered=true}]()\n" +
                            "# Header 1\n" +
                            "## Header 2\n";
        final String html = translate( src );
        Assertions.assertTrue( html.contains( "<ol>" ),
                "Numbered TOC should render an ordered list; got: " + html );
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest='MarkdownRendererTest#testTocPluginHonorsTitleParameter+testTocPluginHonorsNumberedParameter' -q`
Expected: both FAIL — the title test sees the default `Table of Contents` h4, the numbered test sees `<ul>` not `<ol>`.

- [ ] **Step 3: Add imports to `PluginLinkNodePostProcessorState.java`**

In the import block, add (alphabetical order within their groups):

```java
import com.wikantik.parser.PluginContent;
import com.wikantik.util.TextUtil;

import java.util.Collections;
import java.util.Map;
```

(`com.wikantik.api.exceptions.PluginException` is already imported.)

- [ ] **Step 4: Replace `handleTableOfContentsPlugin` and add `parseTocParameters`**

Replace the existing `handleTableOfContentsPlugin` method body with this, and add the new private helper directly below it:

```java
    void handleTableOfContentsPlugin(final NodeTracker state, final WikantikLink link) {
        if( !wysiwygEditorMode ) {
            final ResourceBundle rb = Preferences.getBundle( wikiContext, Plugin.CORE_PLUGINS_RESOURCEBUNDLE );

            final Map< String, String > params = parseTocParameters( link );
            final String customTitle = params.get( "title" );
            final String titleText = customTitle != null ? TextUtil.replaceEntities( customTitle )
                                                          : rb.getString( "tableofcontents.title" );
            final String numbered = params.get( "numbered" );
            final boolean isNumbered = "true".equalsIgnoreCase( numbered ) || "yes".equalsIgnoreCase( numbered );
            final String tocOptions = isNumbered ? "levels=1-3 numbered" : "levels=1-3";

            final WikiHtmlInline divToc = WikiHtmlInline.of( "<div class=\"toc\">\n" );
            final WikiHtmlInline divCollapseBox = WikiHtmlInline.of( "<div class=\"collapsebox\">\n" );
            final WikiHtmlInline divsClosing = WikiHtmlInline.of( "</div>\n</div>\n" );
            final WikiHtmlInline h4Title = WikiHtmlInline.of( "<h4 id=\"section-TOC\">" + titleText + "</h4>\n" );
            final TocBlock toc = new TocBlock( CharSubSequence.of( "[TOC]" ), CharSubSequence.of( tocOptions ) );

            link.insertAfter( divToc );
            divToc.insertAfter( divCollapseBox );
            divCollapseBox.insertAfter( h4Title );
            h4Title.insertAfter( toc );
            toc.insertAfter( divsClosing );

        } else {
            NodePostProcessorStateCommonOperations.inlineLinkTextOnWysiwyg( state, link, wysiwygEditorMode );
        }
        removeLink( state, link );
    }

    /**
     * Parses the parameters of a {@code [{TableOfContents ...}]} markup link.
     * Returns an empty map (and logs a warning) if parsing fails, so the TOC
     * still renders with default options rather than throwing.
     *
     * @param link the TableOfContents plugin link node.
     * @return the parsed parameter map, never {@code null}.
     */
    private Map< String, String > parseTocParameters( final WikantikLink link ) {
        final String markup = link.getText().toString();
        try {
            final PluginContent pc = PluginContent.parsePluginLine( wikiContext, markup, -1 );
            if( pc != null ) {
                return pc.getParameters();
            }
        } catch( final PluginException e ) {
            LOG.warn( "Could not parse TableOfContents parameters from '{}'; rendering TOC with defaults",
                      markup, e );
        }
        return Collections.emptyMap();
    }
```

Note: `link.getText()` is used (not `getUrl()`) because `process()` already keys the TOC branch off `getText()`, which holds the `{TableOfContents ...}` markup.

- [ ] **Step 5: Run the new tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest='MarkdownRendererTest#testTocPluginHonorsTitleParameter+testTocPluginHonorsNumberedParameter' -q`
Expected: both PASS.

If the numbered test still fails because Flexmark emits a marker other than `<ol>`, read the `got:` output in the failure, confirm the numbered list structure, and tighten the assertion to the observed ordered-list markup — this pins the new behavior.

- [ ] **Step 6: Run the full `MarkdownRendererTest` to confirm no regression**

Run: `mvn test -pl wikantik-main -Dtest=MarkdownRendererTest -q`
Expected: PASS, including the pre-existing `testMarkupExtensionTOCPluginGetsSubstitutedWithMDTocExtension`.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/postprocessor/PluginLinkNodePostProcessorState.java \
        wikantik-main/src/test/java/com/wikantik/render/markdown/MarkdownRendererTest.java
git commit -m "feat(toc): honor title and numbered params in TableOfContents markup

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Delete the dead TableOfContents plugin class and heading-listener mechanism

**Files:**
- Delete: `wikantik-main/src/main/java/com/wikantik/plugin/TableOfContents.java`
- Delete: `wikantik-main/src/main/java/com/wikantik/parser/Heading.java`
- Delete: `wikantik-main/src/main/java/com/wikantik/parser/HeadingListener.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/MarkupParser.java`
- Delete: `wikantik-main/src/test/java/com/wikantik/plugin/TableOfContentsCITest.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/parser/MarkupParserAdditionalTest.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/plugin/PluginCoverageTest.java`

- [ ] **Step 1: Delete the four dead source/test files**

```bash
git rm wikantik-main/src/main/java/com/wikantik/plugin/TableOfContents.java \
       wikantik-main/src/main/java/com/wikantik/parser/Heading.java \
       wikantik-main/src/main/java/com/wikantik/parser/HeadingListener.java \
       wikantik-main/src/test/java/com/wikantik/plugin/TableOfContentsCITest.java
```

- [ ] **Step 2: Remove the heading-listener members from `MarkupParser.java`**

Delete the field (line ~60):

```java
    protected final ArrayList< HeadingListener > headingListenerChain = new ArrayList<>();
```

Delete the entire `addHeadingListener` method including its Javadoc (lines ~204-213):

```java
    /**
     *  Adds a HeadingListener to the parser chain.  It will be called whenever a parsed header is found.
     *
     *  @param listener The listener to add.
     */
    public void addHeadingListener( final HeadingListener listener ) {
        if( listener != null ) {
            headingListenerChain.add( listener );
        }
    }
```

Then remove the now-unused `import com.wikantik.parser.HeadingListener;` line if present, and the `import java.util.ArrayList;` line **only if** `ArrayList` is no longer used elsewhere in the file (search the file first; `mutatorChain` may also use it — if so, keep the import).

- [ ] **Step 3: Remove the `addHeadingListener` test from `MarkupParserAdditionalTest.java`**

Delete the test method (lines ~181-191):

```java
    // addHeadingListener – null listener is silently ignored
    @Test
    void addHeadingListenerWithNullIsIgnored() throws Exception {
```

(delete the comment line, the `@Test`, the full method through its closing brace). Then update the class-level Javadoc at line ~41 — remove `addHeadingListener,` from the comma-separated list of covered methods.

- [ ] **Step 4: Remove the `TableOfContentsTests` block from `PluginCoverageTest.java`**

Delete the unused import at line ~34:

```java
import com.wikantik.parser.Heading;
```

Delete the entire `@Nested` `TableOfContentsTests` class — it begins with the comment marker `//  TableOfContents tests ...` (~line 266) and runs through the closing brace of the nested class. Its render-path coverage is replaced by `MarkdownRendererTest` (Task 1) and `TableOfContentsTest` (Task 3); its `manager.execute()` / `new TableOfContents()` / `new Heading()` cases test code that no longer exists.

- [ ] **Step 5: Compile main and test sources**

Run: `mvn test-compile -pl wikantik-main -q`
Expected: BUILD SUCCESS, no `cannot find symbol` errors for `Heading`, `HeadingListener`, `TableOfContents`, or `addHeadingListener`.

If a compile error names another file still referencing a deleted symbol, that file was missed — search with `grep -rn "Heading\b\|HeadingListener\|TableOfContents" wikantik-main/src` and resolve before continuing.

- [ ] **Step 6: Run the affected test classes**

Run: `mvn test -pl wikantik-main -Dtest='MarkupParserAdditionalTest+PluginCoverageTest+MarkdownRendererTest' -q`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A wikantik-main/src/main/java/com/wikantik/parser/MarkupParser.java \
           wikantik-main/src/test/java/com/wikantik/parser/MarkupParserAdditionalTest.java \
           wikantik-main/src/test/java/com/wikantik/plugin/PluginCoverageTest.java
git commit -m "refactor(toc): delete unreachable TableOfContents plugin and heading-listener code

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

(The four `git rm`'d files from Step 1 are already staged.)

---

## Task 3: Strengthen `TableOfContentsTest`

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/plugin/TableOfContentsTest.java`

- [ ] **Step 1: Tighten the anchor assertion in `testNumberedItems`**

In `TableOfContentsTest.java`, replace the body assertions of `testNumberedItems` with assertions on the real anchor structure:

```java
    @Test
    public void testNumberedItems() throws Exception {
        final String src="[{TableOfContents}]()\n\n# Heading\n\n## Subheading\n\n### Subsubheading";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "<div class=\"toc\">" ), "Should contain TOC div" );
        Assertions.assertTrue( res.contains( "<a href=\"#heading\">Heading</a>" ),
                "TOC should link to the heading anchor; got: " + res );
        Assertions.assertTrue( res.contains( "<a href=\"#subheading\">Subheading</a>" ),
                "TOC should link to the subheading anchor; got: " + res );
    }
```

- [ ] **Step 2: Add a no-headings edge-case test**

Add this method to `TableOfContentsTest.java` (replaces the edge-case coverage removed from `PluginCoverageTest`):

```java
    @Test
    public void testTocWithNoHeadings() throws Exception {
        final String src = "[{TableOfContents}]()\n\nJust some plain text without headings.";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "<div class=\"toc\">" ),
                "TOC div should still render with no headings; got: " + res );
    }
```

- [ ] **Step 3: Run the test class**

Run: `mvn test -pl wikantik-main -Dtest=TableOfContentsTest -q`
Expected: PASS. If an anchor slug differs from `#heading`/`#subheading`, read the `got:` output and correct the expected slug to match Flexmark's actual heading-id output.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/plugin/TableOfContentsTest.java
git commit -m "test(toc): assert real anchor structure and no-headings case

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Log the commits in News.md

**Files:**
- Modify: `docs/wikantik-pages/News.md`

- [ ] **Step 1: Check for pre-existing uncommitted News.md changes**

Run: `git status --short docs/wikantik-pages/News.md`
If `News.md` shows as modified before this task, those changes are unrelated pre-existing work — STOP and ask the user how to handle them before editing. Otherwise continue.

- [ ] **Step 2: Append the three commit subjects**

Read the last ~15 lines of `docs/wikantik-pages/News.md` to match the existing entry format, then add entries for the three commits from Tasks 1-3:

- `feat(toc): honor title and numbered params in TableOfContents markup`
- `refactor(toc): delete unreachable TableOfContents plugin and heading-listener code`
- `test(toc): assert real anchor structure and no-headings case`

Follow the existing News.md convention exactly (per `project_news_md_log_convention` memory — each commit subject logged as content; the News commit itself is never self-logged).

- [ ] **Step 3: Commit**

```bash
git add docs/wikantik-pages/News.md
git commit -m "docs(news): log TableOfContents retirement commits

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Full verification

- [ ] **Step 1: Run the full integration-test reactor**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. This is the pre-merge gate (per `feedback_full_it_after_targeted_fix` memory) — targeted runs miss cross-module breakage.

- [ ] **Step 2: Confirm the SpotBugs warnings are gone**

The `UWF` / `NP_UNWRITTEN` warnings on `com.wikantik.parser.Heading` fields no longer appear in `wikantik-main/target/spotbugsXml.xml` — the class is deleted. Confirm no new SpotBugs failures were introduced by the build above.

- [ ] **Step 3: Report results**

Report the build outcome with evidence. If any IT module fails, investigate per `superpowers:systematic-debugging` before claiming completion.

---

## Self-Review

- **Spec coverage:** Part 1 (delete dead code) → Task 2. Part 2 (wire params) → Task 1. Part 3 (tests) → Tasks 1 & 3. `start`/`prefix` drop → covered by not implementing them (Task 1) and noted here. News.md note → Task 4. Verification → Task 5. All spec sections covered.
- **Placeholder scan:** No TBD/TODO; every code step shows full code; the one conditional (numbered marker, anchor slug) gives an explicit observe-and-tighten instruction, not a placeholder.
- **Type consistency:** `parseTocParameters` returns `Map<String,String>`, consumed via `params.get(...)`; `PluginContent.getParameters()` returns `Map<String,String>` (verified). Method/field names (`handleTableOfContentsPlugin`, `headingListenerChain`, `addHeadingListener`) match the actual source.
