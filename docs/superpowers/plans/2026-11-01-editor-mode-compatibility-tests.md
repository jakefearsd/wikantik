# Editor-Mode Compatibility Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make source/live editor-mode compatibility a tested contract: a corpus sweep, every editor browser
test in both modes, live-mode keyboard tests, a real-browser whole-page round trip, and a three-renderer
parity fixture (live preview vs editor preview pane vs server reader).

**Architecture:** Breadth in vitest (the 1,197-page repo corpus; a shared parity fixture), depth in Selenide
(real layout, keyboard, scrolling, save). Selenide reads the editor's *document* text, never its on-screen
text, so assertions hold in both modes.

**Tech Stack:** vitest 4 + happy-dom, CodeMirror 6; Selenide ITs (`wikantik-it-tests/wikantik-selenide-tests`);
JUnit 5 + flexmark (wikantik-main) for the server side of the parity fixture.

**Spec:** `docs/superpowers/specs/2026-11-01-live-preview-completion-design.md` (the invariant in §1 and the
construct list in §3–§5). Motivation recorded in the conversation of 2026-11-01: editing modes will be a bug
hotspot; tests must cover features × modes, and grow to a third (ProseMirror) mode later.

## Global Constraints

- Tests only: no production behaviour changes. If a test exposes a real defect, stop and report it with the
  failing test (the controller rules on the fix); do not weaken the assertion to pass.
- No new npm or Maven dependencies. No production-only test hooks or globals added to the app.
- Never swallow exceptions (Java: at least `LOG.warn`; JS: `console.warn` with context).
- Selenide: positive waits before any negative assertion (`shouldNot(exist)` alone is vacuous); IT URLs from
  `Page.baseUrl()` (never `window.__WIKANTIK_BASE__`); use `RestSeedHelper` for seeding/cleanup and
  `awaitAdminReady` after login, as existing ITs do.
- An IT that sets the IT user's server editor preference restores it (`PUT /api/auth/profile {"editorMode":null}`)
  in `@AfterEach`, failing loudly if that fails.
- Mode names: `source`, `live` (a `EditorMode` enum in the IT module is fine; keep it extensible to a third mode).
- Frontend tests run with `cd wikantik-frontend && npx vitest run <files>`; the corpus test stays in the default
  suite and must finish in ≤ 30 s locally — if the full-`EditorView` part pushes it over, shrink that part's
  deterministic sample until it fits (the state-level checks still cover every page).
- Stage files by name; commits 1–3 lines with the session trailers.

## Review Focus

1. A test that passes in live mode only because it never actually entered live mode (no positive check for
   `.cm-editor.cm-live-preview`) — every parameterized/live IT must assert the mode it runs in.
2. Document-text reads that silently fall back to `innerText` — the accessor must fail loudly if it cannot reach
   the CodeMirror state.
3. Corpus pages whose bodies contain frontmatter-like `---` blocks or legacy JSPWiki syntax — the sweep must
   process the body exactly as the editor would (frontmatter stripped; legacy-syntax pages skipped, as the
   editor forces source for them) and report, not crash.
4. Parity fixture entries where the three renderers legitimately differ (e.g. the editor deliberately shows
   source for nested tables) — such entries must say so explicitly (`liveExpect` differs, with a reason),
   never be silently skipped.
5. Scrolling in the round-trip IT that doesn't actually reach the end of the document (viewport virtualisation)
   — assert the last line was rendered.

---

### Task 1: Live-preview corpus sweep (vitest)

**Files:** Create `wikantik-frontend/src/utils/livePreview/corpus.test.js`.

Reads every `docs/wikantik-pages/*.md`, strips frontmatter with the editor's own util
(`src/utils/frontmatterUtils.js` `stripFrontmatter`), skips pages the editor would treat as legacy syntax (use
the same detection the editor uses — find how `markupSyntax` is derived for `PageEditor`) and counts them. For
each remaining page, with a fully parsed `EditorState` (`markdown(editorMarkdownConfig)`, see
`src/test/fullParse`):
- `livePreviewSpecs(state, new Set(), { from: 0, to: doc.length, context })` and `blockCandidates` throw nothing
  and log no `console.warn`/`console.error` (spy; fail listing page names);
- replace specs (`hide`/`widget`) never cross a line break and never overlap each other; block candidates
  ascend and don't overlap;
- `updateBlockCandidates` after 3 deterministic edits (seeded by page name: insert a char mid-doc, delete a line,
  insert a blank line at the top) equals a full `blockCandidates` rescan;
- with the full `livePreview()` extension on a real `EditorView`, toggling live on/off/on dispatches no
  document change (updateListener) — for a deterministic sample of 100 pages (all pages if total time allows).
- per-page timing: record the slowest 5 pages for `livePreviewSpecs`; fail if any page exceeds 250 ms.
Output (console.info) a one-line summary: pages scanned, skipped (legacy), slowest pages.
Commit: `test(live-preview): corpus sweep over the repo pages`.

### Task 2: Document-text accessor + every editor IT in both modes

**Files:** Modify `pages/spa/EditWikiPage.java` (and `ViewWikiPage.editPage` if needed); create
`its/EditorMode.java` (enum `SOURCE("source")`, `LIVE("live")`); modify `BlockRefsIT`, `EditIT`,
`EditorInsertHelpersIT`, `EditorWorkspaceIT`, `PasteAsMarkdownIT`, `StructuredFrontmatterEditorIT` (each only
where it interacts with editor content — judge per test, record skips with reasons in the report).

