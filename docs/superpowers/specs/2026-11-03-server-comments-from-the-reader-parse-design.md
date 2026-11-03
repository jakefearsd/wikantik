# Server comments and tags from the reader's parse (#127)

Status: design, 2026-11-03. Issue: #127. Follows `docs/superpowers/plans/2026-11-02-one-editor-dialect.md` (the editor
half, released in 2.5.7).

## Why

A `%%comment%%` is hidden from readers. Where it opens and closes depends on code and math, because a `%%` inside code or
math is literal. Today two programs decide that:

- **The editor** takes code and math from its CommonMark syntax tree (Lezer). Shipped in 2.5.7.
- **The server** takes them from a hand-written text mask in wikantik-api: `ObsidianComments.mask`, built from
  `CodeMask`, `QuotedFences`, `MathMask`, `GfmTableLines` and `GfmTableRows`.

Wherever the mask misreads block structure, the author sees text dimmed as a hidden comment that readers are shown. The
2.5.7 work fixed the largest classes. #127 lists the rest:

- a wikilink holding a backtick or `$`;
- backticks in HTML attributes or autolinks;
- an escaped backtick;
- a code span in a table cell that holds pipes;
- a table that starts on a list item's first line;
- display math whose list item ends before the closer.

Each of these is a case of the mask re-deriving block structure by hand. Patching them one at a time is the loop the
editor work got out of. The server already has a parser that reads block structure exactly as readers see it, because
it *is* the reader. The fix is to ask it.

The same mask also decides which `#tags` the index counts (`InlineTagSyntax.scan` masks code, math and comments with
it). The rendered page decides tags from flexmark's own `InlineTag` nodes. So the tag index and the page are a second
pair of readers that can disagree. Today `inline-tags.json` keeps them close.

## Goals

1. **Comment ranges come from the reader's parse.** On the server, a `%%` is a marker exactly when it lies outside the
   code and math nodes of a flexmark parse of the raw body. That parse is prepared as the render prepares it.
2. **One server implementation of comment ranges.** It is used by every caller: render, reader text, block ids, access
   rules (`SpanClassifier` IN_COMMENT), references, embeds, `PageResource` and tags.
3. **Tags come from the reader's nodes** (Phase B). The index counts the `InlineTag` nodes the render would draw. A text
   scan that approximates them is no longer needed.
4. **The hand masks go:** `MathMask`, `QuotedFences`, `GfmTableLines`, `GfmTableRows`, `ObsidianComments.mask`, and
   `InlineTagSyntax`'s scanner with `InlineTagMasks` and `InlineTagTables`.
5. **The reader is the arbiter.** A case where the editor and the reader disagree is fixed on whichever side differs from
   the rendered page. That side is usually the editor once the server reads its own parse.

## Non-goals

- **Wikilink extraction and the vault importer.** `WikiLinkSyntax.findAll` and `importer/CodeSegments` keep `CodeMask`.
  Their failure mode is a wrong Page Graph edge or a rewritten link inside code, not a leak. On the prod corpus the mask
  now disagrees with flexmark on 51 characters (2.5.7 measurement). This gets a follow-up issue with that number, not
  this design.
- **No change to the comment rule itself:**
  - the next free `%%` closes;
  - unterminated means literal;
  - code wins (a backtick inside a comment can pair with a later one);
  - newlines are kept when a comment is stripped;
  - hidden, not secret.
- **No change to access-rule semantics.** IN_COMMENT stays server-only and fail-closed.

## Design

### Phase A: comments

**`ReaderComments`** (new, wikantik-main, `com.wikantik.parser.markdown`). It is the only server implementation of
comment ranges.

```java
static List< int[] > ranges( String body )  // [from, to) per comment, body coordinates, delimiters included
static String strip( String body )          // newlines kept; the same instance when nothing was removed
```

- **No `%%` in the body:** it returns at once, with no parse. That's the cost almost every page pays (on prod, 1,459 of
  1,467 pages).
- **Otherwise:**
  1. **Parse the raw body as the render parses it.** Run `MarkdownBodySource.prepare` (plugin `()` insertion, the
     `$$`→`math` fence rewrite), then the structural parser with `InlineMathParser` under the #105 parse budget. This is
     the parse `RenderPathAccessRules.structure` already runs.
  2. **Take the opaque spans:** `Code`, `IndentedCodeBlock`, `FencedCodeBlock`, `GitLabInlineMath`, and display-math
     blocks, mapped back to body offsets. HTML is not opaque, matching the editor's `opaqueRanges` set.
  3. **Pair the markers** with the editor's rule (`commentRangesInTree`): a marker is free when neither of its two
     characters is inside a span. Spans are sorted, so each marker check is a binary search. This keeps the pass linear,
     as on the editor side.

