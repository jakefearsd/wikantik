# Live Preview Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the editor's live-preview mode (tables, `[{…}]` chips, less common Markdown) and make the
editor mode a server-stored per-user preference defaulting to live.

**Architecture:** Live preview is a CodeMirror 6 decoration layer over the unchanged Markdown text
(`wikantik-frontend/src/utils/livePreview/`): `ranges.js` turns the Lezer tree + line regexes into specs,
`widgets.js` draws them, `plugin.js` wires inline specs (ViewPlugin) and block specs (StateField with
incremental candidates). New constructs are new spec kinds/widgets on that pipeline. The preference is a
user-profile attribute surfaced through `/api/auth/profile`.

**Tech Stack:** React 19, CodeMirror 6 (`@codemirror/lang-markdown`, Lezer), unified/remark, KaTeX,
vitest 4 + happy-dom; Java 25, `AuthResource` (wikantik-rest), JUnit 5; Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-01-live-preview-completion-design.md` (read §8 rulings — they
override anything ambiguous in §2–§7).

## Global Constraints

- The document text is never changed except by an explicit user edit; toggling modes, mounting, caret
  moves and widget rendering must dispatch no document change.
- No new npm dependency. No `innerHTML` with page-authored text; only existing KaTeX output and
  server-sanitised embed HTML may use it.
- Never swallow errors: every catch logs `console.warn('[live-preview] …', err?.message || err)` (frontend)
  or `LOG.warn(…)` (Java).
- Preference attribute key `wikantik.editor.mode`; values `"source"` | `"live"`; JSON field `editorMode`;
  default when unset: `live`. localStorage key stays `wikantik.editor.mode`.
- 400 message for a bad value: `editorMode must be 'source' or 'live'`.
- Every new decoration hides/replaces only on inactive lines (`activeLinesOf`), and tree constructs cut
  by a claimed wikilink/math/chip range are dropped whole (existing `cuts` rule).
- Frontend line coverage must not drop below the current ~92%; every task adds tests for its own code.
- CSS for new classes goes in `src/utils/editorTheme.js` next to the existing `cm-lp-*` rules, using
  existing CSS variables only (both themes).
- Frontend test command: `cd wikantik-frontend && npx vitest run <files>`; full: `npx vitest run`.
- Commits: stage files by name (never `git add -A`), 1–3 line messages, on `main`.

## Review Focus

1. A programmatic edit (find/replace, undo, multi-cursor) inside an **inactive** table or fence must
   update its widget — today `sameWidget` in `plugin.js` ignores `source`/`language`, so a stale drawing
   can survive. → Task 8 test `programmatic edit inside an inactive table/fence redraws it`.
2. Lezer parses `[^1]` as a shortcut **Link** and `[^1]: text` as a **LinkReference**; footnotes must
   render as footnote chips, never as reference links. → Task 6 tests.
3. Hostile table-cell content (`<img src=x onerror=…>`, `<script>`, `[x](javascript:…)`) renders as
   inert text. → Task 7 test.
4. GFM lazy continuation: a non-blank line right after a table's last row belongs to the table; the
   widget must cover exactly Lezer's `Table` node lines, including a table at end-of-document with no
   trailing newline. → Task 8 tests.
5. A server preference arriving **after** the user already toggled during this mount must not undo the
   toggle. → Task 2 test.

---

### Task 1: `editorMode` on `/api/auth/profile`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java` (`handleGetProfile`,
  `handleUpdateProfile`, `profileToMap`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java`

**Interfaces:**
- Produces: `GET /api/auth/profile` → JSON includes `"editorMode": "source"|"live"|null`.
  `PUT /api/auth/profile` body may carry `"editorMode"`: `"source"`/`"live"` set the attribute
  `wikantik.editor.mode`; JSON `null` removes it; absent → unchanged; any other value (unknown string,
  number, boolean, object) → 400 `editorMode must be 'source' or 'live'` with nothing saved.

- [ ] **Step 1: Failing tests** in `AuthResourceTest` (follow its existing request/response helpers):
  - `getProfileReportsNullEditorModeWhenUnset` → `editorMode` key present with JSON null.
  - `putEditorModeLivePersistsAndGetReturnsIt` → PUT `{"editorMode":"live"}` 200, response and a
    following GET both show `"live"`; `profile.getAttributes().get("wikantik.editor.mode")` = `"live"`.
  - `putEditorModeNullClearsIt` → after setting, PUT `{"editorMode":null}` → GET shows null, attribute absent.
  - `putEditorModeInvalidIs400AndSavesNothing` → values `"rich"`, `5`, `true` each → 400 with the exact
    message; attribute unchanged; `db.save` not called (or the stored profile is unchanged).
  - `preferenceOnlyPutKeepsPasswordHashAndFullName` → password hash, fullName, email, bio identical
    before/after a PUT carrying only `editorMode`; same test for a profile whose password is null (SSO).
  - `profileJsonNeverExposesOtherAttributes` → with `sso.subject` set as an attribute, the GET JSON
    contains no `sso.subject` and no `attributes` key.
- [ ] **Step 2:** Run `mvn test -pl wikantik-rest -Dtest=AuthResourceTest -q` → the new tests FAIL.
- [ ] **Step 3: Implement.** Validate `editorMode` before any other field is applied (so a 400 saves
  nothing). Distinguish JSON null from absent with `body.has("editorMode") && body.get("editorMode").isJsonNull()`.
  Add `editorMode` to the map returned by `profileToMap` only (not to `UserProfileMapping.coreFields`,
  which admin surfaces share). Constant `static final String EDITOR_MODE_ATTRIBUTE = "wikantik.editor.mode"`.
- [ ] **Step 4:** Re-run the test class → PASS. Run `mvn test -pl wikantik-rest -Dtest='AuthResource*' -q` → PASS.
- [ ] **Step 5: Commit** `feat(editor): per-user editorMode preference on /api/auth/profile`.

### Task 2: `useEditorMode` resolves server → local → `live`

**Files:**
- Modify: `wikantik-frontend/src/hooks/useEditorMode.js`, `src/components/PageEditor.jsx` (call site only)
- Test: `src/hooks/useEditorMode.test.js`; pin source in every existing test that assumes it:
  `src/components/PageEditor*.test.jsx` (at least `PageEditor.interleaving.realCodeMirror.test.jsx`,
  `PageEditor.livePreview.realCodeMirror.test.jsx` — grep all editor tests for mode assumptions)
- Modify (IT pinning): `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/spa/EditWikiPage.java`
  and any IT that reaches the editor without `EditWikiPage.open` (grep `page-editor` under `its/`)

**Interfaces:**
- Consumes: Task 1's `editorMode` field; `api.getProfile()`, `api.updateProfile(data)` (existing,
  `src/api/client.js`); `useAuth()` from `src/hooks/useAuth.jsx` (`user.authenticated`).
- Produces: `useEditorMode({ authenticated: boolean, persist?: boolean = true }) → [mode: 'source'|'live', toggle: () => void]`.
  `persist=false` (legacy JSPWiki-syntax pages) means: never PUT, never write localStorage, never migrate.
  `PageEditor` calls `useEditorMode({ authenticated: user?.authenticated === true, persist: !isLegacyWiki })`
  — note `isLegacyWiki` must be computed before the hook call.

- [ ] **Step 1: Failing tests** in `useEditorMode.test.js` (mock `../api/client`):
  - `defaults to live when nothing is stored and anonymous` → `'live'`; `getProfile` not called; no localStorage write.
  - `uses the stored local choice before the server answers` → localStorage `source` → first render `'source'`.
  - `server value wins and is mirrored locally` → local `live`, server `source` → ends `'source'`, localStorage `source`.
  - `server null + explicit local choice migrates it once` → local `source`, server null → `updateProfile({editorMode:'source'})` called exactly once.
  - `server null and nothing local writes nothing` → mode `live`, no PUT, no localStorage write.
  - `toggle while authenticated PUTs the new mode` → `updateProfile({editorMode:'source'})` after toggling from live.
  - `toggle while anonymous does not PUT`.
  - `failed PUT warns and keeps the toggled mode` → rejected promise → `console.warn` called, mode stays toggled.
  - `profile fetch failure falls back with a warning` → rejected `getProfile` → local/default mode, `console.warn`.
  - `a server value arriving after a toggle does not undo it` (Review Focus 5) → toggle before the
    `getProfile` promise resolves with the opposite value → mode stays as toggled.
  - `persist=false never writes or PUTs` → toggle → no localStorage write, no `updateProfile`.
- [ ] **Step 2:** `npx vitest run src/hooks/useEditorMode.test.js` → FAIL.
- [ ] **Step 3: Implement.** One `getProfile` per mount, only when `authenticated` is true (re-run the
  effect when `authenticated` flips false→true; ignore a resolved response after unmount). Keep the
  StrictMode-safe "persist from an effect" pattern already in the file.
- [ ] **Step 4:** Update `PageEditor.jsx` call site; pin `source` in existing editor tests that assume it
  (set `localStorage.setItem('wikantik.editor.mode','source')` in their setup); pin the ITs
  (`EditWikiPage.open` sets the key on the wiki origin before the editor mounts — e.g. open the base URL,
  `Selenide.executeJavaScript("localStorage.setItem('wikantik.editor.mode','source')")`, then open
  `/edit/…`). The IT user `testbot` has no server preference, so local wins.
- [ ] **Step 5:** `npx vitest run src/hooks src/components` → PASS. Compile ITs:
  `mvn -q -pl wikantik-it-tests/wikantik-selenide-tests -am compile -DskipTests` → success.
- [ ] **Step 6: Commit** `feat(editor): editor mode follows the user's server preference, defaulting to live`.

### Task 3: Preferences page — "Default editing mode"

**Files:**
- Modify: `wikantik-frontend/src/components/UserPreferencesPage.jsx`
- Test: `src/components/UserPreferencesPage.test.jsx`

**Interfaces:**
- Consumes: `editorMode` in the `getProfile()` response; `updateProfile({ editorMode })`.
- Produces: an "Editor" section with `<select aria-label="Default editing mode">` options
  `live` → "Live preview", `source` → "Source". It saves on change (its own `updateProfile({editorMode})`
  call, independent of the profile form's Save), updates localStorage `wikantik.editor.mode` on success,
  shows the existing success/error message style.

- [ ] **Step 1: Failing tests:** select shows "Live preview" when `editorMode` is null; shows "Source"
  when `"source"`; changing it calls `updateProfile({ editorMode: 'source' })` only (no other fields) and
  writes localStorage; a rejected save shows the page's error message and logs a warning.
- [ ] **Step 2:** run the test file → FAIL. **Step 3:** implement, following the page's existing section
  markup/styles. **Step 4:** run → PASS.
- [ ] **Step 5: Commit** `feat(editor): choose the default editing mode on the preferences page`.

### Task 4: `[{…}]` chips

**Files:**
- Create: `wikantik-frontend/src/utils/livePreview/chips.js`, `chips.test.js`
- Modify: `src/utils/livePreview/ranges.js` (the `PLUGIN` loop in `livePreviewSpecs`),
  `widgets.js` (add `ChipWidget`, `widgetFor` case `'chip'`), `src/utils/editorTheme.js`
- Test: `ranges.test.js`, `widgets.test.js`

**Interfaces:**
- Produces: `chipFor(source: string) → { text: string, cls: string }` (source = full match incl. `[{`,
  `}]` and optional `()`); widget spec `{ type: 'chip', text, cls, title }`;
  `class ChipWidget(text, cls, title)` (`eq` on all three; `ignoreEvent() → false` so a mousedown places
  the caret and reveals the source; `title` attribute = full source). Reused by Task 6.

- [ ] **Step 1: Failing tests** — `chips.test.js` table (exact values from spec §4 + ruling 5):
  ```js
  ['[{ALLOW view Admin,Editors}]', 'view: Admin, Editors', 'cm-lp-chip cm-lp-chip-acl'],
  ['[{allow edit  Bob ,  Ann}]',   'edit: Bob, Ann',       'cm-lp-chip cm-lp-chip-acl'],
  ['[{DENY edit Guest}]',          'deny edit: Guest',     'cm-lp-chip cm-lp-chip-acl'],
  ['[{$username}]',                '$username',            'cm-lp-chip cm-lp-chip-var'],
  ["[{TableOfContents title='x'}]", '⚙ TableOfContents',   'cm-lp-chip cm-lp-chip-plugin'],
  ['[{INSERT com.foo.Bar a=1}]',   '⚙ Bar',                'cm-lp-chip cm-lp-chip-plugin'],
  ['[{Bar}]()',                    '⚙ Bar',                'cm-lp-chip cm-lp-chip-plugin'],
  ["[{SET name='v'}]",             "SET name='v'",         'cm-lp-chip'],
  ```
  plus: inner text longer than 40 chars is truncated to 40 + `…` for the fallback kind.
  `ranges.test.js`: inactive line `a [{ALLOW view Admin}] b [{$x}]` → two `widget` specs of type `chip`
  covering exactly each match; active line → the existing `cm-lp-plugin` marks, no widgets; a `[{…}]`
  inside inline code or a fenced block → nothing; a block-id marker paragraph `[{ALLOW view Admin}] ^abc`
  still yields its `cm-lp-blockid` mark and a chip.
  `widgets.test.js`: `ChipWidget` DOM is a `span` with the classes, text via `textContent`, `title` set.
- [ ] **Step 2:** run → FAIL. **Step 3:** implement; CSS: `.cm-lp-chip` (pill like the existing
  `.cm-lp-plugin` rule), `.cm-lp-chip-acl::before` lock glyph via CSS content, var/plugin variants.
- [ ] **Step 4:** `npx vitest run src/utils/livePreview` → PASS.
- [ ] **Step 5: Commit** `feat(live-preview): plugin, variable and access-rule chips`.

### Task 5: Autolinks, bare URLs, escapes, setext headings, HTML blocks

**Files:**
- Modify: `src/utils/livePreview/ranges.js`, `src/utils/editorTheme.js`
- Test: `ranges.test.js`

**Interfaces:** none new beyond spec classes `cm-lp-setext-underline`, `cm-lp-html` (and reuse of
`cm-lp-link`, `cm-lp-h1`, `cm-lp-h2`).

- [ ] **Step 1: Failing tests** (inactive vs active line each):
  - `<https://x.org>` → hides `<` and `>`, `cm-lp-link` mark on the URL; `<a@b.co>` same.
  - bare `see https://x.org/a now` → `cm-lp-link` mark on the URL only; a URL inside `[t](https://x)`,
    an image, or an `<…>` autolink is not double-marked.
  - `\*not em\*` → hides each backslash only; `\\` hides the first backslash only.
  - setext `Title\n=====` → line 1 `cm-lp-h1`, line 2 `cm-lp-setext-underline`, nothing hidden;
    `Sub\n---` → `cm-lp-h2`; a two-line setext heading → both text lines `cm-lp-h1`.
  - `<div>\nx\n</div>` (HTML block) → every line `cm-lp-html`; no other specs inside it.
- [ ] **Step 2:** run → FAIL. **Step 3:** implement in the tree walk (`Autolink`, `URL`, `Escape`,
  `SetextHeading1/2`, `HTMLBlock` — HTML block keeps its `excluded` behaviour, adding line classes first).
  Verify Lezer's actual node shapes with a quick test before relying on them.
- [ ] **Step 4:** run `npx vitest run src/utils/livePreview` → PASS.
- [ ] **Step 5: Commit** `feat(live-preview): autolinks, bare URLs, escapes, setext headings, HTML blocks`.

### Task 6: Reference links and footnotes

**Files:**
- Modify: `src/utils/livePreview/ranges.js`, `src/utils/editorTheme.js`
- Test: `ranges.test.js`

**Interfaces:**
- Consumes: Task 4's `{ type: 'chip', text, cls, title }` widget spec.
- Produces: `referenceLabels(state) → Set<string>` (normalised labels of every `LinkReference` whose
  label does not start with `^`), exported, cached per (doc, tree) in a `WeakMap` like `firstMarkers`,
  computed only when a visible Link without a URL exists. `normalizeLabel(s) = s.trim().replace(/\s+/g,' ').toLowerCase()`.

- [ ] **Step 1: Failing tests:**
  - `[Text][Ref]` with `[ref]: https://x` anywhere in the doc (including 3,000 lines below) → `Text`
    marked `cm-lp-link`, `[`, `][Ref]` hidden; `[Text][]` with `[text]: u` → same; shortcut `[Text]`
    with a definition → same; **no definition → no specs**.
  - Definition line `[ref]: https://x` → line class `cm-lp-linkdef`, nothing hidden.
  - Typing a definition makes a previously plain `[Text][ref]` decorate (state built twice; cache keyed on doc+tree).
  - Footnote ref `word[^1] more` → widget chip over `[^1]` with text `1`, cls `cm-lp-chip cm-lp-footnote-ref`;
    **not** a link (Review Focus 2) even though Lezer may parse it as a shortcut Link.
  - Definition `[^note]: Some text` → widget chip over `[^note]:` with text `note:`, cls
    `cm-lp-chip cm-lp-footnote-def`; no `cm-lp-linkdef` on that line; `referenceLabels` excludes `^note`.
  - `[[#^abc]]` (wikilink block ref) and a block marker `^abc` → no footnote chip.
  - Inside inline code / fences → nothing.
- [ ] **Step 2:** run → FAIL. **Step 3:** implement: in `link()`, a Link with no `URL` child goes to
  reference handling (skip when its label/text starts with `^`); footnotes by regex in the line pass
  (`/\[\^([^\]\s]+)\]/g`, definition form at line start with ≤3 spaces followed by `:`), honouring
  `excluded`/`claimed` and pushing to `claimed`.
- [ ] **Step 4:** run `npx vitest run src/utils/livePreview` → PASS.
- [ ] **Step 5: Commit** `feat(live-preview): reference-style links and footnotes`.

### Task 7: Safe inline renderer for table cells

**Files:**
- Create: `src/utils/livePreview/inlineCells.js`, `inlineCells.test.js`, `src/utils/livePreview/mathRender.js`
- Modify: `widgets.js` (move the module-private KaTeX LRU `renderedMath` into `mathRender.js` and import it)

**Interfaces:**
- Produces: `renderedMath(tex: string, display: boolean) → { fragment } | { error }` (moved, unchanged
  behaviour) in `mathRender.js`; `renderInlineMarkdown(text: string) → DocumentFragment` in
  `inlineCells.js`. Parsing: `unified().use(remarkParse).use(remarkGfm).use(remarkMath)` plus the
  project's wikilink plugin (`src/utils/remarkWikiLinks.js` — read it to see the node it emits), take the
  first paragraph's children. Node → DOM: `text`→text node; `emphasis`→`em`; `strong`→`strong`;
  `delete`→`del`; `inlineCode`→`code`; `link`/wikilink→`span.cm-lp-link` with the link text (alias or
  target for wikilinks; never an `href`); `image`→`span.cm-lp-cell-img` with alt text; `inlineMath`→
  KaTeX via `renderedMath` (error → source text); `break`→`br`; anything else (incl. `html`)→ its
  source text slice. Only `textContent`/`createTextNode` for page text.

- [ ] **Step 1: Failing tests:** `**b** *i* ~~d~~ \`c\`` → strong/em/del/code; `[t](https://x)` →
  span.cm-lp-link "t", no `<a>`; `[[Page|Alias]]` → span "Alias"; `$x^2$` → contains `.katex`;
  `![alt](i.png)` → span "alt"; **Review Focus 3:** `<img src=x onerror=alert(1)>`, `<script>x</script>`,
  `[x](javascript:alert(1))` → fragment contains no `img`/`script`/`a` element and the raw text is visible;
  empty string → empty fragment; existing `widgets.test.js` math tests still pass after the move.
- [ ] **Step 2:** run → FAIL. **Step 3:** implement. **Step 4:** `npx vitest run src/utils/livePreview` → PASS.
- [ ] **Step 5: Commit** `feat(live-preview): text-only inline renderer for table cells`.

### Task 8: Table widget

**Files:**
- Create: `src/utils/livePreview/tableWidget.js`, `tableWidget.test.js`
- Modify: `ranges.js` (`blockCandidateOf`: top-level `Table` → block spec), `widgets.js` (`widgetFor`
  case `'table'`), `plugin.js` (`sameWidget` must also compare `source` and `language`), `editorTheme.js`
- Test: `ranges.test.js`, `ranges.incremental.test.js`, `plugin.test.js`

**Interfaces:**
- Consumes: Task 7 `renderInlineMarkdown`.
- Produces: `parseTableSource(source: string) → { align: Array<'left'|'center'|'right'|null>, header: string[], rows: string[][] }`
  (split rows on unescaped `|`, trim optional leading/trailing pipe and cell whitespace, `\|` → `|`,
  short rows padded with `''`, long rows truncated to the header width, delimiter row = line 2);
  block spec `{ kind: 'block', from, to, widget: { type: 'table', source } }` (from/to = whole lines of
  the `Table` node); `class TableWidget(source)` — `eq` by `source`, `estimatedHeight` from row count,
  `ignoreEvent() → true`; mousedown on a row places the caret at the start of that row's source line
  (header row → table line 1, body row k → line k+2), using `view.posAtDOM(wrapper)` for the table
  start; classes `cm-lp-table-block` (wrapper) and `cm-lp-table`.

- [ ] **Step 1: Failing tests:**
  - `parseTableSource`: alignments `:--`/`:-:`/`--:`/`---`; escaped pipe; ragged rows; no outer pipes.
  - `ranges.test.js`: top-level table with caret elsewhere → one block spec spanning exactly the table
    lines; caret on any table line → no block spec; table inside a list/blockquote → none; a table whose
    delimiter row is invalid → none (stays source); **Review Focus 4:** a non-blank line directly after
    the last row is inside the widget range (Lezer's lazy continuation), and a table at end-of-doc with no
    trailing newline is covered exactly.
  - `ranges.incremental.test.js`: `updateBlockCandidates` equals a full `blockCandidates` rescan for edits
    inside, before, after and adjacent to a table (add to the existing equivalence harness).
  - `plugin.test.js` **Review Focus 1:** `programmatic edit inside an inactive table/fence redraws it` —
    with the caret elsewhere, dispatch a change inside the table (and separately inside a mermaid fence);
    the resulting decoration's widget has the new `source`.
  - `tableWidget.test.js`: DOM has `thead`/`tbody`, `th`/`td` counts, `style.textAlign` per alignment,
    cell content via `renderInlineMarkdown`; mousedown on body row 2 dispatches a selection at the start of
    table line 4.
- [ ] **Step 2:** run → FAIL. **Step 3:** implement; CSS mirrors the reader's table styling with
  existing variables. **Step 4:** `npx vitest run src/utils/livePreview` → PASS.
- [ ] **Step 5: Commit** `feat(live-preview): tables render as tables off the caret`.

### Task 9: Round-trip invariant, property test, bench

**Files:**
- Create: `src/utils/livePreview/roundTrip.test.js`, fixture `src/utils/__fixtures__/live-preview-kitchen-sink.md`
- Modify: `src/utils/livePreview/plugin.property.test.js` (generator gains tables, `[{…}]`, footnotes,
  reference links, autolinks, escapes, setext headings)

- [ ] **Step 1: Tests:** kitchen-sink fixture containing every construct this plan and the existing live
  preview handle; on a real `EditorView` with the full editor extension set (as
  `PageEditor.livePreview.realCodeMirror.test.jsx` builds it): toggle live on/off/on, move the caret to
  every line, mousedown every widget → the doc string is byte-identical to the fixture and no transaction
  had `docChanged` (track via an `EditorView.updateListener`). The property test still passes with the
  wider generator.
- [ ] **Step 2:** run → PASS (if it fails, the failure is a real defect in Tasks 4–8: fix it there with a
  focused test first).
- [ ] **Step 3: Bench:** `npx vitest bench --run src/bench/livePreview.bench.js`; compare with the baseline
  in the ledger's scratch file; keystroke and caret-move scenarios must not regress by more than 20% —
  if they do, profile and fix before committing. Record before/after numbers in the commit body.
- [ ] **Step 4: Commit** `test(live-preview): round-trip invariant and wider property generator`.

### Task 10: Selenide IT — live preview end to end

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/LivePreviewIT.java`
- Modify (if helpful): `pages/spa/EditWikiPage.java` (an `openInMode(name, mode)` helper)

- [ ] **Step 1: Test** (logged in as the IT admin, page seeded via the REST seed helper with a table, an
  `[{ALLOW view All}]`-free plugin line such as `[{TableOfContents}]`, and a footnote):
  - open the editor in live mode → `.cm-lp-table` visible with the header text; a `.cm-lp-chip-plugin`
    chip with text `⚙ TableOfContents`; click a table row → the raw `|` source line becomes visible.
  - toggle to source with Mod+E, reload the editor → still source (server preference persisted —
    clear localStorage before the reload to prove it); restore the preference to null via
    `PUT /api/auth/profile {"editorMode":null}` in `@AfterEach`.
- [ ] **Step 2:** run `bin/run-tests.sh --module selenide` is too broad; run the IT module's failsafe for
  this class only per `docs/admin/WikantikOperations.md` / existing IT run notes (or defer to the final
  full gate if a single-class run is not supported), and record which.
- [ ] **Step 3: Commit** `test(it): live preview renders tables and chips; mode persists per user`.

### Task 11: Help page

**Files:**
- Modify: `docs/wikantik-pages/EditPageHelp.md` (new "Live preview" section),
  `wikantik-wikipages/en/src/main/resources/EditPageHelp.md` (sync — diff first, keep any frontmatter the
  shipped copy needs)

- [ ] **Step 1:** Write the section: what live preview is, Mod+E / toolbar toggle, the "Default editing
  mode" preference, that putting the caret in a table/chip/diagram shows its source, and that switching
  never changes the page text. Keep it under ~150 words, matching the page's voice.
- [ ] **Step 2:** Sync the shipped copy; run any test that pins shipped pages
  (`grep -rl EditPageHelp wikantik-*/src/test`) → PASS.
- [ ] **Step 3: Commit** `docs(help): live preview section in EditPageHelp`.
