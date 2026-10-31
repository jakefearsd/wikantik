# Obsidian Block References — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `^id` block anchors, `[[Page#^id]]` links, `![[Page#^id]]` embeds, copy-block-link, `#^`
completion, broken-block reporting and import fidelity, server and editor preview in parity.

**Architecture:** One server block model (`BlockIds`) parses page source exactly as rendering does
and is shared by the render post-processor, embeds, the `/blocks` endpoints, reader-text stripping
and the broken-block report. The frontend has a mirror (`blockIds.js` + `remarkBlockIds`), and a
shared JSON fixture keeps the two in agreement. A wikilink fragment starting with `^` is a block,
everywhere.

**Tech Stack:** Java 25, flexmark 0.64.8 (post-processor + attribute provider), Gson servlets
(wikantik-rest), React 19 + remark/unified + CodeMirror 6 (wikantik-frontend), JUnit 5 + Mockito,
Vitest 4, Selenide/REST ITs.

**Spec:** `docs/superpowers/specs/2026-10-31-obsidian-block-references-design.md` (Phase 1 =
§1.1–§1.12 plus §1.11a, §5, §6, §7). Read it first; this plan carries the decisions, the spec
carries the why.

## Global Constraints

- **Block id:** `^` + one or more `[A-Za-z0-9-]`. Matching is case-sensitive.
- **Inline marker:** preceded by whitespace, at the end of the last line of a paragraph or list item's
  own first paragraph; trailing spaces/tabs allowed. **Standalone marker:** a paragraph that is only
  `^id`.
- **Never** recognised in code spans, fenced or indented code, inline `$…$` or display `$$…$$` math,
  or heading lines.
- **First occurrence wins** for duplicate ids on a page. Later duplicates have their marker removed
  but get no anchor.
- **Rendered anchor:** `id="^id"` on the element. **Link fragment:** `#^id`, never slugged. A
  wikilink `heading` starting with `^` is a block ref, always.
- **Ordinals:** `data-block="i"`, with `i` = index in `BlockIds.list(...)` order. Every parse numbers
  its own blocks from 0. The reader ignores ordinals inside `.inserted-page` and `.wiki-embed`.
- **New ids:** 6 chars `[a-z0-9]`, unique within the page.
- **Change note on assign:** `Added block id ^<id>`.
- **Line endings:** page text may be CRLF (prod pages are). Every regex and offset must work with
  `\r\n`.
- **Never swallow exceptions:** every catch logs at least `LOG.warn` with page and id.
- **TDD:** a failing test before each implementation step (CLAUDE.md).
- **No new config keys.** The existing `wikantik.embed.maxChars` applies to block embeds.
- **New classes carry the Apache licence header** like their neighbours.
- **The PMD complexity ratchet must stay green.** Keep new handlers out of `PageResource` (it's
  large); put them in their own classes.
- **Frontend:** no new npm dependencies.
- **Final gate:** `bin/run-tests.sh --parallel 4` must pass before Phase 1 is declared done.

## Review Focus

1. **CRLF pages.** A marker at the end of a `\r\n` line must be detected, stripped and assigned
   correctly (prod pages are CRLF). Pinned in Task 1 and Task 4.
2. **Display math before the block.** `$$…$$` is rewritten to a fence before parsing. `assign` must
   insert the marker at the right raw offset after one. Pinned in Task 1.
3. **Prose that ends in ` ^2`** ("squared ^2") becomes block `2`, by design. Pinned so the behaviour
   is explicit; the corpus audit (Task 12) finds real cases.
4. **A host page with `[{InsertPage}]` and its own blocks.** Host ordinals still match
   `BlockIds.list` on host source, and the reader ignores the included page's ordinals. Pinned in
   Task 2 and Task 9.
5. **Two people assigning ids from the same version.** The second gets 409 `stale`, never a silent
   overwrite. Pinned in Task 4.

---

