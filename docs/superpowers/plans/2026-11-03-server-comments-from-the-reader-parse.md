# Server Comments and Tags from the Reader's Parse — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The server takes `%%comment%%` boundaries (Phase A) and inline `#tag`s (Phase B) from flexmark's parse of the
page as the render reads it. After Phase B the hand-written code/math masks in wikantik-api are deleted (#127).

**Architecture:** The raw-body parse and its code/math/HTML classification move out of `RenderPathAccessRules` into
`ReaderStructure` (wikantik-main). `ReaderComments` pairs `%%` markers outside its code and math spans, with the
editor's rule; every server caller moves to it. In Phase B, `ReaderTags` keeps the `InlineTag` nodes the renderer
draws, and the indexers and `PageListEngine` take tags from it or from the structural index.

**Tech Stack:** Java 25, flexmark (wikantik-main), JUnit 5, Gson; vitest 4 (frontend fixture consumer).

**Spec:** `docs/superpowers/specs/2026-11-03-server-comments-from-the-reader-parse-design.md`

## Global Constraints

- wikantik-api stays parser-free: no flexmark import there, ever.
- Test-first: each new test is run and seen failing against the current code before the fix (CLAUDE.md).
- Never an empty catch: log at least `LOG.warn()` with context and message.
- PMD complexity ratchet (`mvn pmd:check -Pcomplexity-gate`): never add a baseline entry; entries only come out.
- Linear time in the input on every new scan; guards compare ratios, never absolute milliseconds.
- Every mask/scanner test gets a CRLF variant (prod: 1,450 of 1,467 pages are CRLF).
- Frontend timing tests use `linearCost` + `LINEAR_COST_TIMEOUT_MS`; tests that read `syntaxTree` build state with `fullyParsed`.
- The comment rule is unchanged:
  - the next free `%%` closes;
  - an unterminated `%%` is literal;
  - code wins;
  - newlines are kept when a comment is stripped;
  - `strip` returns the same instance when nothing is removed.
- Expected values come from the rendered page or a fixture the rendered page passes, never from the code under test.
- Stage files by name (never `git add -A`); commit messages 1–3 lines with the session's trailer lines.
- Work on main; one full gate (`bin/run-tests.sh --parallel 4`) at the end of each phase, not per task.

## Review Focus

1. **CRLF bodies:** a `\r\n` body yields the same comment ranges as its `\n` form, shifted by the `\r`s (Task 3 test).
2. **A `%%` next to or around a `[{Plugin}]`:** `prepare` inserts `()`, so the parse's offsets differ from the body's. Marker
   positions must map back exactly, and a comment wrapping a plugin hides it (Task 3 test).
3. **A comment around a display-math block:** a comment opening before a `$$` block and closing after it, or a `$$`
   inside a comment. The math rewrite's edits must not shift the ranges, and the range is in body coordinates (Task 3
   test).
4. **Frontmatter:** callers with full text pass only the body. A `%%` in YAML frontmatter is never a marker, and ranges
   come back in the caller's coordinates (Task 4 test, `SpanClassifier` and `BlockModel`).
5. **Empty input:** `null`, empty and no-`%%` bodies return an empty list and the same `strip` instance, with zero
   parses (Task 3 test).

---

## Phase A — comments

### Task 1: `ReaderStructure` (move-only extraction from `RenderPathAccessRules`)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/parser/markdown/ReaderStructure.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/RenderPathAccessRules.java`
- Test: `wikantik-main/src/test/java/com/wikantik/parser/markdown/ReaderStructureTest.java`

**Interfaces:**
- Produces:
  - `final class ReaderStructure` (package-private).
  - `static RenderPathAccessRules.Structure of( String body, int bodyStart )` parses with `BudgetedText.budgetFor( body.length() )`.
  - `static RenderPathAccessRules.Structure of( String body, int bodyStart, long budget )`, a test seam.
  - `static long parses()`, a test seam counting raw-body parses.
  - Both throw `RenderPathAccessRules.Unreadable` exactly as `parsed` does today.
