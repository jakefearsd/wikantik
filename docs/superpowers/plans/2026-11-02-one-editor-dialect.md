# One Editor Dialect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The editor's live preview reads every wiki/Obsidian construct from ONE incremental syntax tree (CodeMirror's Lezer
markdown parser, extended with the wiki's constructs), deleting the per-construct line scanners, hand code/math masks and
hand incremental-invalidation code; and fix #126 (a `%%` comment in a table row is shown to readers) on both sides.

**Architecture:** Each construct becomes an `@lezer/markdown` extension (block or inline parser + node names) in
`src/utils/dialect/`. `editorMarkdownConfig` composes them; a standalone `dialectParser` (same config, for pure-text
callers such as the preview pane's comment strip) parses a string synchronously. `livePreview/ranges.js` maps node types
to decorations instead of regex-scanning lines. `%%` comments stay an overlay (they may span blocks): markers are found in
the text and the tree decides which are code or math. The remark preview pane and the server reader are unchanged except
where a task says otherwise.

**Tech Stack:** @lezer/markdown 1.7.2 (direct dependency, already in the lockfile transitively), @codemirror/lang-markdown,
vitest 4, Java 25 / JUnit 5 (server mask fix only).

**Spec:** this plan's Background section and the 2026-11-02 evaluation + spike recorded in memory
`project_one_dialect_lezer_spike`; issue #126. Spike code: `wikantik-frontend/spike/` (untracked; Task 1 lifts it).

## Background (the decisions)

- The dialect is implemented by three parsers (flexmark reader, remark preview, Lezer + regex scanners in live preview) and
  by at least six hand code/math masks (JS `obsidianComments.js` codeMask; Java `CodeMask`, `CodeRegions` (math),
  `EnforcedAclDirectives.CodeRegions`, `ObsidianComments.mask`, importer `CodeSegments`). Disagreements between them are
  most of the recent frontend fix commits.
- Spike result: comment ranges derived from the Lezer tree matched the shared fixture 30/30 and the hand mask on 8,374 of
  8,379 corpus documents; every disagreement was a hand-mask defect (inline math across table rows; a fence kept open past
  its list item's end). Per keystroke on a 162K page: reparse 0.18 ms + tree scan 0.13 ms vs 0.75 ms old full scan.
- Ruling (server scope): the server keeps its hand masks in this plan — they live in parser-free `wikantik-api`, feed the
  #104 access-rule reader (IN_COMMENT locks), and moving them onto the flexmark AST is its own program. Task 2 fixes the two
  mask defects there, test-first, through the shared fixture. Cost if wrong: the server consolidation is deferred, not lost.
- Ruling (preview pane): the remark preview pane keeps its remark plugins (server-rendering it is a separate decision); it
  shares only the comment rule, which becomes tree-based.

## Global Constraints

- TDD: a failing test before each fix (CLAUDE.md). Shared fixtures (`src/utils/__fixtures__/*.json`) are the arbiters;
  a new behaviour case goes into the shared fixture so both sides run it.
- Never weaken a behaviour assertion to get green. A test asserting tree SHAPE (e.g. "display math is a Paragraph") may be
  updated to the new shape; say so in the commit.
- Every task ends with: `cd wikantik-frontend && npx vitest run` all green, `npx eslint src` clean, and
  `npx vitest run --coverage` thresholds met (lines 91 / statements 89 / functions 87 / branches 80).
- The corpus, parity, property and round-trip suites (`livePreview/corpus.test.js`, `parity.test.js`,
  `plugin.property.test.js`, `roundTrip.test.js`) must stay green unchanged unless a task names the change.
- Partial trees: in a live view the tree may not cover the document. Code reading the tree must treat positions past
  `tree.length` as plain text and rely on the plugin's existing `treeChanged` rebuild (`livePreview/plugin.js:50`).
- Delete what a task replaces in the same task (code AND its now-redundant tests). Report lines deleted vs added.
- Stage files by name; commit messages 1-3 lines with the session trailer. Work on main.
- npm: `npm install --save-exact=false @lezer/markdown@1.7.2 --ignore-scripts` (supply-chain posture); no other new deps.

## Review Focus

1. A `%%` near a table, list-nested fence or math: comments open/close where CommonMark structure says (fixture cases).
2. Typing in a large page: no full-document rescans per keystroke (existing previewCost / incremental tests).
3. Partially parsed document (fresh view on a long page): no exception, no permanently wrong decoration once parsed.
4. Constructs inside code, math, links, plugin `[{…}]` and comments are not decorated (parity fixture `kind`s).
5. Source text never changes from decoration alone (round-trip + "toggling live dispatches no change" corpus test).

---

### Task 1: dialect module + math nodes (and a narrower base)

**Files:** Create `src/utils/dialect/math.js`, `src/utils/dialect/index.js` (exports `dialectExtensions`,
`dialectParser`, `opaqueAt(tree, pos)`), tests beside them; modify `src/utils/editorMarkdown.js`,
`src/utils/livePreview/ranges.js`, `package.json`/lock.

**Interfaces — Produces:** `dialectExtensions: MarkdownConfig[]`; `dialectParser: MarkdownParser` (commonmark + GFM +
dialect + fold config); `opaqueAt(tree, pos): boolean` (inside FencedCode, CodeBlock, InlineCode, DisplayMath, InlineMath,
InlineDisplayMath); node names `DisplayMath`, `InlineMath`, `InlineDisplayMath`.

- [ ] Lift `spike/lezerDialect.js` math parsers into `dialect/math.js` with unit tests (each node from the
  inlineMath.cases.js cases + display block open/close/unterminated/in blockquote).
- [ ] Narrow the base: GFM (Table, TaskList, Strikethrough, Autolink) on commonmark, dropping Subscript/Superscript/Emoji
  that the server never renders (removes the `^x^` vs `^id` conflict). Update the editorMarkdown.js doc comment.
- [ ] ranges.js: display math = `DisplayMath` node (delete `BLOCK_MATH`, `isBlockMathText` and the `$$`-Paragraph
  checks); inline math = `InlineMath`/`InlineDisplayMath` nodes (delete the `matchInlineMath` line scan). The 21 tests the
  spike broke must pass by reading nodes; tree-shape assertions updated, behaviour unchanged.
- [ ] Gate (Global Constraints), commit `refactor(live-preview): math is a syntax-tree node; GFM base without sub/sup/emoji`.

### Task 2: comments from the tree, and #126 on both sides

**Files:** Modify `src/utils/obsidianComments.js`, `src/utils/livePreview/ranges.js`, `livePreview/index.js`,
`components/editor/EditorPreviewMarkdown.jsx` (only if its call changes); delete
`livePreview/ranges.comments.incremental.test.js` if all it tests is deleted machinery.

**Also (#126, server + shared fixture):**
**Files:** Modify `wikantik-frontend/src/utils/__fixtures__/comments.json`;
`wikantik-api/src/main/java/com/wikantik/api/markdown/ObsidianComments.java` (inline-math mask);
`wikantik-api/src/main/java/com/wikantik/api/parser/CodeMask.java` (list-item fence end);
tests `wikantik-api/src/test/java/.../ObsidianCommentsTest.java` / `CodeMaskTest.java` (or the existing parity runner).

- [ ] Add fixture cases (names verbatim):
  - `"a $ in one table row and a $ in the next are not one math span"`: input
    `"| Item | Price |\n|---|---|\n| widget | $5 |\n| %%secret note%% gadget | a$b |\n"`, output
    `"| Item | Price |\n|---|---|\n| widget | $5 |\n|  gadget | a$b |\n"`.
  - `"a fence in a list item ends with the item"`: input
    `"3. step:\n\n    ```bash\n    run\n  %%gone%% text\n    ```\n"`, output `"3. step:\n\n    ```bash\n    run\n   text\n    ```\n"`.
- [ ] FIRST (before any code change): run Java fixture runner (`CommentsParityTest`) and JS `obsidianComments.test.js`: both FAIL on the two cases.
- [ ] Java: inline math (`$…$`, inline `$$…$$`) in the mask never spans a newline when either line is a GFM table row
  (starts, after optional indent/quote markers, with `|`, or is part of the table block). CodeMask: a fence opened inside a
  list item (fence indent ≥ list content indent) ends at the first non-blank line indented less than the content indent.
  Keep `CodeMask` callers' tests green (`WikiLinkSyntax`, importer `CodeSegments`).
- [ ] `mvn test -pl wikantik-api -q` and `mvn test -pl wikantik-main -Dtest='CommentsParityTest,*AccessRule*,ReaderText*' -q` green.
- [ ] Commit the Java fix separately: `fix(comments): #126 a table row's $ never pairs with the next row's; a list fence ends with its item`.

**Interfaces — Produces:** `commentRanges(text)` (parses with `dialectParser`) and `commentRangesInTree(text, tree)`;
`stripComments(text)` unchanged signature.

- [ ] `commentRangesInTree` = spike `commentRangesFromTree` using `opaqueAt`; `commentRanges(text)` parses then delegates.
- [ ] Delete codeMask, maskInlineCode, quoted fences, display/inline math masks and helpers from obsidianComments.js.
- [ ] ranges.js: comments per state = `commentRangesInTree(doc, syntaxTree(state))`, memoised per (doc, tree); delete
  `commentCache`, `editKeepsComments`, `lineEditKeepsKind`, `paragraphHasPercent`, `updateCommentRanges`,
  `commentRangesField` (and its registration in index.js) unless a measured test shows a need.
- [ ] comments.json passes in full on the JS side (incl. the two #126 cases); previewCost and corpus tests green.
- [ ] Commit `refactor(comments): the editor reads code and math from its syntax tree (#126)`.

### Task 3: `==highlight==` as a node

**Files:** Create `src/utils/dialect/highlight.js` (+test); modify ranges.js; delete `src/utils/highlightSyntax.js` and its
test if nothing else imports it (only ranges.js does today).

- [ ] Inline delimiter parser modelled on @lezer/markdown's Strikethrough, node `Highlight` + `HighlightMark`: a run of
  EXACTLY two `=`, CommonMark emphasis flanking, `\=` escapes; crosses soft breaks within a paragraph. Cases from
  `__fixtures__/highlight.json` drive the unit test.
- [ ] ranges.js highlightSpecs reads `Highlight` nodes (code/math/link exclusion now comes from the parser); a highlight
  inside a comment range still is not drawn as a highlight (comment overlay wins), per the parity fixture.
- [ ] Commit `refactor(live-preview): ==highlight== is a syntax-tree node`.

### Task 4: inline `#tags` as a node

**Files:** Create `src/utils/dialect/inlineTags.js` (+test); modify ranges.js; keep `src/utils/inlineTags.js` normalise
and whatever remarkInlineTags/tagComplete/PageView/SearchResultsPage still need — delete only scanning code that ranges.js
alone used.

- [ ] Inline parser `InlineTag` at `#` using the same character rule as `inlineTags.js` (import it, do not copy), not
  inside `[{…}]` plugin text (node or check), cases from `__fixtures__/inline-tags.json`.
- [ ] ranges.js tag chips from `InlineTag` nodes. Commit `refactor(live-preview): inline #tags are syntax-tree nodes`.

### Task 5: wikilinks as a node

**Files:** Create `src/utils/dialect/wikiLinks.js` (+test); modify ranges.js, `utils/linkInteraction.js` (its `inCode`
becomes `opaqueAt`), `livePreview/widgets.js` if it rescans.

- [ ] Inline parser `WikiLink` (+ `WikiLinkMark`, `WikiLinkTarget`, `WikiLinkAlias`; embed `!` included) before `Link`,
  parsing the inner text with `parseWikiLink` from `wikiLinkSyntax.js` (reuse, do not copy). Cases from
  `__fixtures__/wikilinks.json`.
- [ ] ranges.js wikilink chips/embeds from nodes; delete its `findWikiLinks` line scans. Commit
  `refactor(live-preview): wikilinks are syntax-tree nodes`.

### Task 6: `^id` block markers as nodes

**Files:** Create `src/utils/dialect/blockIds.js` (+test); modify ranges.js; shrink `src/utils/blockIds.js` only where its
logic moved (it also serves remarkBlockIds, blockLinks, completion — keep those exports).

- [ ] Inline parser `BlockIdMark` for a trailing `^id` (the `BLOCK_ID_RE` rule, last thing in the block's inline content)
  and a block parser for the standalone `^id` line (`STANDALONE_RE`). The access-rule beside-marker rules
  (`RULE_TEXT`, `MARKER_WITH_SPACE`, keptBeforeRules) stay where they are unless the node makes one redundant.
- [ ] ranges.js marker chips from nodes; `__fixtures__/block-ids.json` and BlockIdParityTest's JS mirror green.
- [ ] Commit `refactor(live-preview): ^id block markers are syntax-tree nodes`.

### Task 7: gate wiring

**Files:** `bin/run-tests.sh`, `wikantik-frontend/pom.xml`.

- [ ] run-tests.sh runs `npx vitest run --coverage` in wikantik-frontend in the static-checks phase (FAIL on red).
- [ ] The WAR build's `npm ci` runs only when `package-lock.json` is newer than `node_modules/.package-lock.json` (or
  node_modules is absent); otherwise it skips. Commit `build: run vitest in the gate; reuse node_modules across WAR builds`.

### Task 8: final

- [ ] Delete `wikantik-frontend/spike/`. Full gate `bin/run-tests.sh --parallel 4` via `bin/agent-build.sh`; final
  cross-cutting review; update docs that describe the old scanners (code comments, `docs/` design pages mentioning
  `commentRangesField`/line scanners).