### Task 1: `BlockIds` — the server block model

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/parser/markdown/BlockIds.java`
- Test: `wikantik-main/src/test/java/com/wikantik/parser/markdown/BlockIdsTest.java`

**Interfaces:**
- Produces:
  - `public final class BlockIds`:
    - `public enum Kind { PARAGRAPH, LIST_ITEM, LIST, TABLE, QUOTE, CALLOUT, CODE, MATH }`
    - `public record Block( int index, String id /*nullable*/, Kind kind, int start, int end, int markerStart /* -1 */, int markerEnd /* -1 */, String plainText )`
  - Offsets index into the `fullText` argument, frontmatter included. `markerStart`/`markerEnd`
    cover the whitespace + `^id` (inline) or the whole marker line (standalone).
  - Methods:
    - `public static List< Block > list( String fullText )`
    - `public static Optional< Block > find( String fullText, String id )`
    - `public static String sourceOf( String fullText, Block b )` (the block's source with its own
      marker removed)
    - `public static String assign( String fullText, Block b, String id )`
    - `public static String strip( String fullText )`
    - `public static String newId( Set< String > existing )` (draws from a `SecureRandom`)
    - `public static String fingerprint( String text )` (lowercase, keep `\p{L}\p{N}` only, first 200
      chars)
    - `public static boolean isBlockRef( String heading )` (`heading != null && heading.startsWith("^")`)
    - `public static String idOf( String heading )` (strips the leading `^`)
    - `public static final Pattern ID = Pattern.compile( "[A-Za-z0-9-]+" )`

- [ ] **Step 1: Write the failing tests** (`BlockIdsTest`, one `@Test` per line below). Inputs and
  assertions:
  - `paragraphInlineMarker`: `"Alpha beta ^abc\n"` → one block `PARAGRAPH`, id `abc`,
    `plainText` `"Alpha beta"`.
  - `listItemMarker`: `"- one ^a1\n- two\n"` → blocks `[LIST_ITEM a1, LIST_ITEM null]`. The list
    itself is not listed.
  - `standaloneMarkerTagsPreviousTable`: `"| a | b |\n|---|---|\n| 1 | 2 |\n\n^tbl\n"` → one `TABLE`
    block with id `tbl`. The marker paragraph is not a block.
  - `standaloneMarkerTagsQuoteListCodeMath`: the same shape for a `>` quote, a `- x` list (gives
    `LIST`), a ```` ``` ```` fence (gives `CODE`), and a `$$\nx\n$$` block (gives `MATH`).
  - `calloutKind`: `"> [!note] T\n> body\n\n^c1\n"` → `CALLOUT` id `c1`.
  - `orphanStandalone`: `"^lone\n"` → no blocks. `find(.., "lone")` is empty;
    `strip` → `"\n"`.
  - `headingIgnored`: `"## Title ^h\n"` → no block has id `h`. The heading isn't a block.
  - `codeAndMathIgnored`: `` "`x ^a`\n" ``, `"```\ny ^b\n```\n"`, `"$x ^c$\n"` → no ids.
  - `needsWhitespace`: `"e^x and 2^10\n"` → paragraph with id null.
  - `proseCaretBecomesId` (Review Focus 3): `"squared ^2\n"` → id `2`.
  - `duplicateFirstWins`: `"a ^d\n\nb ^d\n"` → both blocks are listed, the first has id `d` and the
    second id null. `find` returns the first.
  - `crlf` (Review Focus 1): `"Alpha ^abc\r\n\r\n- x ^k\r\n"` → ids `abc`, `k`. `strip` →
    `"Alpha\r\n\r\n- x\r\n"`.
  - `frontmatterOffsets`: `"---\ntitle: t\n---\nText ^z\n"`. `markerStart` points at the space
    before `^z` in the **full** text.
  - `assignAfterDisplayMath` (Review Focus 2): `"$$\na\n$$\n\nPara\n"`. `assign` on the paragraph
    with `"q1"` → `"$$\na\n$$\n\nPara ^q1\n"`.
  - `assignStandaloneForTable`: the table from above without an id → `assign` appends
    `"\n^t2\n"` after the table (a blank line before it).
  - `assignListItem`: `"- one\n- two\n"`, assign item 1 `"x"` → `"- one\n- two ^x\n"`.
  - `sourceOfListItemKeepsChildren`: `"- a ^p\n  - child\n- b\n"` → `"- a\n  - child"` (trailing
    newline trimmed).
  - `stripKeepsLineCount`: every fixture above. `strip(t).split("\n", -1).length` equals the
    original's.
  - `newIdUnique`: `newId(Set.of(...))` matches `[a-z0-9]{6}` and isn't in the set (call it 1000
    times).
  - `fingerprint`: `fingerprint("Héllo, World! 42")` → `"hélloworld42"`.