- `EditWikiPage.documentText()` returns the CodeMirror document (`state.doc.toString()`), reached through the
  editor DOM without a production hook (probe what @codemirror/view 6.43 exposes on `.cm-content`/`.cm-editor`
  — e.g. the `cmView`/`cmTile` back-reference — and encapsulate it with a clear failure message). Replace every
  `innerText`-based content read in the page object and the ITs with it.
- `EditWikiPage.openInMode(name, EditorMode)` and `assertMode(EditorMode)` (positive check of
  `.cm-editor.cm-live-preview` presence/absence after the editor is visible).
- Parameterize each content-touching test with `@ParameterizedTest @EnumSource(EditorMode.class)` (or a class-level
  equivalent); every test asserts the mode first. Where a step is mode-specific by nature (e.g. reading rendered
  table cells), branch explicitly.
- Run the touched ITs (single-module failsafe run as the Task 10 implementer did, or `bin/run-tests.sh --it`
  after `mvn clean install -DskipTests -T 1C`) — all green in both modes.
Commit: `test(it): editor ITs run in source and live mode; reads go through the document`.

### Task 3: Live-mode keyboard IT (real browser)

**Files:** Create `its/LivePreviewKeyboardIT.java` (uses Task 2's accessor and `openInMode`).

Six tests, each asserting `documentText()` and the caret's document offset (read via the same accessor's state:
`state.selection.main.head`) — not screen text:
1. ArrowRight/ArrowLeft through a line with `**bold**`, a `[[Page|alias]]` and a `[{TableOfContents}]` chip: each
   press moves one document position where markup is revealed and the doc is unchanged.
2. End/Home on a line with hidden markup land on the line's document end/start.
3. Backspace immediately after a chip (caret placed via the accessor at the chip's end) deletes one character of
   source (the `]`), revealing the source; doc changed by exactly that char.
4. Ticking a task checkbox then Mod+Z restores the exact original text.
5. Mod+A then typing `x` replaces the whole document with `x` (and Mod+Z restores it).
6. ArrowDown from the line above a table into it reveals the table source and lands on the table's first line.
Commit: `test(it): live-mode keyboard behaviour around hidden markup and widgets`.

### Task 4: Whole-page round trip in a real browser

**Files:** Create `its/LivePreviewRoundTripIT.java`; reuse `wikantik-frontend/src/utils/__fixtures__/live-preview-kitchen-sink.md`
as the seeded body (load it from the repo path or copy it into the IT module's test resources via the existing
IT shared-resources mechanism — see how other ITs load fixtures).

Seed the page via REST, record its version. Open in live mode; scroll the editor scroller to the bottom in
viewport-sized steps until the last document line's DOM is rendered (assert it); collect the set of
`.cm-lp-*` widget classes seen during the scroll and assert it includes table, chip, footnote-ref, math, embed (or
the embed's missing/restricted state), fence and image; toggle to source and back (Mod+E twice, asserting each
mode); `documentText()` equals the seeded body byte-for-byte; press Save without editing; assert via REST that
the page text is unchanged and no new version was created (or, if the editor always saves, that the stored text is
byte-identical — find out which and assert the stronger true statement).
Commit: `test(it): live preview whole-page round trip in a real browser`.

### Task 5: Three-renderer parity fixture

**Files:** Create `wikantik-frontend/src/utils/__fixtures__/live-preview-parity.json`,
`wikantik-frontend/src/utils/livePreview/parity.test.js`,
`wikantik-frontend/src/components/editor/EditorPreviewMarkdown.parity.test.jsx` (or next to wherever the editor
preview pipeline is testable), and `wikantik-main/src/test/java/com/wikantik/markdown/LivePreviewParityTest.java`
(follow `BlockIdParityTest`'s way of locating the frontend fixture).

Fixture entries: `{ "name", "markdown", "kind", "notes"?, "liveExpect"?, "previewExpect"?, "readerExpect"? }`,
where `kind` is one of: `table`, `table-nested`, `footnote-ref`, `footnote-def`, `ref-link`, `ref-link-undefined`,
`shortcut-link`, `autolink`, `bare-url`, `escaped`, `escaped-footnote`, `setext-h1`, `setext-h2`, `html-block`,
`plugin-line`, `inline-math`, `display-math`, `callout`, `task`, `wikilink`. Cover every construct the live
preview handles (spec §3–§5 plus the pre-existing ones). Per-renderer expectation defaults to `kind`; a renderer
that legitimately differs states its own expectation with a `notes` reason (Review Focus 4).
- Live side: map `livePreviewSpecs`/`blockSpecs` (caret elsewhere) to the observed kind (e.g. a `table` block
  widget → `table`; a `cm-lp-link` mark with hidden brackets → `ref-link`; no specs → `literal`).
- Editor preview side: render through the same remark pipeline `EditorPreviewMarkdown` uses and classify the
  DOM (`table`, `sup` footnote ref / `section.footnotes`, `a[href]`, literal text, `h1`/`h2`, …).
- Reader side (Java): render through the production markdown renderer the reader uses (the path that produces
  `page.contentHtml` — find it; e.g. `MarkdownRenderer` via `TestEngine`), classify the HTML with the same kinds.
- Any disagreement that is not an explicit, reasoned per-renderer expectation fails. If the first run surfaces a
  real disagreement, STOP and report it (with the entry) — do not encode it as expected without a controller ruling.
Commit: `test(parity): one fixture checks live preview, editor preview and reader agree`.