**Display math takes its extent from the parse, not the pre-pass.** `RenderPathAccessRules` currently adds
`prepared.displayMathInBody()`, the rewrite's own record, because the generated fence starts in generated markup and maps
to no body offset. That record ignores containers, which is the "display math in a list item" case in #127. The reader
actually renders the generated `FencedCodeBlock`, and flexmark ends that block with its container. So the math span is
that node's extent, mapped back through the rewrite's edits:

- the start comes from the edit's body offset;
- the end comes from the node's end.

This fixes the access-rule structure in the same change, because the two share the classification.

**Sharing one parse.** `RenderPathAccessRules.structure()` and `ReaderComments` classify the same raw-body parse. Factor
the parse and classification out of `RenderPathAccessRules` into a package-private `ReaderStructure.of(body)`. It returns
`code`, `math` and `html` spans plus the existing rule and label lists. `ReaderComments` uses only the opaque spans; the
access-rule reader keeps the rest. Whether a save then parses one body twice (once per caller) is **measured with the
existing parse-count seams** (`RenderPathAccessRules.parses()`, `BlockModel.parsesOnThisThread()`). Add a memo only if it
does; it is not in the design until a test shows the second parse.

**Failure: fail toward hiding.** When the raw parse is `Unreadable` (budget exhausted, parser error), `ReaderComments`
logs a WARN with the body length and reason. It then pairs markers as if the body held no code or math. Two
consequences:

- **The author's comments stay hidden.** The cost is that a `%%` inside code may also pair, which hides more, not less.
- **An access rule inside such a comment still locks the page.** `SpanClassifier` reads the same ranges.
  `AccessRules.read` already locks a page whose render-path reading is `Unreadable`, so nothing here can unlock one.

**Migration.** Every server caller of `ObsidianComments.strip` / `ranges` moves to `ReaderComments`:

- `MarkdownParser`, `RenderPathAccessRules.commentStrippedStructure`
- `BlockModel`, `ReaderText`
- `DefaultReferenceManager`, `WikiEmbedRenderer`
- `SpanClassifier`, `PageResource`

wikantik-api keeps no comment logic. Its one caller, `InlineTagSyntax`, gets its comment-free body from the caller in
Phase B. Between the phases, `InlineTagSyntax` keeps calling the old `ObsidianComments` in wikantik-api. The two server
implementations then coexist for exactly one phase, and the Phase A tests name the tag path as the one remaining user.

### Phase B: tags from the reader's nodes

**`ReaderTags.of(body)`** (new, wikantik-main): the page's inline tags, written and normalised forms, in document order.
It is computed by:

1. stripping the body's comments (`ReaderComments.strip`);
2. parsing it with the render's options, which register `InlineTagExtension`;
3. keeping each `InlineTag` node that `InlineTagNodeRenderer` would draw.

The renderer's skip rule ("not inside a Link, WikiLink, Image or defined `RefNode`") moves into one predicate,
`InlineTagNodes.shown(node)`, called by both the renderer and `ReaderTags`. That makes the page and the index one rule by
construction.

**What changes in the tag callers:**
- **Indexers:** `DefaultLuceneIndexer`, `DefaultStructuralIndexService` and `PageDescriptor.inlineTags` take tags from
  `ReaderTags`. That costs one parse per page at index time, the same parse a render does. A body with no `#` skips the
  parse.
- **Query time:** `PageListEngine.tagsMatch` (wikantik-knowledge, depends on wikantik-main) stops re-scanning bodies.
  Today it reads each candidate page's text when its frontmatter tags don't satisfy the filter. Running a parse there
  would put a parse per candidate per query on the hot path. It reads the inline tags the structural index already holds
  on the page's descriptor instead. *To verify in planning:* `PageListEngine` can reach the structural index, and the
  descriptor is fresh at query time. If not, it calls `ReaderTags`, and the plan sets a cost guard.
- **What stays in wikantik-api:** the lexical rules (`normalise`, `isTagText`, `isTagChar`) and `findableTags`'s merge
  of frontmatter and inline tags, which takes the inline list as an argument. `InlineTagSyntax.scan`, `InlineTagMasks`,
  `InlineTagTables` and `ObsidianComments` are deleted, together with `MathMask`, `QuotedFences`, `GfmTableLines` and
  `GfmTableRows`. `CodeMask` stays for wikilinks and the importer (non-goal).

## How it is tested

The principle: every test's expected value comes from the **rendered page** or from a fixture the rendered page
already passes. Expected values never come from the implementation under test. Each step is test-first: the test is
shown failing against the current mask before the fix.

1. **Shared fixtures, now run against `ReaderComments`.** `comments.json` (`CommentsParityTest` in Java, the editor's
   vitest runner in JS) gains one case per #127 shape. The expected value is what the rendered page shows.
   `inline-tags.json` runs against `ReaderTags` in Phase B. `live-preview-parity.json` and `block-ids.json` stay green
   unchanged.