- [ ] **Step 2: Run them and see them fail.**
  `mvn test -pl wikantik-main -Dtest=BlockIdsTest -q` → FAIL (`BlockIds` doesn't exist).
- [ ] **Step 3: Implement `BlockIds`.**
  - Body offset: `FrontmatterParser.bodyOffset(fullText)`. Then `MarkdownBodySource.prepare(body)`,
    and map every AST offset back with `prepared.toBody(pos) + bodyStart`, as
    `RenderPathAccessRules.find` does.
  - Parser: one static `Parser` built from `MarkdownDocument.structuralOptions()` plus
    `customInlineParserExtensionFactory( new InlineMathParser.Factory() )`, so `$…$` is a math node
    and not text.
  - Walk the AST in pre-order. List paragraphs (except an item's own first paragraph, which makes the
    `ListItem` the block), list items, `TableBlock`, `BlockQuote` (`CalloutBlock` → `CALLOUT`),
    fenced/indented code (info `math` → `MATH`), and a list only when a standalone marker follows it.
  - Detect the inline marker on the paragraph's **last** `Text` node with
    `(?<=[ \t])\^([A-Za-z0-9-]+)[ \t]*\r?$` against that node's chars.
  - Detect a standalone marker by the paragraph's whole text matching `^\^([A-Za-z0-9-]+)[ \t]*\r?$`.
  - Plain text: flexmark `TextCollectingVisitor`, then `WikiLinkSyntax.toPlainText`, then trim.
  - `strip` replaces each marker range: an inline one with `""`, a standalone line's content with
    `""`, keeping its newline. Cheap pre-check: return the input unchanged when it has no `^`.
- [ ] **Step 4: Run them and see them pass** (the same command → PASS).
- [ ] **Step 5: Commit.**
  `git add` the two files, then commit as
  `feat(blocks): BlockIds — server block model for ^id block references`.

---

### Task 2: Render — anchors and ordinals

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/blockids/BlockIdPostProcessor.java`
  (with a nested `Factory`), and `BlockIdAttributeProvider.java` (with a nested `Factory`) in the same
  package.
- Modify:
  - `wikantik-main/src/main/java/com/wikantik/markdown/MarkdownForWikantikExtension.java`: register
    both factories.
  - `wikantik-main/src/main/java/com/wikantik/parser/markdown/WikantikHtmlSanitizer.java`: allow
    `data-block` (`[0-9]+`) on block elements and `data-task` (for Phase 3), and `id` beginning with
    `^`. `id` is already allowed globally; prove it survives.
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/blockids/BlockIdRenderTest.java`

**Interfaces:**
- Consumes: `BlockIds.list`, `BlockIds.Kind`.
- Produces:
  - Rendered HTML for an inline id: `<p id="^abc" data-block="0">Alpha beta</p>`.
  - For a list item: `<li id="^a1" data-block="0">`; for a table: `<table id="^tbl" data-block="N">`.
  - For an orphan standalone marker: `<span id="^lone" class="block-anchor"></span>`.
  - Blocks without ids still get `data-block`.

- [ ] **Step 1: Write the failing tests** (render through the real engine, as
  `WikiLinkParityTest` does):
  - `inlineMarkerBecomesIdAndIsRemoved`
  - `listItemId`
  - `standaloneMarkerOnTableQuoteListCodeMath`
  - `orphanAnchor`
  - `headingCaretStaysLiteral` (`## T ^h` renders the text `^h`)
  - `duplicateGetsNoSecondAnchor`
  - `ordinalsEqualBlockIdsList` over a fixture page with a callout, a nested list, a table, display
    math, an embed-only paragraph `![[Other]]` and a task list. Each `data-block` value in the HTML
    equals the index of the matching `BlockIds.list` entry, and no index is duplicated.
  - `insertPageHostOrdinalsUnaffected` (Review Focus 4): a host with `[{InsertPage page=Inc}]`
    between two paragraphs. The host paragraphs carry the ordinals `BlockIds.list(hostText)` gives,
    and the included content sits inside `div.inserted-page`.
  - `caretIdSurvivesSanitizer`
  - `crlfPage`
- [ ] **Step 2: Run them and see them fail.**
  `mvn test -pl wikantik-main -Dtest=BlockIdRenderTest -q` → FAIL.
- [ ] **Step 3: Implement.**
  - `BlockIdPostProcessor` runs **before** `NativeWikiLinkPostProcessorFactory`
    (`getBeforeDependents`), so it numbers original paragraphs even when a later processor replaces
    one with an embed.
  - It numbers blocks with the same walk as `BlockIds` and removes markers from the AST:
    - for an inline marker, trim the `Text` node;
    - for a standalone marker, unlink the paragraph, or replace it with an `HtmlInline` anchor when
      it's an orphan.
  - It records `Map< Node, Attrs(id, ordinal) >` in a `DataKey` on the `Document`.
  - `BlockIdAttributeProvider` applies `id`/`data-block` from that map when the node is rendered.
  - **Extract the shared walk** into a package-visible helper used by both `BlockIds` and the
    post-processor, so the numbering can't drift.
- [ ] **Step 4: Run them and see them pass.** Then rerun `WikiLinkParityTest`, `CalloutExtensionTest`
  and `WikantikHtmlSanitizerTest` → PASS.
- [ ] **Step 5: Commit.** Message: `feat(blocks): render ^id anchors and data-block ordinals`.

---

### Task 3: Links and embeds resolve `^id`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/wikilink/WikiLinkFragments.java`, with
  `public static String of( String heading )`: returns `heading` for a block ref, else
  `HeadingSlugs.slug( heading )`.
- Modify:
  - `wikantik-main/src/main/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiLinkPostProcessor.java`
    (`pageLink`, ~191-209)
  - `wikantik-main/src/main/java/com/wikantik/wikilink/WikiEmbedRenderer.java`: `render` (href and
    title, ~103-124) and `sourceText` (~172-178)
  - `wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java`: `rewriteWikiLinks`
    (~397-415)
- Test:
  - `NativeWikiLinkPostProcessorTest` (add cases)
  - `WikiEmbedRendererTest` (add cases)
  - `WikiPageFormatFilterTest` (add cases)
  - `wikantik-frontend/src/utils/__fixtures__/wikilinks.json`: add the cases below. The Java parity
    test runs them now; the frontend side passes after Task 8.

**Interfaces:**
- Consumes: `BlockIds.find`, `BlockIds.sourceOf`, `BlockIds.isBlockRef`.
- Produces: `WikiLinkFragments.of(String)`; `EmbedResult` unchanged.

- [ ] **Step 1: Write the failing tests.**
  - `[[T#^abc]]` → `href` ends with `T#^abc`, text `T > ^abc`; `[[#^abc]]` → `#^abc`. Add both to
    `wikilinks.json` too.
  - Embed `![[T#^abc]]` where `T` has `Para ^abc` → the body contains `Para` and not `^abc`. The
    title is `T`; the href is `…/T#^abc`.
  - Embed of a missing block → class `wiki-embed-missing`, text `Block not found: ^zzz`.
  - Self-embed loop: `T` contains `x ![[T#^b]] ^b` → `Embed loop stopped`, no stack overflow.
  - ACL: a restricted `T` → `wiki-embed-restricted`, and `VAR_VIEWER_SENSITIVE` is set (as for
    headings).
  - `?format=md`: `[[T#^abc|see]]` → `[see](<base>/wiki/T#^abc)`.
- [ ] **Step 2: Run them and see them fail.**
  `mvn test -pl wikantik-main -Dtest='NativeWikiLinkPostProcessorTest,WikiEmbedRendererTest,WikiLinkParityTest' -q`
  and `mvn test -pl wikantik-rest -Dtest=WikiPageFormatFilterTest -q` → FAIL.
- [ ] **Step 3: Implement.**
  - Replace each `HeadingSlugs.slug( heading )` in those three spots with `WikiLinkFragments.of`.
  - In `sourceText`, a block ref goes through `BlockIds.find( text, idOf(heading) ).map( b -> BlockIds.sourceOf( text, b ) )`.
  - The missing message is `"Block not found: " + attr( heading )`, and the title is the page name
    when the target is a block ref.
- [ ] **Step 4: Run them and see them pass** (the same commands → PASS).
- [ ] **Step 5: Commit.** Message: `feat(blocks): [[Page#^id]] links and ![[Page#^id]] block embeds`.

---

### Task 4: `/blocks` endpoints, preview and assignment service

**Files:**
- Create:
  - `wikantik-main/src/main/java/com/wikantik/content/BlockIdService.java`
  - `wikantik-rest/src/main/java/com/wikantik/rest/PageBlocksHandler.java`
- Modify:
  - `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java`: route `GET …/{name}/blocks`
    and `POST …/{name}/blocks` to the handler (doGet near the `/embed` branch, doPost near
    `/rename`). Pass `section` beginning with `^` through block lookup in `handlePreview` (~837-863)
    and `handleEmbed` (~876-910).
- Test:
  - `wikantik-main/src/test/java/com/wikantik/content/BlockIdServiceTest.java`
  - `wikantik-rest/src/test/java/com/wikantik/rest/PageBlocksHandlerTest.java`
  - `PageResourceTest` (preview/embed cases)

**Interfaces:**
- Consumes: `BlockIds.*`, `PageSaveHelper`/`SaveOptions` (`expectedVersion`, `changeNote`, author as
  in `doPut`), `VersionConflictException`.
- Produces:
  - `BlockIdService`:
    - `public record Assigned( String id, int version ) {}`
    - `public Assigned assign( String pageName, int expectedVersion, int index, String fingerprint, String author ) throws WikiException`
    - It throws:
      - `BlockIdService.Refused( Reason reason )`, with `enum Reason { STALE, NOT_LOCATED, DERIVED }`;
      - or `VersionConflictException`, which the handler maps to STALE.
    - If the block already has an id, it returns that id without saving.
  - `GET /api/pages/{name}/blocks?q=&limit=20` → `{ "version": int, "canAssign": bool, "blocks": [ { "index": int, "id": string|null, "kind": "paragraph"|…, "text": string } ] }`
    - `text` is `plainText` cut to 120 chars;
    - `q` filters case-insensitively on text or id;
    - `limit` defaults to 20 and is capped at 100;
    - `canAssign` = edit permission and not derived.
  - `POST /api/pages/{name}/blocks` with body `{ "version": int, "index": int, "fingerprint": string }`
    → `{ "id": string, "version": int }`, or 409 `{ "reason": "stale"|"block-not-located"|"derived" }`.
  - Status codes: 404 when the page is missing or the caller can't view it (GET and POST); 403 when
    POST lacks edit.
  - Preview with `section=^id`: `excerpt` = the block's `plainText`, else the page excerpt plus
    `"blockMissing": true`.
  - Embed with `section=^id`: the Task 3 behaviour.

- [ ] **Step 1: Write the failing tests.**
  - **Service:**
    - assigns, and saves with change note `Added block id ^<id>` and the caller as author;
    - an existing id is returned with no save (verify the `saveText` call count is 0);
    - an out-of-range index → `NOT_LOCATED`;
    - a derived page (`derived_from` frontmatter) → `DERIVED`;
    - a fingerprint mismatch → still assigns, and a WARN is logged (assert via a log4j test
      appender, as other tests in the module do);
    - CRLF page text (Review Focus 1);
    - **concurrent:** two `assign` calls with `expectedVersion=5`, where the first saves v6 → the
      second throws a conflict that maps to `STALE`, and page text equals the first save's
      (Review Focus 5).
  - **Handler:**
    - JSON shapes as above;
    - `q` filtering and `limit` cap;
    - `canAssign` false for a view-only caller and for a derived page;
    - 404 for unviewable (not 403) and for missing;
    - 403 for POST without edit;
    - 409 bodies for each reason.
  - **Preview/embed:** the `section=^id` cases and `blockMissing`.
- [ ] **Step 2: Run them and see them fail** (`-Dtest` on the three classes) → FAIL.
- [ ] **Step 3: Implement.** The handler resolves the author exactly as `doPut` does
  (`AuthorKeys.authorKeyFor` when authenticated), and every error goes through
  `RestServletBase.sendError` or the JSON body (never `response.sendError`).
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit.** Message: `feat(blocks): /api/pages/{name}/blocks list + assign; ^id preview and embed`.

---

### Task 5: `ReaderText` and the derived-text consumers

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/parser/markdown/ReaderText.java`, with
  `public static String derived( String fullText )`. In Phase 1 it returns `BlockIds.strip`; Phase 2
  adds comment stripping here.
- Modify:
  - `WikiPageFormatFilter` (body before link rewriting)
  - `PageExcerpts.excerpt` (`wikantik-main/…/preview/PageExcerpts.java:59`)
  - `NodeTextAssembler.stripMarkdown` (`…/knowledge/NodeTextAssembler.java:62`)
  - `ContentChunker.chunkableBody` (`…/knowledge/chunking/ContentChunker.java:105`)
  - `DefaultLuceneIndexer` (body text, ~line 224)
  - knowledge-MCP `ReadPagesTool` / `GetPageTool` / `GetPageByIdTool`: **no change** in Phase 1.
    Markers stay on source surfaces (spec §1.11a).
- Test: add a case to each consumer's existing test class, plus `ReaderTextTest`. Add regression tests
  (no code change expected) to:
  - `MarkdownLinkScannerTest`: `[[T#^a]]` → target `T`;
  - `WikiLinkRenamerTest`: rename T→U keeps `#^a` and the alias;
  - `MentionMaskingTest`;
  - the Obsidian export converter test: `^id` markers and `[[T#^a]]` survive export.

**Interfaces:**
- Consumes: `BlockIds.strip`.
- Produces: `ReaderText.derived(String)`. Phase 2 extends it.

- [ ] **Step 1: Write the failing tests.**
  - Each consumer's output for `"Para ^abc\n"` contains `Para` and not `^abc`.
  - Chunker: the chunk text and hash are the same for `"Para\n"` and `"Para ^abc\n"`.
  - The regression tests as listed.
- [ ] **Step 2: Run them and see them fail** (the new consumer cases fail; the regression cases
  should already pass. If one fails, that's a real defect: fix it in this task).
- [ ] **Step 3: Implement** by routing each consumer's body through `ReaderText.derived`.
- [ ] **Step 4: Run them and see them pass** (`mvn test -pl wikantik-main,wikantik-rest -Dtest='…' -q`).
- [ ] **Step 5: Commit.** Message: `feat(blocks): ReaderText strips block markers from derived text`.

---

### Task 6: Broken block references report

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/references/BrokenBlockRefs.java`
  - `public record Entry( String page, String target, String id ) {}`
  - `public List< Entry > find( PageManager pages, WikiLinkResolver resolver )`
  - It scans every page's text with `WikiLinkSyntax.findAll`, keeps refs whose heading is a block
    ref and whose target exists (same-page refs check the page itself), and reports those where
    `BlockIds.find` is empty. Sorted by page, then target, then id.
- Modify:
  - `GetBrokenLinksTool` (wikantik-admin-mcp): add `brokenBlockRefs` (a list of
    `{page, target, id}`) and `blockRefCount`, and update the output schema and example.
  - `AdminContentResource.handleBrokenLinks`: add `brokenBlockRefs` to the JSON.
  - The admin UI component that renders `/admin/content` broken links (find it via
    `grep -rl "referrerCount" wikantik-frontend/src`): add a "Broken block references" table
    (Page · Link · Missing id).
- Test:
  - `BrokenBlockRefsTest`
  - `GetBrokenLinksToolTest`
  - `AdminContentResourceTest`
  - the admin component's vitest

- [ ] **Step 1: Write the failing tests.** Pages A (`see [[B#^x]] and [[B#^ok]] and [[#^self]]`),
  B (`Para ^ok`) and C (`![[Missing#^q]]`, where the target page doesn't exist). The report is
  `[(A,B,x), (A,A,self)]`. `Missing` is a broken *page* link and stays in the existing list.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.** Also run `McpProtocolIT`'s and
  `InstructionsRegistryDriftTest`'s unit counterparts if the output schema is asserted there.
- [ ] **Step 5: Commit.** Message: `feat(blocks): broken block references in get_broken_links and admin content`.

---

### Task 7: Importer keeps block ids

**Files:**
- Modify:
  - `wikantik-main/src/main/java/com/wikantik/importer/VaultBodyRewriter.java`: remove
    `TRAILING_BLOCK`/`LINE_BLOCK`, `stripBlockMarkers` and the `blockref:` warning.
  - `RewriteCtx.java`: remove `blockRefs` and `cutBlock`.
  - `WikiLinkRewriter.java`: keep `ref.heading()` verbatim, including `^id`.
- Test: `VaultBodyRewriterTest`, `WikiLinkRewriterTest` (invert the stripping assertions).

- [ ] **Step 1: Change the tests to expect preservation.**
  - `"Para ^abc"` is unchanged.
  - `[[Note#^abc]]` → `[[RenamedNote#^abc]]` when the target is renamed, unchanged otherwise.
  - `![[Note#^abc]]` stays a block embed.
  - No `blockref:` warning is emitted.
  - `%%` comment stripping is **unchanged** in Phase 1.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.** Run `mvn test -pl wikantik-main -Dtest='com.wikantik.importer.*Test' -q`.
- [ ] **Step 5: Commit.** Message: `feat(import): keep Obsidian block ids and [[x#^id]] links`.

---

### Task 8: Frontend block model, preview rendering and parity

**Files:**
- Create:
  - `wikantik-frontend/src/utils/blockIds.js`
  - `wikantik-frontend/src/utils/remarkBlockIds.js`
  - `wikantik-frontend/src/utils/__fixtures__/block-ids.json`
  - `wikantik-main/src/test/java/com/wikantik/markdown/extensions/blockids/BlockIdParityTest.java`
- Modify:
  - `wikantik-frontend/src/utils/wikiLinkSyntax.js` (`wikiLinkHref`, ~79-83)
  - `wikantik-frontend/src/components/PageEditor.jsx`: preview plugin list (~626), with
    `remarkBlockIds` before `remarkWikiLinks`.
  - `wikantik-frontend/src/components/WikiEmbed.jsx`: title and href for block sections (~58).
- Test: `blockIds.test.js`, `remarkBlockIds.test.jsx`, `wikiLinkSyntax.test.js`,
  `remarkWikiLinks.test.jsx` (fixture), `WikiEmbed.test.jsx`.

**Interfaces:**
- Produces, in `blockIds.js`:
  - `export const BLOCK_ID_RE` (inline: `/(?<=[ \t])\^([A-Za-z0-9-]+)[ \t]*$/`) and
    `STANDALONE_RE`
  - `export function isBlockRef(heading)`
  - `export function blocksFromMarkdown(md)` → `[{ id, kind, from, to, markerFrom, markerTo, text }]`
    over an mdast parse with gfm + math + the callouts plugin
  - `export function newBlockId(existingSet)`
  - `export function insertMarker(md, block, id)` → the new markdown
  - `export function blockFingerprint(text)` (same rule as the Java `fingerprint`)
- Produces: the `remarkBlockIds` default export, which sets `data.hProperties.id`, removes markers and
  unlinks standalone paragraphs (orphan → an `html` node `<span id="^x" class="block-anchor"></span>`).
- Fixture shape: `[{ "name", "markdown", "expected": [ { "tag": "p", "id": "^abc", "text": "Alpha" } ] }]`.
  It holds every Task 1 rendering case (paragraph, list item, table/quote/list/code/math standalone,
  orphan, heading literal, code/math exclusion, whitespace rule, duplicate, CRLF).

- [ ] **Step 1: Write the failing tests.** The JS fixture runner checks `remarkBlockIds` output. The
  Java `BlockIdParityTest` reads `../wikantik-frontend/src/utils/__fixtures__/block-ids.json` the way
  `WikiLinkParityTest` does and asserts server HTML. Also test `wikiLinkHref({heading:'^abc'…})` →
  `T#^abc`, the `#^` cases from `wikilinks.json`, and `blocksFromMarkdown`/`insertMarker`/
  `newBlockId`/`blockFingerprint` units mirroring Task 1.
- [ ] **Step 2: Run them and see them fail.**
  `cd wikantik-frontend && npx vitest run src/utils/blockIds.test.js src/utils/remarkBlockIds.test.jsx src/utils/wikiLinkSyntax.test.js src/utils/remarkWikiLinks.test.jsx`
  and `mvn test -pl wikantik-main -Dtest=BlockIdParityTest -q` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.** Run the same commands, plus `npx vitest run src/components/WikiEmbed.test.jsx`.
- [ ] **Step 5: Commit.** Message: `feat(blocks): editor preview block ids + server/client parity fixture`.

---

### Task 9: Reader — copy block link and landing

**Files:**
- Create: `wikantik-frontend/src/utils/blockLinks.js`
  - `export function addBlockLinkButtons(root, { pageName, canAssign, version, assign, onCopy, onError })`
  - It's idempotent (`data-blocklink-injected`), and the cleanup function it returns removes
    listeners.
- Modify:
  - `wikantik-frontend/src/api/client.js`: `getPageBlocks(name, { q, limit, signal })` and
    `assignBlockId(name, { version, index, fingerprint })`.
  - `wikantik-frontend/src/components/PageView.jsx`: call `addBlockLinkButtons` after
    `addHeadingAnchors` (~293). Land on fragments with `decodeURIComponent` (~300-314). On landing on
    a `^` target add the `block-target-flash` class, or show the toast `Block ^<id> not found on this
    page` when it's missing.
  - `wikantik-frontend/src/utils/fragmentScroll.js`: decode the id.
  - The link-preview hover card (find it via `grep -rl getPagePreview wikantik-frontend/src`): pass
    `section` for `#^id` hrefs and show `Block not found` when the response has `blockMissing: true`.
  - `wikantik-frontend/src/styles/article.css`:
    - `.block-link-btn` (gutter, hover-reveal, keyboard-focusable)
    - `.block-target-flash` (a 2 s background fade using a theme token, defined for light and dark;
      a static outline under `prefers-reduced-motion`)
    - `.block-anchor`
- Test: `blockLinks.test.js`, `PageView.test.jsx` (landing cases), `fragmentScroll.test.js`.

**Interfaces:**
- Consumes: `data-block`, `id^="^"` from Task 2; the endpoints from Task 4; `blockFingerprint` from
  Task 8.
- Behaviour:
  - Buttons go on `[data-block]` elements in the article whose
    `closest('.inserted-page, .wiki-embed')` is null.
  - An element with an `id` starting with `^` copies `[[<pageName>#^id]]`. The button's menu also has
    "Copy URL" (`${origin}${BASE}/wiki/<page>#^id`).
  - An element without an id gets a button only when `canAssign` (= `page.permissions.edit` and no
    `derived_from`).
  - Click → `assign` → set `el.id` → copy → update the known version.
  - Toasts: `Link copied` / `Couldn't copy link`.
  - On 409, the toast depends on `reason`:
    - `stale` → `This page changed — reload to copy a block link`;
    - `block-not-located` → `Couldn't locate this block — add an id in the editor`;
    - `derived` → `This page is generated; block ids can't be added here`.

- [ ] **Step 1: Write the failing tests.**
  - Buttons appear only on eligible elements: none inside `.inserted-page` or `.wiki-embed`
    (Review Focus 4), and none on id-less blocks when `canAssign=false`.
  - Copy text is exact.
  - The assign success path sets the id and calls copy with `[[P#^new1]]`.
  - Each 409 reason maps to its toast.
  - Landing: `#%5Eabc` scrolls to `#^abc` and flashes. A missing block toasts.
  - Idempotent re-run after an auth refetch.
- [ ] **Step 2: Run them and see them fail** (`npx vitest run` on the three files) → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit.** Message: `feat(blocks): reader copy-block-link, block landing flash`.

---

### Task 10: Editor — completion, command, live preview

**Files:**
- Modify:
  - `wikantik-frontend/src/utils/wikiLinkComplete.js`:
    - Extend `WIKI_TRIGGER` handling. A heading fragment starting with `^` goes to `blockOptions`;
      otherwise headings as today.
    - `createWikiLinkSource` gains deps `getBlocks(page|null)` and
      `assignBlock(page, version, index)`.
  - `wikantik-frontend/src/components/PageEditor.jsx`:
    - `getBlocks`: the current page → `blocksFromMarkdown(bodyRef.current)` mapped to
      `{ index, id, kind, text }` with `canAssign: true` (a local buffer insert); other pages →
      `api.getPageBlocks` (cache per page per editor session).
    - Wire the "Copy block link" command.
  - `wikantik-frontend/src/utils/editorCommands.js`: `cmd('copy-block-link', 'Copy block link', 'Editor', copyBlockLink, { keywords: ['block', 'reference', 'anchor'] })`.
  - `wikantik-frontend/src/utils/livePreview/ranges.js`: block-marker specs. Off the active line, a
    marker is a mark `cm-lp-blockid`; when it duplicates an earlier id it is `cm-lp-blockid-dup` with
    title `Duplicate block id — only the first ^<id> is linkable`.
  - `wikantik-frontend/src/utils/editorTheme.js`: styles for both classes (light and dark).
- Test: `wikiLinkComplete.test.js`, `ranges.test.js`, `editorCommands` test, and a `PageEditor`
  integration test for copy-block-link.

**Interfaces:**
- Completion `[[Page#^q`: options are labelled with the block text, with `^id` as detail when present.
  - Picking an id'd block inserts `[[Page#^id]]`.
  - Picking an id-less block:
    - on the current page, inserts ` ^<newBlockId>` at the block end (`insertMarker`) and the link,
      in **one** transaction;
    - on another page with `canAssign`, `await assignBlock(...)` then inserts;
    - otherwise the option is disabled with detail `no id — needs edit access`.
  - `[[#^q` is the current page's blocks. `[[Page#q` (no caret) is headings only, unchanged.
- "Copy block link": on the block at the cursor (`blocksFromMarkdown` → the containing range), adds a
  marker if missing (one undo step) and copies `[[<pageName>#^id]]`. With no block at the cursor, the
  toast reads `Put the cursor in a paragraph, list item or table`.

- [ ] **Step 1: Write the failing tests.**
  - Completion branches: id'd, current-page insert as a single transaction, remote assign called
    with `(page, version, index)`, `canAssign=false` disabled, `[[Page#` still headings.
  - Live-preview spec output for an active line vs an inactive line, and a duplicate.
  - The command adds a marker and copies; with no block, the toast.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.** Run `npx vitest run src/utils src/components/PageEditor.test.jsx`.
  Run `npm run lint` too.
- [ ] **Step 5: Commit.** Message: `feat(blocks): [[Page#^ completion, Copy block link command, live-preview markers`.

---

### Task 11: Integration tests and docs

**Files:**
- Modify:
  - `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WikiLinksIT.java`
    (or a new `BlockRefsIT.java` beside it)
  - the Selenide module (`wikantik-it-tests/wikantik-selenide-tests`): a new `BlockRefsIT`, following
    the existing wikilink/embed browser ITs
  - `docs/wikantik-pages/EditPageHelp.md`: block references section
  - `wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt` (find the actual path with
    `git ls-files | grep mcp-instructions`): a short paragraph on `^id`, `[[P#^id]]` and
    `![[P#^id]]`, and on adding an id by writing a marker through `update_page`. **The tool list is
    unchanged.**
  - `docs/superpowers/specs/2026-10-01-native-wikilinks-design.md` line 13: point the out-of-scope
    note at the new spec.
- **REST IT cases:**
  - save a page with `Para ^abc` and `[[Self#^abc]]` → the rendered HTML has `id="^abc"` and
    `href` ending `#^abc`;
  - `GET /embed?section=^abc`;
  - `GET /blocks` shape;
  - `POST /blocks` assigns and bumps the version;
  - the same POST again with the old version → 409 `stale`;
  - `?format=md` keeps `#^abc` and has no marker.
- **Selenide cases:**
  - hover a paragraph without an id → click the button → the clipboard (read via the JS clipboard
    shim the other copy ITs use) is `[[Page#^xxxxxx]]`, and a reload shows `id` on that paragraph;
  - open `/wiki/Page#^abc` → the element is in view and has `block-target-flash`;
  - in the editor, typing `[[Other#^Al` and picking a suggestion inserts `[[Other#^…]]`.

- [ ] **Step 1: Write the ITs.**
- [ ] **Step 2: Run them.** Run `bin/run-tests.sh --module rest` and the Selenide module through
  `bin/run-tests.sh` (never a bare `-pl` failsafe run). Expect PASS. Any failure → fix in the owning
  task's code.
- [ ] **Step 3: Write the docs changes.** Run `ConfigSurfaceDriftTest`/`InstructionsRegistryDriftTest`
  locally → PASS.
- [ ] **Step 4: Commit.** Message: `test(blocks): REST + browser ITs; docs: block references help and MCP instructions`.

---

### Task 12: Corpus audit (read-only; before deploy, not before merge)

**Files:**
- Create: `docs/superpowers/specs/2026-10-31-obsidian-syntax-corpus-audit.md`, section "Phase 1 —
  block markers".

- [ ] **Step 1:** List every page from the **live** index (knowledge MCP `list_pages_by_filter` with a
  high limit, or `list_pages`), and fetch bodies with `read_pages` (max 20 per call). In a scratchpad
  script, run the Task 1 rules over the bodies: lines ending in whitespace + `^[A-Za-z0-9-]+` outside
  code and math, and lines that are only `^[A-Za-z0-9-]+`. Repeat over `docs/wikantik-pages/*.md`.
- [ ] **Step 2:** Record every hit in the audit doc (page, line, snippet, intended/unintended).
- [ ] **Step 3:** For unintended hits, list the escape (`\^`) fix. **Do not edit prod pages in this
  task**: prod content changes happen at deploy time, with the user's go-ahead.
- [ ] **Step 4: Commit.** Message: `docs(blocks): Phase 1 corpus audit`.

---

### Final gate

- [ ] Run `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4` and poll `status`/`wait`
  until it reports SUCCESS or FAILED. Fix every red; no "pre-existing" exemptions.
- [ ] Run `mvn pmd:check -Pcomplexity-gate` → PASS.
- [ ] Update the wiki page `ObsidianBlockReferencesDesign` status line to "Phase 1 implemented on main
  (unreleased)".