- `RenderPathAccessRules.find` / `structure` / `commentStrippedStructure` delegate to it. `RenderPathAccessRules.parses()`
  returns `ReaderStructure.parses()`, so existing seam users keep working.

- [ ] **Step 1: Write `ReaderStructureTest.ofClassifiesCodeMathHtmlAndRulesAsRenderPathAccessRulesDid`.** For three
  bodies, assert `ReaderStructure.of( body, 0 )` equals `RenderPathAccessRules.structure( body )` list by list (compare
  `int[]` lists with `Arrays.equals` per element). The bodies are:
  - `` "a `x` $y$ <b>h</b> [{ALLOW view Admin}]\n\n$$\nz\n$$\n" ``
  - a fenced block
  - an indented block

  Add `budgetExhaustionIsUnreadable`: `assertThrows( RenderPathAccessRules.Unreadable.class, () -> ReaderStructure.of( "a `b` c", 0, 1 ) )`.
- [ ] **Step 2: Run it and see it fail to compile.** `mvn -o -q test -pl wikantik-main -Dtest=ReaderStructureTest`.
- [ ] **Step 3: Move `PARSER`, `PARSES`, `parsed`, `parsedStructure`, `rule`, `regionKind` and `region` into
  `ReaderStructure`.** Leave `Structure`, `Rule`, `Unreadable` and `RuleLabels` where they are; no behaviour change.
- [ ] **Step 4: Run `ReaderStructureTest`, `RenderPathAccessRulesTest`, and `mvn -o -q test -pl wikantik-main -Dtest='*AccessRule*,BlockIds*,ReaderText*'`; all pass.**
- [ ] **Step 5: Commit** `refactor(reader): the raw-body parse and its classification move to ReaderStructure`.

### Task 2: Display math takes its extent from the parsed fence

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownBodySource.java` (`Prepared`)
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/ReaderStructure.java`
- Test: `ReaderStructureTest.java`; `wikantik-main/src/test/java/com/wikantik/parser/markdown/MarkdownBodySourceTest.java` (create if absent)

**Interfaces:**
- Produces: `int[] Prepared.displayMathNodeInBody( int nodeStart, int nodeEnd )`. It returns `{bodyStart, bodyEnd}` for
  a parsed `FencedCodeBlock` that one display-math edit generated, or `null` if no edit generated it:
  - **start:** the edit's `fromNormalized( sourceStart )`.
  - **end:** if `nodeEnd >= edit.targetEnd()`, the edit's body end (as `displayMathInBody`). Otherwise
    `toBodyEnd( nodeEnd )`, because the container ended the fence early.
- `ReaderStructure` records math from those nodes instead of `prepared.displayMathInBody()`. `displayMathInBody` is
  deleted if nothing else calls it.

- [ ] **Step 1: Write `ReaderStructureTest.displayMathInAListItemEndsWithTheItem`.** Body:
  `"- item\n  $$\n  x\n- next $$ tail\n"`, or whichever list shape the reader closes before the closer. First confirm
  the shape by rendering it (`MarkdownParser` via `TestEngine`, as `CommentsParityTest.render` does) and checking that
  `math-display` stops at the item. Assert:
  - `of( body, 0 ).math()` holds one span starting at `body.indexOf( "$$" )`;
  - that span ends at or before `body.indexOf( "- next" )`.

  Add `displayMathAtTopLevelStillSpansDelimiters`: `"a\n\n$$\nx\n$$\n\nb"` gives one math span equal to
  `[indexOf("$$"), lastIndexOf("$$")+2]`.
- [ ] **Step 2: Run them and see the list case fail** (the current span runs to the second `$$`).
- [ ] **Step 3: Implement `displayMathNodeInBody` and use it** for each `FencedCodeBlock` whose start maps to `-1`.
- [ ] **Step 4: Run the Task 1 command plus `-Dtest='*Math*'`; all pass.**
- [ ] **Step 5: Commit** `fix(reader): display math ends where the render ends it (a list item closes its fence)`.