2. **A render oracle, so the fixtures cannot drift from the reader.** For every `comments.json` case, plus a fuzz set
   (point 3), a Java test renders the body through `MarkdownParser`. It then asserts that a sentinel word placed inside
   each `%%…%%` is absent from the HTML exactly when `ReaderComments` calls it a comment. In Phase B the same test
   asserts that the HTML's `a.inline-tag` set equals `ReaderTags.of(body)`. This is the check the hand masks never had:
   the server's rule against the server's own output.

3. **The regression guard #127 asks for: a checked-in differential fixture.** A Java generator, seeded, mixes:
   - code spans and fences;
   - `$`/`$$`, tables, list items, quotes and callouts;
   - HTML, autolinks, wikilinks and escapes;
   - `%%` markers.

   It is a port of the generator from the 2.5.7 fuzz, which found 601 disagreements in 25,633 documents. It writes about
   2,000 documents and the server's comment ranges to `src/test/resources/…/comments-differential.json`, which is
   checked in.
   - **Staleness check:** a Java test regenerates the file and fails if it differs, naming the command that refreshes
     it (the `ConfigReferenceRegressionTest` pattern).
   - **Editor check:** a vitest test asserts `commentRanges(doc)` equals the server's ranges for every document.
   - **Allow-list:** the target is zero disagreements. Any case left over goes into an explicit list in the fixture,
     each entry with the side that differs from the render and why. The test fails if the list grows.

4. **The access-rule locks.** The existing #104 tests stay green: `AccessRules*`, `SpanClassifier*`, and the
   `LockedAccessRulesFixture` IT page. New tests, each first run against the old mask:
   - **The misread shapes:** an access rule inside a comment of each #127 shape locks the page (IN_COMMENT).
   - **An `Unreadable` raw parse:** forced through the parse budget's test seam, its comments fall back to hiding and
     the page locks.
   - **The `RenderPathAccessRules.structure` math extent:** display math in a list item that ends before the closer is
     math only up to the item's end, as the render draws it.

5. **Cost.**
   - **No `%%`:** a body with none costs zero extra parses through `ReaderComments` (parse-count seams), so 99% of
     pages pay nothing.
   - **With `%%`:** a body holding `%%` costs exactly one raw-body parse per `ReaderComments` call.
   - **Per save:** a test records how many raw-body parses one save performs (render, block ids, access rules, index),
     to decide the memo question above.
   - **Linear time:** a guard by ratio, not milliseconds, on a body of many comments, code spans and math spans. The JS
     `linearCost` method, ported: warm-up, interleaved best-of-5, bound `factor^1.5`.

6. **The corpus, before deploy** (the 2.5.7 check, made repeatable). The scratch harness from 2.5.7 becomes a
   test-scope tool: it copied the prod pages byte-exact, compiled the released wikantik-api, and diffed old against new
   and against flexmark. Its name and location are left to the plan. It runs the old (2.5.7) server and the new one over
   a page directory and reports:
   - each page whose reader text, comment ranges or tag set changed, with a snippet;
   - per page, whether the change moves toward or away from the render.

   It runs over the repo corpus in the gate. Over a byte-exact prod copy (`docker exec repo-wikantik-1 tar …`) it runs
   once before the deploy, with the user's authorisation for the read. The release note states the result.

7. **The gate.** `bin/run-tests.sh --parallel 4` (unit, PMD ratchet, eslint, `vitest --coverage`, ITs) after each
   phase. `--all` before the release.

## Phasing and rollout

| Phase | Ships | Deletes |
|------|-------|---------|
| A | `ReaderStructure`, `ReaderComments`, display-math extent from the parse, all comment callers migrated, tests 1–6 for comments | nothing yet (`InlineTagSyntax` still reads the old comment mask) |
| B | `ReaderTags`, `InlineTagNodes.shown`, indexers + `PageListEngine` on reader tags, tests 1–6 for tags | `ObsidianComments`, `MathMask`, `QuotedFences`, `GfmTableLines`, `GfmTableRows`, `InlineTagSyntax.scan`, `InlineTagMasks`, `InlineTagTables` |

Each phase is releasable alone. Phase B changes what the tag index holds, so it needs a reindex after deploy. 2.5.6 did
one; the runbook is the same.

## Risks

- **Parse cost at index time** (Phase B). Mitigated by skipping bodies without `#` and by measurement (test 5); an index
  rebuild already parses every page for the render cache.
- **Reader and editor disagree** where the editor is the one that's wrong. Test 2 finds them. Each is fixed in the
  editor dialect, with the case added to the shared fixture, not to an allow-list.
- **Coordinate mapping** through `MarkdownBodySource.Prepared` (plugin `()` insertions, math rewrite edits). Already
  exercised by `RenderPathAccessRules` and `BlockModel`. The display-math end mapping is new and gets its own test.