### Task 3: `ReaderComments`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/parser/markdown/ReaderComments.java`
- Modify: `wikantik-frontend/src/utils/__fixtures__/comments.json` (append rows)
- Modify: `wikantik-main/src/test/java/com/wikantik/markdown/CommentsParityTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/parser/markdown/ReaderCommentsTest.java`

**Interfaces:**
- Consumes: `ReaderStructure.of( body, 0[, budget] )` (Task 1); its `code()` and `math()` lists are the opaque spans.
- Produces (`public final class ReaderComments`):
  - `public static List< int[] > ranges( String body )`: `[from, to)` in body coordinates, delimiters included, in
    order. `null`/no-`%%` gives `List.of()` with no parse.
  - `public static String strip( String body )`: newlines kept, the same instance when nothing is removed.
  - `static List< int[] > ranges( String body, long budget )`: the test seam.
  - On `Unreadable`: one `LOG.warn( "comment boundaries read without code/math: the parse of a {}-character body failed: {}", len, reason )`,
    then pairing with no opaque spans.

- [ ] **Step 1: Append one `comments.json` row per #127 shape.** The rows:
  - `` [[Note `x]] %%secret%% `y` z ``
  - `` <!-- `x -->%%secret%%<!-- ` --> ``
  - `` <http://a.b/`x> %%secret%% `y` ``
  - `` \`a %%secret%% `b` ``
  - `` | `x | %%secret%% | y` | `` with a header and delimiter row
  - `- | a | b |\n  |---|---|\n  | $5 | x |\n  | %%secret%% | y$ |`
  - the Task 2 display-math-in-a-list shape with a `%%secret%%` after the item

  For each row, `output` is what the **rendered page** shows: render it, and the row's comment is hidden iff `secret` is
  absent from the HTML. Run `npx --no-install vitest run src/utils/obsidianComments.test.js` and confirm the editor
  passes every new row. A row the editor fails is an editor defect: stop and report it; do not edit the row to match.
- [ ] **Step 2: Write `ReaderCommentsTest`, then switch `CommentsParityTest.stripMatchesTheSharedFixture` to
  `ReaderComments.strip`.** `ReaderCommentsTest` covers Review Focus 1, 2, 3 and 5:
  - `crlfBodiesMatchTheirLfForm`: ranges of `lf.replace("\n","\r\n")` equal the LF ranges with every offset shifted by
    the `\r`s before it, over the fixture rows.
  - `aCommentAroundAPluginHidesItAndMapsBack`: `"a %%[{TableOfContents}]%% b"` gives one range `[2, 25)`, and the
    `strip` result is `"a  b"`.
  - `aCommentSpanningDisplayMathIsInBodyCoordinates`: `"x %%c\n\n$$\ny\n$$\n\nd%% e"` gives one range from
    `indexOf("%%")` to `lastIndexOf("%%")+2`. And `"$$\n%%no%%\n$$"` gives none.
  - `emptyInputsCostNoParse`: for `null`, `""` and `"plain"`, `ranges` is empty, `strip` returns the same instance, and
    `ReaderStructure.parses()` is unchanged.
  - `anUnreadableParseHidesEveryFreePair`: `ranges( "a `%%x%%` b", 1 )` gives one range over `%%x%%` (code ignored).
  - `linearInComments`: ratio guard over `"%%a%% `b` $c$ "` repeated n times, n = 2000 vs 8000. Interleaved best-of-5,
    `ratio < 4^1.5` (port `linearCost`'s method; keep it private in the test).
- [ ] **Step 3: Run both and see them fail:** compile error, then the #127 rows mismatching against `ObsidianComments`
  semantics.
- [ ] **Step 4: Implement `ReaderComments`.** Pairing is the editor's `commentRangesInTree`: merge `code()` and `math()`
  sorted, binary-search each marker's two characters, take the next free `%%`.
- [ ] **Step 5: Run `ReaderCommentsTest`, `CommentsParityTest` and the vitest file; all pass.** Commit
  `feat(reader): ReaderComments reads %% boundaries off the render's parse (#127)`.

### Task 4: Every server caller uses `ReaderComments`

**Files:**
- Modify:
  - `parser/markdown/MarkdownParser.java:91`, `parser/markdown/RenderPathAccessRules.java` (`commentStrippedStructure`)
  - `parser/markdown/BlockModel.java:115`, `parser/markdown/ReaderText.java:79,82,95`
  - `pagegraph/references/DefaultReferenceManager.java:673`, `wikilink/WikiEmbedRenderer.java:177`
  - `auth/acl/SpanClassifier.java:95`
  - `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java:912`

  (paths under `wikantik-main/src/main/java/com/wikantik/` unless given)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/acl/SpanClassifierTest.java` (or the existing access-rules test class); `wikantik-main/src/test/java/com/wikantik/parser/markdown/ReaderCommentsCallersTest.java`

**Interfaces:**
- Consumes: `ReaderComments.ranges` / `strip` (Task 3).
- Produces: no wikantik-main or wikantik-rest source references `com.wikantik.api.markdown.ObsidianComments`
  (`grep -rn "ObsidianComments" wikantik-main/src/main wikantik-rest/src/main` is empty). The one remaining user is
  `InlineTagSyntax` in wikantik-api, until Task 10.

- [ ] **Step 1: Write the failing tests:**
  - `SpanClassifier`: for each #127 shape with `[{ALLOW view Admin}]` placed inside the `%%…%%`, `AccessRules.read`
    returns `Locked` with shape `IN_COMMENT`.
  - `ReaderCommentsCallersTest.aPercentInFrontmatterIsNoMarker` (Review Focus 4): a page
    `"---\nnote: '%%x'\n---\nbody %%c%% end"` renders without `c` and keeps `note` in the frontmatter.
  - `BlockIds` on the same text: a `^id` after the comment keeps ordinal 1.
  - `ReaderCommentsCallersTest.parsesPerSave`: save one page holding `%%` through `TestEngine` and record
    `ReaderStructure.parses()` before and after. Assert the count is at most the number of distinct callers (render,
    block ids, access rules, references, index), and print it. **This is the measurement for the memo question:**
    report the number in the task report; a memo is out of scope unless the controller rules otherwise.
- [ ] **Step 2: Run them; the #127 shapes fail** (the old mask misses them).
- [ ] **Step 3: Replace each call;** imports change from `com.wikantik.api.markdown.ObsidianComments` to
  `com.wikantik.parser.markdown.ReaderComments`.
- [ ] **Step 4: Run `mvn -o -q test -pl wikantik-main,wikantik-rest -Dtest='*Comment*,*AccessRule*,*SpanClassifier*,BlockIds*,ReaderText*,*Embed*,*ReferenceManager*,PageResource*' -Dsurefire.failIfNoSpecifiedTests=false`;
  all pass.**
- [ ] **Step 5: Commit** `refactor(comments): every server caller reads comments off the render's parse`.

### Task 5: Render oracle and the checked-in differential fixture

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/markdown/CommentsDifferentialFixture.java`, the seeded generator
  plus `main()` that writes the fixture.
- Create: `wikantik-frontend/src/utils/__fixtures__/comments-differential.json`
- Create: `wikantik-main/src/test/java/com/wikantik/markdown/CommentsDifferentialTest.java`
- Create: `wikantik-frontend/src/utils/obsidianComments.differential.test.js`

**Interfaces:**
- Produces: `comments-differential.json` =
  `{ "seed": 127, "count": 2000, "generator": "<one-line description>", "allow": [ {"input","side","reason"} ], "cases": [ {"input", "ranges": [[from,to],…]} ] }`.
  - **Generator:** a `java.util.Random(127)` port of the 2.5.7 token generator. Concatenate 4–17 tokens drawn from this
    list, and keep a document only if it contains `%%`, until 2,000 are kept:
    `"%%","%%","%%","`","`","$","$$\n","|"," | ","\n","\n\n","- ","1. ","> ","    ","  ","```\n","[[","]]","<b t=\"","\">","\\","a","b "," ","|---|---|\n","#t ","==","x\n","$x$"`
  - **Ranges:** `ReaderComments.ranges`.

- [ ] **Step 1: Write the three tests:**
  - **`CommentsDifferentialTest.fixtureIsCurrent`:** regenerate in memory and assert it equals the checked-in file. The
    failure message names the refresh command:
    `mvn -o -q test-compile exec:java -pl wikantik-main -Dexec.classpathScope=test -Dexec.mainClass=com.wikantik.markdown.CommentsDifferentialFixture`,
    or whatever invocation actually works; document it in the class Javadoc.
  - **`CommentsDifferentialTest.everyCaseRendersAsItsRangesSay`** (the render oracle): for each case, replace each
    comment's interior with a unique sentinel `zq<i>q` (same length, padded), render through `MarkdownParser`, and
    assert each sentinel is absent from the HTML. Also take a copy where each pair is replaced by `ZK<i>K` placed
    *outside* any range, and assert those are present. Allow-listed inputs are skipped.
  - **`obsidianComments.differential.test.js`:** for every case, `commentRanges(input)` mapped to `[from,to]` equals
    `ranges`, except inputs in `allow`. Plus `expect(fixture.allow.length).toBeLessThanOrEqual(<count at commit>)`.
- [ ] **Step 2: Run them; the fixture test fails (file absent). Generate the file and run all three.** For each case
  where vitest disagrees: classify by the render oracle. If the editor is wrong, record it in `allow` with
  `"side": "editor"` and the reason, and report it. If the server is wrong, that is a Task 3 defect: stop and report.
  Target: `allow` is empty.
- [ ] **Step 3: Commit** `test(comments): checked-in differential fixture, render oracle, editor parity (#127 guard)`.

### Task 6: `ReaderTextSnapshotCli`, the corpus diff before deploy

**Files:**
- Create: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/ReaderTextSnapshotCli.java`
- Create: `bin/reader-text-diff.sh`
- Test: `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/ReaderTextSnapshotCliTest.java`

**Interfaces:**
- `ReaderTextSnapshotCli snapshot <pagesDir> <out.json>` writes `{ page: { "stripped": sha256(ReaderComments.strip(body)), "strippedLength": n, "comments": [[f,t]…], "tags": [normalised…] } }`
  over every `*.md` in a directory. The body comes from `FrontmatterParser.parse(text).body()`. `tags` is
  `InlineTagSyntax.normalisedTags(body)` in Phase A and `ReaderTags` in Phase B.
- `ReaderTextSnapshotCli diff <a.json> <b.json>` prints one line per changed page:
  `GROWS|SHRINKS|SAME-LENGTH <page> comments a→b tags +[..] -[..]`. It exits 1 when any page `GROWS` (the reader sees
  more) and 0 otherwise.
- `bin/reader-text-diff.sh <git-ref> <pagesDir>`:
  1. builds `<git-ref>` in a temporary `git worktree` with `mvn -q -o install -DskipTests -pl wikantik-extract-cli -am`;
  2. snapshots with both builds;
  3. diffs them;
  4. removes the worktree.

- [ ] **Step 1: Write `ReaderTextSnapshotCliTest`** over a temp dir of three pages: one plain, one with a comment, one
  with a tag. Assert the snapshot JSON's fields, and that `diff` of a snapshot against one whose page shows more text
  prints `GROWS` and exits 1.
- [ ] **Step 2: Run it and see it fail; implement; run it and see it pass.**
- [ ] **Step 3: Run `bin/reader-text-diff.sh v2.5.7 docs/wikantik-pages`**; paste the output into the task report.
- [ ] **Step 4: Commit** `feat(extract-cli): reader-text snapshot + diff, the corpus check before a mask change ships`.

**Phase A gate (controller):** `bin/run-tests.sh --parallel 4` all passed; final review of Tasks 1–6. Before Phase A
ships, the controller runs `bin/reader-text-diff.sh v2.5.7 <prod copy>`, after the user authorises the prod read.

---

## Phase B — tags

### Task 7: `InlineTagNodes.shown` and `ReaderTags`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/inlinetags/InlineTagNodes.java`
- Create: `wikantik-main/src/main/java/com/wikantik/parser/markdown/ReaderTags.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/inlinetags/InlineTagNodeRenderer.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/markdown/InlineTagParityTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/parser/markdown/ReaderTagsTest.java`

**Interfaces:**
- Produces:
  - `public static boolean InlineTagNodes.shown( InlineTag node )`: the renderer's `!insideLink` rule, moved unchanged.
    `InlineTagNodeRenderer` calls it.
  - `public final class ReaderTags` with:
    - `public static List< String > written( String body )`: first written form per normalised tag, in order.
    - `public static Set< String > normalised( String body )`.
  - Both strip comments with `ReaderComments.strip` first. Both return empty with no parse when the body has no `#`.
  - The parse uses `MarkdownDocument.structuralOptions()` (which registers `InlineTagExtension`) plus
    `InlineMathParser`, on `MarkdownBodySource.prepare( stripped ).text()`, under the parse budget.
  - On `Unreadable`: `LOG.warn` and an empty result. A tag missed from the index is the safe failure.

- [ ] **Step 1: Write the tests:**
  - **`InlineTagParityTest.theIndexersScanEveryFixtureRowToTheSameTags`** switches to `ReaderTags.normalised`.
  - **`ReaderTagsTest`:**
    - `tagsAreTheRenderedTagLinks`: over every `inline-tags.json` row and every `comments-differential.json` input,
      the normalised hrefs of `a.inline-tag` in the rendered HTML, in order and de-duplicated, equal `normalised(body)`.
    - `noHashNoParse`: zero `ReaderStructure.parses()` delta; count `ReaderTags`' own parses with a seam,
      `static long parses()`.
    - `crlfBodiesMatchLf`.
    - `linearInTags`: a ratio guard over `"#a `b` $c$ "` repeated n times.
- [ ] **Step 2: Run them; they fail to compile.**
- [ ] **Step 3: Implement; run `InlineTagParityTest` and `ReaderTagsTest`; all pass.**
- [ ] **Step 4: Commit** `feat(reader): ReaderTags reads inline tags off the render's nodes`.

### Task 8: The indexers take tags from `ReaderTags`

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/markdown/InlineTagSyntax.java`. `findableTags` becomes
  `findableTags( List< String > frontmatterTags, List< String > inlineTags )`, which merges and normalises.
- Modify: `wikantik-main/.../search/subsystem/lucene/DefaultLuceneIndexer.java:622`, `wikantik-main/.../pagegraph/spine/DefaultStructuralIndexService.java:318`
- Test: the existing Lucene and structural-index tag tests, plus `DefaultStructuralIndexServiceTest.inlineTagsAreTheRenderedOnes`

**Interfaces:**
- Consumes: `ReaderTags.written` (Task 7).
- Produces: `PageDescriptor.writtenTags()` / `findableTags()` are fed from `ReaderTags`. The signature
  `findableTags( List, String )` no longer exists.

- [ ] **Step 1: Failing test.** A body where the old scan and the render disagree is indexed with the render's tags.
  Take one from Task 7's oracle, or ``"`a #code` #real [x #inlink](u)"``, where `#real` is the only tag. Confirm the
  old scan disagrees first; if the old scan already agrees, use a `comments-differential.json` input it gets wrong.
- [ ] **Step 2: Implement; run the indexer tests:**
  `mvn -o -q test -pl wikantik-api,wikantik-main -Dtest='*Lucene*,*StructuralIndex*,InlineTag*' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Commit** `refactor(tags): the index counts the tags the page draws`.

### Task 9: `PageListEngine` reads indexed inline tags

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/retrieval/PageListEngine.java`, `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java:136`
- Test: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/retrieval/PageListEngineTest.java`

**Interfaces:**
- Produces: a new `PageListEngine` constructor parameter, `Function< String, Optional< List< String > > > indexedInlineTags`
  (page name → the descriptor's `writtenTags()`).
  - **Wiring:** `DefaultContextRetrievalService` wires it from the engine's `StructuralIndexService`
    (`resolveCanonicalIdFromSlug` then `getByCanonicalId`).
  - **Index absent or page not indexed:** `ReaderTags.written( body )`.
  - **Existing constructors** pass `name -> Optional.empty()`.

- [ ] **Step 1: Failing tests.**
  - `tagFilterUsesIndexedInlineTagsWithoutReadingTheBody`: a stub `PageManager` whose `getPureText` fails the test if
    called, and an index lookup returning `["inline"]`. A filter on `inline` matches.
  - `tagFilterFallsBackToReaderTags`: the lookup returns empty; the body `"x #inline"` matches.
- [ ] **Step 2: Implement; run `mvn -o -q test -pl wikantik-knowledge -Dtest='PageListEngine*,*ContextRetrieval*'`.**
- [ ] **Step 3: Commit** `perf(tags): tag filters read the structural index's inline tags, not page bodies`.

### Task 10: Delete the hand masks

**Files:**
- Delete:
  - `wikantik-api/src/main/java/com/wikantik/api/markdown/{ObsidianComments,MathMask,QuotedFences,GfmTableLines,GfmTableRows,InlineTagMasks,InlineTagTables}.java`
    (check that each exists first)
  - `InlineTagSyntax.scan`, `normalisedTags`, `writtenTags` and the `Tag` record, if unused
- Modify:
  - `wikantik-api/src/test/java/com/wikantik/api/markdown/ObsidianCommentsTest.java`: port every behaviour-level case
    that is not already a `comments.json` row into `ReaderCommentsTest` or the fixture, then delete it.
  - The Javadoc that names the deleted classes (`grep -rn` for each class name).
  - `build-support/pmd-complexity-baseline.properties`: remove entries for deleted classes.
  - `CHANGELOG.md` `[Unreleased]`: tags now match the render exactly; **a reindex is needed after deploy**.
- Test: the full wikantik-api, wikantik-main and wikantik-knowledge unit suites.

- [ ] **Step 1: Run `grep -rn "ObsidianComments\|MathMask\|QuotedFences\|GfmTable\|InlineTagMasks\|InlineTagTables\|InlineTagSyntax\.\(scan\|normalisedTags\|writtenTags\)" --include=*.java .`**
  The only hits are the files to delete, their tests, and Javadoc.
- [ ] **Step 2: Port the `ObsidianCommentsTest` cases; run them against `ReaderComments`.** Each passes, or it is a
  behaviour change: report it, with the render's verdict.
- [ ] **Step 3: Delete; run `mvn -o -q install -DskipTests -pl wikantik-api,wikantik-main,wikantik-knowledge,wikantik-rest,wikantik-extract-cli -am`**
  (it compiles), then `mvn -o -q test -pl wikantik-api`.
- [ ] **Step 4: Switch `ReaderTextSnapshotCli`'s tags to `ReaderTags` by reflection, with an
  `InlineTagSyntax.normalisedTags` fallback.** `bin/reader-text-diff.sh` compiles HEAD's source against the old ref's
  jar, so a direct `ReaderTags` import fails to compile against a Phase A build. Look the class up the way comments are
  looked up; run `bin/reader-text-diff.sh <Phase A tag or HEAD~N> docs/wikantik-pages`;** paste the output.
- [ ] **Step 5: Commit** `refactor(api): delete the hand-written code/math/comment/tag masks (#127)`.

**Phase B gate (controller):** `bin/run-tests.sh --parallel 4`; final review; prod `reader-text-diff` before deploy;
reindex after deploy.
