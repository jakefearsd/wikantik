# Rich Editor Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nobody has to type Markdown in the page editor, and in Rich mode they do not see it wherever hiding it is cheap; Source mode is unchanged.

**Architecture:** Today's live preview (CodeMirror 6 decorations over the Markdown text, `wikantik-frontend/src/utils/livePreview/`) becomes Rich. Constructs that are cheap to keep hidden get "sticky" decorations that no longer reveal at the caret, plus a caret rule that keeps the caret out of the hidden text. Tables stay real editor lines drawn as a grid, so typing in a cell is ordinary typing in the one editor. Every action is a pure function from document state to a minimal list of text changes, dispatched through `CodeEditor`'s existing `applyChanges` / `applyEdit`.

**Tech Stack:** React 19, CodeMirror 6 (`@codemirror/state`, `/view`, `/language`, `/collab`), `@lezer/markdown` with the dialect in `src/utils/dialect/`, vitest, Selenide ITs. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-18-rich-editor-mode-design.md` — read it first; this plan argues from it.

## Global Constraints

- No new runtime dependencies in `wikantik-frontend`.
- Every action ends as a text change through `CodeEditor`'s `applyChanges([{from,to,insert}])` or `applyEdit` — never a second write path. Shared editing (`docs/MultiEdit.MD`) must not notice this work.
- An action changes only the characters it must. No re-padding, re-aligning, marker normalising or line-ending changes outside the edited span.
- Opening a page in Rich, moving the caret anywhere and leaving produces zero document changes.
- Two modes only: stored values `live` | `source` stay; `live` means Rich. The user-visible name is "Rich" (and "Source"). Command ids and existing test names that say `live` stay.
- The mode changes only by the toolbar toggle or `Mod-E`.
- The shared reader fixtures in `src/utils/__fixtures__/` are not edited. The dialect and the server are not touched.
- The editor document is always LF (CodeEditor converts); no task handles CRLF itself.
- Legacy wiki-syntax pages (`isLegacyWiki` in `PageEditor.jsx`) stay Source-only; new commands behave there as the existing toolbar commands do, with no new gating.
- Out of scope: `WorkBodyEditor`, the frontmatter form, two live editors side by side.
- Cost guards: use `src/test/linearCost.js` (`linearCost`, `LINEAR_COST_TIMEOUT_MS`) for anything that scans; a caret move must not walk the document.
- No empty `catch`: log with `console.warn('[rich] …', err?.message || err)`.
- Project rules: TDD (see the failing test fail first), work on `main`, `git add` files by name, 1–3 line commit messages. Frontend tests: `cd wikantik-frontend && npx vitest run <file>`; lint: `npx eslint <files>`. Before the last commit of each phase: `bin/run-tests.sh --parallel 4` via the long-build protocol in `CLAUDE.md`.
- Each phase ships on its own. Do not start a phase with the previous one red.

## Review Focus

Inputs the spec implies but does not spell out. Each is pinned by a test in the task named.

1. **Multi-line text pasted into a table cell** — expected: line breaks become spaces, pipes are escaped, the table keeps its shape (Task 13).
2. **A collaborator deletes the row or the whole table while you are typing in one of its cells** — expected: your caret lands at the nearest surviving position, no exception, no text of yours inserted outside a cell in a way that breaks a neighbouring row (Task 15).
3. **A selection that starts in a paragraph and ends inside a hidden marker or a table cell, then Delete or typing** — expected: the result is what Source mode would give for the same character range, never a half-deleted marker left visible only in Source (Tasks 7 and 13).
4. **IME / composition input (and mobile autocorrect) at a position next to a hidden marker or cell boundary** — expected: the caret rule does not move the caret mid-composition and the composed text lands where it was typed (Tasks 7, 13, 18).
5. **Undo after a toolbar, card or column action** — expected: one undo step restores the exact previous text and a sensible selection (Tasks 2, 10, 14).

---

## File Structure

New files (all under `wikantik-frontend/src/`):

| File | Responsibility |
|---|---|
| `utils/formatState.js` | What formatting the caret is in, read from the syntax tree (for toolbar state) |
| `utils/livePreview/sticky.js` | Sticky (never-revealed) ranges: the facet, atomic ranges, the caret rule, Backspace-removes-marker |
| `utils/livePreview/headers.js` | Fence header and callout title widgets with their pickers |
| `utils/linkEdit.js` | Read a link/wikilink/embed/image at a position into a model; write a model back to text |
| `components/editor/LinkCard.jsx` | The card for links, embeds and images |
| `components/editor/AccessRuleForm.jsx` | The small form behind "Insert access rule" |
| `utils/tableModel.js` | Read a table from the tree; pure operations returning minimal changes |
| `utils/livePreview/tableGrid.js` | Grid decorations for editable tables, caret confinement, Tab/Enter, cell input rules |
| `components/editor/TableControls.jsx` | Row/column buttons and menu for the table the caret is in |
| `components/editor/SourceMirror.jsx` | Read-only source pane that follows the caret |
| `utils/livePreview/inlineSticky.js` | Phase 6 only: caret-at-edge and pair-balancing rules for hidden inline marks |

Modified: `utils/markdownFormat.js`, `utils/editorCommands.js`, `utils/slashComplete.js`, `components/EditorToolbar.jsx`, `components/PageEditor.jsx`, `components/CodeEditor.jsx`, `components/UserPreferencesPage.jsx`, `utils/livePreview/{ranges,plugin,index,widgets,tableWidget}.js`, `utils/editorTheme.js`. `PageEditor.jsx` is ~2000 lines: grep for the anchors named in each task rather than reading it whole, and put new logic in the new files, not in it.

Tests sit beside their file (`x.test.js`); real-editor tests follow `components/PageEditor.*.realCodeMirror.test.jsx` and use `src/test/realEditorHarness.jsx`; collab tests follow `components/PageEditor.collab.test.jsx` and `PageEditor.interleaving.realCodeMirror.test.jsx`. Browser tests go in `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RichEditorIT.java`, modelled on `LivePreviewIT.java` and using the page object `com/wikantik/pages/spa/EditWikiPage.java`.

---

# Phase 1 — Insert surface and toolbar state (both modes)

### Task 1: Formatting functions for the missing constructs

**Files:** Modify `utils/markdownFormat.js`; Test `utils/markdownFormat.test.js`

**Interfaces — Produces** (all take and return `{ text, selStart, selEnd }`, as the existing functions do):
- `insertFence(state, language, body)` — a fenced block ```` ```language\nbody\n``` ````, blank line before as `insertCodeBlock` does; caret at the start of the body.
- `insertFootnote(state)` — inserts `[^N]` at the caret and appends `\n\n[^N]: ` at the document end, where N is the smallest positive integer with no `[^N]` in the text; caret after the definition's `: `.
- `insertEmbed(state)` — inserts `![[]]` with the caret between the inner brackets (the existing wikilink completion then opens).
- `insertAccessRule(state, { permission, principals })` — inserts `[{ALLOW permission A,B}]` on its own line at the start of the caret's line. `permission` is one of `view comment edit modify upload rename delete`; `principals` is a non-empty array of names. Before writing this, read `wikantik-main/src/main/java/com/wikantik/auth/acl/DirectiveSpelling.java` (`canonical`) and make the output exactly its canonical form; if a multi-name rule's canonical separator is not a comma, use what it is.
- `setHeading` accepts level 4 (already general; add the test).
- `toggleLinePrefix(state, '1. ')`, `'- [ ] '`, `'> '` — already general; add tests. For `'1. '` every selected line gets `1. ` (the reader numbers them).

- [ ] **Step 1: Failing tests.** One `describe` per function. Assert the exact resulting `text`, `selStart`, `selEnd` for: empty document; caret mid-paragraph; a selection; and for footnote, a text already holding `[^1]` and `[^3]` (expect `[^2]`). For `insertFence('mermaid', 'graph TD\n  A --> B')` on `'intro'` with the caret at 5 expect `'intro\n\n```mermaid\ngraph TD\n  A --> B\n```\n'`. For each function also assert the minimal-change property: `text` starts with the original text before `selStart` unchanged and ends with the original text after `selEnd` unchanged (footnote: except the appended definition).
- [ ] **Step 2:** `npx vitest run src/utils/markdownFormat.test.js` — FAIL (functions undefined).
- [ ] **Step 3:** Implement, reusing `insertBlockAt` / `blankLineLead`.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat(editor): formatting functions for fences, footnotes, embeds, access rules`.

### Task 2: Commands, `/` menu entries and `applyFormat` cases

**Files:** Modify `utils/editorCommands.js`, `utils/slashComplete.js`, `components/PageEditor.jsx` (`applyFormat`, ~line 937, and `buildEditorCommands` call ~1112); Create `components/editor/AccessRuleForm.jsx`; Tests `utils/editorCommands.test.js`, `utils/slashComplete.test.js`, `components/editor/AccessRuleForm.test.jsx`, `components/PageEditor.obsidianFormat.realCodeMirror.test.jsx` (extend).

**Interfaces — Consumes:** Task 1. **Produces:** command ids, exact:

| id | title | `format(…)` argument | slash | keys |
|---|---|---|---|---|
| `format-strike` | Strikethrough | `strike` (`toggleWrap '~~'`) | no | `Mod-Shift-X` |
| `format-ordered-list` | Numbered list | `olist` | yes | |
| `format-task-list` | Task list | `tasklist` | yes | |
| `format-quote` | Quote | `quote` | yes | |
| `heading-4` | Heading 4 | `h4` | yes | |
| `insert-mermaid` | Mermaid diagram | `fence:mermaid` | yes | |
| `insert-plantuml` | PlantUML diagram | `fence:plantuml` | yes | |
| `insert-footnote` | Footnote | `footnote` | yes | |
| `insert-embed` | Embed a page | `embed` | yes | |
| `insert-access-rule` | Access rule | opens `AccessRuleForm` (new injected action `pickAccessRule`) | yes | |

The diagram bodies are the first valid `mermaid` and `plantuml` sources in `src/utils/__fixtures__/renderedFences.json` (read them at build time of the test; hard-code the same strings in the command). `format-highlight` and `toggle-comment` gain `slash: true`. `SLASH_ORDER` becomes: headings 1–4, lists (bulleted, numbered, task), quote, callouts, table, code block, the two diagrams, math block, rule, image, link, embed, footnote, highlight, comment, access rule.

`AccessRuleForm({ open, onClose, onInsert })` — a `components/ui/Modal` with a permission `Select` (the seven permissions, default `view`) and a `TagInput` for names; Insert is disabled with no name; calls `onInsert({ permission, principals })`.

- [ ] **Step 1: Failing tests.** `editorCommands.test.js`: every id above exists with its title and `slash` flag. `slashComplete.test.js`: with an empty query the option labels are in `SLASH_ORDER`. `AccessRuleForm.test.jsx`: Insert disabled until a name is entered; submitting calls `onInsert({ permission: 'edit', principals: ['Editors'] })`. Real-editor test: running each new command on `'alpha'` gives the text Task 1 defines, **and one `undo` restores `'alpha'` exactly** (Review Focus 5).
- [ ] **Step 2:** Run the four files — FAIL.
- [ ] **Step 3:** Implement. `applyFormat` gains the cases; a `fence:` prefix is handled like `callout:`.
- [ ] **Step 4:** PASS; `npx eslint` on the touched files clean.
- [ ] **Step 5:** Commit `feat(editor): commands and slash entries for lists, quote, diagrams, footnote, embed, access rule`.

### Task 3: Format state at the caret

**Files:** Create `utils/formatState.js`, `utils/formatState.test.js`

**Interfaces — Produces:**
`formatStateAt(state: EditorState): { bold, italic, strike, highlight, code: boolean, heading: 0|1|2|3|4|5|6, list: null|'bullet'|'ordered'|'task', quote: boolean, link: boolean }` and `EMPTY_FORMAT_STATE` (all false / 0 / null).

Read it from `syntaxTree(state).resolveInner(head, -1)` and its ancestors (node names: `StrongEmphasis`, `Emphasis`, `Strikethrough`, the dialect's highlight node — see `utils/dialect/highlight.js`, `InlineCode`, `ATXHeadingN`, `SetextHeadingN`, `BulletList`, `OrderedList`, `Task`, `Blockquote`, `Link`, `WikiLink`). The innermost list wins. With a non-empty selection, a flag is true only when the whole selection is inside one such node. Must be O(depth), not a document walk.

- [ ] **Step 1: Failing tests** building states with the harness's dialect extensions: caret inside `**a|b**` → `bold`; inside `- [ ] x|` → `list: 'task'`; inside `> ## t|` → `quote` and `heading: 2`; in a nested ordered-in-bullet item → `'ordered'`; plain text → `EMPTY_FORMAT_STATE` (deep equal); selection `a**[b]**c` covering only `b` → `bold`; selection spanning out of the bold → not bold. A `linearCost` test: 20 000 lines of bold text, caret at the end.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5:** Commit `feat(editor): format state at the caret`.

### Task 4: Toolbar — full button set, state, heading and Insert menus, keyboard, overflow

**Files:** Modify `components/EditorToolbar.jsx`, `components/EditorToolbar.test.jsx`, `components/PageEditor.jsx` (toolbar render ~1977; feed format state from the existing `handleViewChange`), `utils/editorTheme.js` or the toolbar's CSS file.

**Interfaces — Consumes:** `formatStateAt`, the command ids of Task 2. **Produces:** `EditorToolbar({ onRun, liveMode, liveDisabled, formatState = EMPTY_FORMAT_STATE })`.

Layout, left to right: Bold, Italic, Strikethrough, Heading ▾ (Paragraph, Heading 1–4; shows the current level), Bulleted, Numbered, Task, Quote, Inline code, Table, Link, Insert ▾ (code block, Mermaid diagram, PlantUML diagram, math block, callouts, image, embed, footnote, highlight, comment, rule, access rule), then the mode control from Task 5 (this task leaves the existing toggle in place).

- Every button: `aria-label` = its title; `title` = title plus shortcut; toggle buttons carry `aria-pressed` from `formatState`.
- `role="toolbar"` with roving tabindex: Left/Right move between buttons, Home/End jump, Tab leaves.
- Menus are `role="menu"`; Escape closes and returns focus to the trigger; choosing an item runs the command and returns focus to the editor.
- Overflow: buttons that do not fit the toolbar's width move into the Insert menu, measured with a `ResizeObserver`; without `ResizeObserver` (jsdom) nothing moves.
- "Paragraph" runs the current heading level's command (which toggles it off); when `formatState.heading` is 0 it is disabled.
- `PageEditor` computes `formatStateAt(view.state)` in `handleViewChange` and stores it in state only when it differs from the previous value (shallow compare), so typing does not re-render the toolbar.

- [ ] **Step 1: Failing tests.** All buttons present by accessible name; `formatState={{…, bold: true}}` → Bold has `aria-pressed="true"`; `heading: 3` → trigger text "Heading 3"; ArrowRight moves focus to the next button; opening Insert and choosing "Mermaid diagram" calls `onRun('insert-mermaid')`; Escape closes the menu. Real-editor test in `PageEditor.realCodeMirror.test.jsx`: placing the caret inside bold text makes the toolbar's Bold pressed.
- [ ] **Step 2–4:** fail, implement, pass, eslint clean.
- [ ] **Step 5: Phase gate.** `bin/run-tests.sh --parallel 4` green. Commit `feat(editor): toolbar covers the dialect and shows the caret's formatting`.

---

# Phase 2 — Rich mode

### Task 5: The name, and the mode control

**Files:** Modify `components/EditorToolbar.jsx` (+ test), `components/UserPreferencesPage.jsx` (~line 450, + test), `utils/editorCommands.js` (title of `toggle-live-preview`), any user-facing docs page that names "Live preview" (`grep -rn "Live preview" docs/wikantik-pages | head`; change only pages in the checkout that describe the editor).

- The toolbar's mode control is a two-option segmented control, `Rich` | `Source`, `role="radiogroup"`, the active one `aria-checked`. Clicking the inactive option runs `toggle-live-preview`. On a legacy page `Rich` is disabled with the title "Rich editing is available for Markdown pages".
- Command title: `Switch between Rich and Source` (id unchanged, keys `Mod-E` unchanged). Preferences option label: `Rich`.

- [ ] **Step 1: Failing tests:** the control renders both options; `liveMode` → Rich checked; clicking Source calls `onRun('toggle-live-preview')`; `liveDisabled` → Rich disabled with that title; the preferences select shows "Rich" for value `live`.
- [ ] **Step 2–4:** fail, implement, pass. Update any existing test that asserts the old label.
- [ ] **Step 5:** Commit `feat(editor): live preview is named Rich; explicit Rich | Source control`.

### Task 6: Sticky ranges — the mechanism

**Files:** Create `utils/livePreview/sticky.js` (+ test); Modify `utils/livePreview/ranges.js`, `plugin.js`, `index.js`.

**Interfaces — Produces:**
- In `ranges.js`, the `hide` and `widget` helpers inside `livePreviewSpecs` take a trailing options argument `{ sticky = false }`. A sticky spec is emitted whether or not its line is active and carries `sticky: true`. Non-sticky behaviour is unchanged.
- `plugin.js`: `toDecorations` passes `sticky` into the decoration spec (`Decoration.replace({ …, lpSticky: true })`).
- `sticky.js` exports:
  - `stickyRangesOf(view): RangeSet` — the sticky decorations of the inline plugin and the block field.
  - `stickyAtomic` — `EditorView.atomicRanges.of(stickyRangesOf)`.
  - `stickyCaret` — an `EditorState.transactionFilter`: for a transaction that sets the selection (and is not a composition: skip when `tr.isUserEvent('input.type.compose')` or the view is composing), an empty range whose head sits at the **start** of a sticky range that begins at a line start moves to that range's end; a range head strictly inside a sticky range moves to the nearer end. Non-empty selections are left alone.
  - `removeLineMarker` — a keymap command bound to Backspace (precedence above the default): with an empty selection whose head is at the end of a line-start sticky range, delete exactly that range's text (the marker and its one trailing space) and nothing else; otherwise return false.
  - `stickyExtension` = `[stickyAtomic, stickyCaret, keymap.of([{ key: 'Backspace', run: removeLineMarker }])]`, active only when `liveModeField` is true.
- `index.js`: `livePreview()` includes `stickyExtension`.

This task makes nothing sticky; it adds the mechanism with a test-only sticky spec.

- [ ] **Step 1: Failing tests** (`sticky.test.js`, real `EditorState`/`EditorView` via the harness, with a test extension that marks `## ` on headings sticky): Home on `## Title` puts the caret at offset 3, not 0; ArrowLeft from offset 3 goes to the end of the previous line; Backspace at offset 3 gives `Title`; typing at the caret never inserts before `## `; with live mode off none of this applies (Home → 0); during composition the filter does not move the caret (Review Focus 4: dispatch with `userEvent: 'input.type.compose'`); a selection from the previous paragraph into offset 1 then Delete yields the same text as the same range deleted in Source mode (Review Focus 3).
- [ ] **Step 2–4:** fail, implement, pass. Run `utils/livePreview/*.test.js` whole: all green (no behaviour change yet).
- [ ] **Step 5:** Commit `feat(rich): sticky ranges — atomic, caret rule, Backspace removes a line marker`.

### Task 7: Block markers stay hidden under the caret

**Files:** Modify `utils/livePreview/ranges.js` (the `heading`, list-marker, task, blockquote paths), `utils/livePreview/widgets.js` if a widget needs an active-line variant; Tests `utils/livePreview/ranges.test.js`, `roundTrip.test.js`, `corpus.test.js`, `components/PageEditor.livePreview.realCodeMirror.test.jsx`.

**Interfaces — Consumes:** Task 6's `{ sticky: true }`.

Make sticky: ATX heading marker and its space; bullet marker (drawn as `BulletWidget`); ordered-list number (shown as text, the marker itself atomic); task checkbox (`CheckboxWidget`, still clickable); block-quote `>` marks. Setext underlines, and any marker inside the "expensive" set (footnote definitions, tables in containers), keep reveal-at-caret.

- Enter on a list item must keep continuing the list as it does today, and Enter on an empty item must end the list (check what handles this now — `grep -n "insertNewlineContinueMarkup\|Enter" components/CodeEditor.jsx utils/obsidianSyntaxKeymap.js` — and keep it; add the binding from `@codemirror/lang-markdown` only if nothing does).
- `Backspace` at the start of a heading's/item's/quote's content removes one level of marker (Task 6's command; for a nested quote-in-list line, the innermost marker).

- [ ] **Step 1: Failing tests.** `ranges.test.js`: with the caret on a heading line, the specs still hide `## `; same for `- `, `1. `, `- [ ] `, `> `. Real-editor: type `x` with the caret at the visual start of `## Title` → `## xTitle`; Backspace there → `Title`; Enter at the end of `- a` → `- a\n- `; Enter on the empty item → list ends. **No-op invariant** in `roundTrip.test.js`: for `live-preview-kitchen-sink.md` and every corpus page `corpus.test.js` already iterates, mount in Rich, set the selection to each line start and each line end in turn, and assert `doc.toString()` is unchanged and no transaction had `docChanged`. Review Focus 3: select from mid-paragraph to mid-next-heading-line, press Delete, compare with the same range deleted from the plain string.
- [ ] **Step 2–4:** fail, implement, pass; `PageEditor.previewCost.realCodeMirror.test.jsx` still within budget.
- [ ] **Step 5:** Commit `feat(rich): heading, list, task and quote markers stay hidden at the caret`.

### Task 8: Fence headers and callout titles

**Files:** Create `utils/livePreview/headers.js` (+ test); Modify `ranges.js` (fence and callout paths), `widgets.js` (`CalloutTitleWidget`, `FenceWidget` click behaviour), `editorTheme.js`.

**Interfaces — Produces:**
- `FenceHeaderWidget({ language, kind })` replaces the opening fence line's text (sticky). It shows the language (or "Code", or "Mermaid diagram" / "PlantUML diagram" when `rendererFor(language)` from `utils/fences/registry.js` exists) and is a `<select>`; choosing a value dispatches one change replacing **only the info string's first word**. Options: the registry's rendered kinds, then `text bash java javascript typescript json yaml sql python xml html css diff` , then the current value if it is none of those.
- The closing fence line's text is hidden (sticky) and the line gets class `cm-lp-fence-close` (rendered as a thin bottom border; the caret rule already keeps the caret off it).
- `CalloutTitleWidget` stays drawn when the caret is on its line. Its type badge is a `<select>` over `note tip info warning danger` plus the current type; choosing rewrites only the word inside `[!…]`. The title text after the marker is ordinary editable text (the widget replaces only `> [!type]` and its trailing space, sticky).
- A rendered diagram (`FenceWidget`) keeps today's behaviour: drawn when the caret is outside, source body when the caret is inside — now with the header instead of the raw fence line.
- Fences and callouts nested in a list or quote: same treatment where today's code already decorates them; where today leaves them as source (`SKIP` in `ranges.js`), leave them.

- [ ] **Step 1: Failing tests.** `headers.test.js`: for ```` ```js\nx\n``` ```` the header shows `js`; choosing `python` yields ```` ```python\nx\n``` ```` via exactly one change `{from: 3, to: 5, insert: 'python'}`; an info string `js title="a"` keeps ` title="a"`. Callout: `> [!note] Heads up` → choosing `warning` yields `> [!warning] Heads up` with one change covering only `note`. Real-editor: caret on the first body line, ArrowUp skips the header to the line above the fence; the no-op invariant from Task 7 still passes.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5: Phase gate.** Add to `RichEditorIT.java`: open a page with a heading, a list and a code block in Rich; assert no `#`, list marker or backticks are visible with the caret on each line; switch to Source with the control; save without edits and assert the stored text is byte-identical (fetch via the REST page API as `LivePreviewRoundTripIT` does). `bin/run-tests.sh --parallel 4` green. Commit `feat(rich): fence headers and callout titles with pickers`.

---

# Phase 3 — Cards

### Task 9: Link model

**Files:** Create `utils/linkEdit.js`, `utils/linkEdit.test.js`

**Interfaces — Produces:**
- `readLinkAt(state: EditorState, pos: number): LinkModel | null` where
  `LinkModel = { kind: 'wiki'|'embed'|'link'|'image', from, to, source, target?, heading?, alias?, size?, url?, text?, alt?, title? }`. `wiki`/`embed` are parsed with `parseWikiLink` (`utils/wikiLinkSyntax.js`); `link`/`image` from the tree's `Link`/`Image` children. Returns null for reference-style links, autolinks, `WikiLinkText` (a bracket pair that is no wikilink) and anything inside code.
- `writeLink(model: LinkModel): string` — the Markdown for the model. **`writeLink(readLinkAt(s, p))` returns `model.source` exactly when no field was changed** (keep `source` and a snapshot of the parsed fields; re-emit only when a field differs).
- `unwrapLink(model): string` — the display text only (`alias`, else `target`/`text`; for an image, the alt text).
- `linkChange(model, patch): { from, to, insert }` — the change for a patched model.

Writing rules: a wiki target or alias never contains `]]`, `|` in the target, or a line break — `linkChange` throws a `RangeError` with a readable message for those and the card shows it; a Markdown link's text escapes `[` and `]`; a URL containing a space or `)` is written in `<…>` form.

- [ ] **Step 1: Failing tests.** Table-driven, taking inputs from `src/utils/__fixtures__/wikilinks.json` where they fit: each of `[[P]]`, `[[P#H]]`, `[[P|a]]`, `[[P#H|a]]`, `![[P]]`, `![[img.png|300]]`, `[t](u)`, `[t](<u v>)`, `![a](u "t")` round-trips to itself; patching `target` on `[[P|a]]` gives `[[Q|a]]`; clearing `alias` gives `[[P]]`; `unwrapLink` of `[[P|a]]` is `a`; `readLinkAt` inside `` `[[P]]` `` and on `[[ not a link]]` is null; an alias of `x]]y` throws `RangeError`.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5:** Commit `feat(rich): link model — read, write, unwrap`.

### Task 10: The card, sticky chips, and `Mod-K`

**Files:** Create `components/editor/LinkCard.jsx` (+ test); Modify `utils/livePreview/ranges.js` (wikilink, link, embed, image paths → sticky), `widgets.js` (`ChipWidget`, `ImageWidget`, `EmbedWidget`: a click reports instead of placing the caret in source), `plugin.js` (a `StateEffect` `openLinkCard: { pos }` and a facet callback `onOpenLinkCard`), `components/CodeEditor.jsx` (prop `onOpenLinkCard`), `components/PageEditor.jsx` (card state; `Mod-K` / `insert-link` routing).

**Interfaces — Consumes:** Task 9; the page-name suggestion source the wikilink completion uses (`utils/wikiLinkComplete.js` — reuse its fetch, do not write another).
**Produces:** `LinkCard({ model, anchorRect, suggestPages, onCommit(patch), onRemove(), onOpen(), onClose() })`.

- Fields by kind — wiki/embed: Page (a `components/ui/Combobox` fed by `suggestPages`), Heading (optional), Text (optional; hidden for embeds), Size (only when `model.size` exists or the target is an image file). link: URL, Text. image: Source, Alt text.
- One text change on commit (Enter, or focus leaving the card with changed fields); Escape discards; "Remove" applies `unwrapLink`; "Open" follows the link in a new tab. No change is dispatched when nothing was edited.
- The card is `role="dialog"` `aria-label="Edit link"`, traps focus, returns focus to the editor at the link's end on close.
- If the document changes under an open card (a collaborator edits), map `model.from/to` through the change; if the link's text itself changed or was deleted, close the card and discard.
- In Rich mode `insert-link` / `Mod-K`: caret or selection inside a link → open its card; a non-empty selection → wrap it as `[selection]()` and open the card focused on URL; nothing selected → insert `[[]]` and let the completion open (today's wikilink flow). In Source mode it keeps today's `insertLink` behaviour.
- Links inside the "expensive" set (reference-style) keep reveal-at-caret.

- [ ] **Step 1: Failing tests.** `LinkCard.test.jsx`: renders the right fields per kind; editing Page then Enter calls `onCommit({ target: 'Q' })` once; Escape calls only `onClose`; an invalid alias shows the error and does not commit. Real-editor (`PageEditor.linkCompletion.test.jsx` or a new `PageEditor.linkCard.realCodeMirror.test.jsx`): with the caret adjacent to `[[P|a]]` the chip is still drawn (no `[[` visible); clicking it opens the card; committing a new page name produces exactly one transaction whose change covers only the link; **one undo restores the original text** (Review Focus 5); a remote change earlier in the document while the card is open does not misplace the commit.
- [ ] **Step 2–4:** fail, implement, pass; the Task 7 no-op invariant still passes.
- [ ] **Step 5: Phase gate.** `RichEditorIT`: retarget a wikilink through its card and assert the saved source. Gate green. Commit `feat(rich): link, embed and image cards; chips stay drawn at the caret`.

---

# Phase 4 — Table grid

Decision (author's, within "left to the builder"): **table rows stay real editor lines.** The grid is decorations over those lines — pipes drawn as cell borders, the delimiter row hidden, cells sized into columns — not a widget with its own editable DOM. Typing in a cell is then ordinary typing in the one editor, which is what makes per-keystroke shared editing, undo, IME and remote cursors work without new machinery. Tables the grid cannot own keep today's `TableWidget`.

### Task 11: Table model and operations

**Files:** Create `utils/tableModel.js`, `utils/tableModel.test.js`

**Interfaces — Produces:**
- `readTable(state, tableNode): TableModel` with
  `TableModel = { from, to, editable: boolean, columns: number, aligns: ('left'|'center'|'right'|null)[], header: Row, delimiter: { from, to }, body: Row[] }`, `Row = { from, to, cells: Cell[] }`, `Cell = { from, to }` — `from..to` is the cell's content span with surrounding spaces excluded (an empty cell is a zero-width span at the position where typing should go).
  `editable` is true only when the node is a top-level `Table` (its parent is the document), every row's cell count equals the delimiter row's, and every row is a single line.
- `tableAt(state, pos): TableModel | null`; `cellAt(table, pos): { row: number, col: number } | null` (row 0 = header, body rows from 1).
- `escapeCell(text: string): string` — `|` → `\|` unless already escaped; `\r?\n` and runs of them → one space.
- Operations, each returning an array of non-overlapping `{from, to, insert}`:
  `insertRow(table, index)`, `deleteRow(table, index)` (never the header), `insertColumn(table, index)`, `deleteColumn(table, index)` (never the last remaining column), `moveColumn(table, from, to)`, `setAlign(table, col, align)`.
  New cells are a single space between pipes; a new delimiter cell is ` --- `. `setAlign` rewrites only that delimiter cell (`:---`, `:---:`, `---:`, `---`). No operation touches a cell it does not have to.

- [ ] **Step 1: Failing tests.** Reading: a 3×2 table; escaped pipe `a \| b` is one cell; a pipe in a code span is one cell; a ragged table and a table inside a list item are `editable: false`. Operations: apply the returned changes to the source string and assert the exact result, e.g. `insertColumn` at index 1 of `| a | b |\n| --- | --- |\n| 1 | 2 |` gives `| a |  | b |\n| --- | --- | --- |\n| 1 |  | 2 |`; `deleteRow` removes exactly one line and its newline; `setAlign(…, 0, 'center')` changes only `---` → `:---:`. A property test: for each operation, every character outside the returned change ranges is unchanged. `escapeCell('a|b\nc')` is `a\|b c`. `linearCost` on `readTable` for 5 000 rows.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5:** Commit `feat(rich): table model and minimal-change operations`.

### Task 12: Layout spike (throwaway)

Not committed to `main`'s source tree; work in the scratchpad or a stash. Purpose: settle how columns are sized when rows are separate editor lines.

- [ ] **Step 1:** In a local build (`mvn clean install -DskipTests -T 1C && bin/redeploy.sh`), prototype: each cell's content wrapped by a `Decoration.mark` with class `cm-lp-cell` and inline style `width: var(--lp-col-N)`, `display: inline-block`; pipes replaced by zero-width border widgets; column widths written as CSS variables on the table's line elements from a `view.requestMeasure` pass over the visible rows, with a character-count estimate for rows not in view.
- [ ] **Step 2:** Judge in a real browser (Chrome and Firefox) on: a 3-column table with one long wrapping cell; a 12-column table wider than the editor; a 500-row table while scrolling; typing that widens a column. **Exit criteria:** columns line up across visible rows; no visible jump while typing inside a cell that does not grow its column; scrolling the 500-row table shows no row-height thrash.
- [ ] **Step 3:** Write the outcome into this plan under Task 13 ("Column sizing: …") as a short paragraph: the technique that passed, or the fallback chosen. The fallback, if inline-block sizing fails: fixed equal-share column widths (`calc(100% / columns)`, min 8ch, horizontal scroll on the table's lines) — plain, but stable. Commit only the plan edit.

### Task 13: The grid — drawing, caret confinement, cell input

**Files:** Create `utils/livePreview/tableGrid.js` (+ test); Modify `ranges.js` (`tableCandidateOf`: editable tables are no longer block-widget candidates), `plugin.js`/`index.js` (wire the grid extension), `tableWidget.js` (kept for non-editable tables only), `editorTheme.js`.

**Interfaces — Consumes:** Task 11; Task 6's sticky mechanism; Task 12's sizing decision.
**Produces:** `tableGridExtension` (active with `liveModeField`), and for Task 14 `currentTable(state): { table: TableModel, row: number, col: number } | null` (the table and cell holding the main caret, memoised per `(doc, selection.main.head)`).

**Column sizing (from the Task 12 spike):** the brief's technique passed in headless Chrome 157 and Firefox (report: `.superpowers/sdd/2026-11-18-rich-editor-mode/task-12-report.md`); the equal-share fallback is not needed.
- *Structure.* Each row line gets `Decoration.line({ class: 'cm-lp-table-row', attributes: { style: '--lp-col-0:97px;--lp-col-1:…' } })` (one style string per table). Each cell is one `Decoration.mark({ class: 'cm-lp-cell', inclusive: true, attributes: { style: 'width:var(--lp-col-N)' } })` running **from the cell's content start to the next cell's content start** (the last one to the line end), with the closing padding and pipe hidden by a `Decoration.replace` *inside* it; only the row's opening `| ` is a replace outside any cell. `inclusive: true` is required: without it CodeMirror draws a replace that ends where the mark ends outside the cell's span, and the caret at a cell's end is then drawn at the cell's border with zero height. An empty cell has no text to mark: a replace **widget** with the cell's class and width draws its box.
- *CSS.* `.cm-lp-table-row { display: flex; align-items: stretch; white-space: nowrap; width: max-content; min-width: 100% }`, `.cm-lp-cell { flex: 0 0 auto; box-sizing: content-box; padding: 2px 8px; border-left/bottom: 1px; white-space: break-spaces; overflow-wrap: anywhere; min-height: 1lh }`. `display: inline-block; vertical-align: top` cells on a plain line measure identically, but their borders stop at each cell's own height in a row with a wrapped cell, so use flex.
- *Widths.* A column's natural width is the max over **all** rows (off-screen included) of a per-cell-text width: a canvas `measureText` estimate of the *drawn* text (link destinations and emphasis/code markers stripped), cached by `font|source text`. A `view.requestMeasure` pass (key `'lp-grid'`) over the drawn rows reads each cell's real width (per visual line, the extent of `Range.getClientRects()`, summed) and may only **raise** the cached value; it then redispatches from a microtask (a dispatch inside the measure cycle throws; the microtask still runs before the frame paints). Fit: if the naturals (each at least 8ch) exceed the room, columns under the fair share keep theirs and the rest share what is left, never under 8ch; if 8ch each still does not fit, the row overflows and the editor's scroller scrolls sideways. Take the room from `scrollDOM.clientWidth` minus the gutters, never from `contentDOM` (a `nowrap` row widens `.cm-content`, which feeds back).
- *Measured* (820px editor; 3-col with a 215-char cell, 12-col, 500-row, typing; both browsers, both display modes): same-column x spread across drawn rows 0px in every case and every recorded frame; typing in a cell that does not grow its column moved nothing (0px top/height/x over ~28 frames); growing a column moved the later columns of every row by the same amount in one frame (10px for one character) with 0px height change unless the table is already squeezed (then wrapped cells re-wrap, as in any table); 500 rows: 0 row-height changes and 0 x changes over 280 scrolled frames (~14,000 row comparisons) and 0px change after each of 9 jumps settled; a keystroke costs 3ms (Chrome) / 6ms (Firefox) median. Letting a measurement *lower* a width shifted a column by 87px while scrolling, hence raise-only; the one remaining shift is a single widening (6px in the test) when a cell drawn wider than its estimate (a code span) first scrolls in.
- *Constraints.* Provide the cell marks at the lowest precedence so that the inline pass's marks nest inside the cell's span, and leave nothing of an editable row's text outside a cell (it would become a stray flex item). A caret in a cell's trailing padding (a space just typed) must stay drawn as cell text (end the hidden range at the caret), or the next character lands in the next cell. A cursor at a cell's first position needs `assoc: 1` and at its end `assoc: -1`: after ArrowRight across a pipe the head is in the next cell but `drawSelection` draws it at the previous cell's text end until this is set. Backspace in an empty cell deletes the atomic pipe and End/ArrowRight leave the last cell for the line end: both are for Task 13's key rules. Key the width cache by the model's cell text, not by `posAtDOM` of the cell's span (a hidden mark at a cell's edge can be drawn outside it).

Behaviour:
- Row lines get `cm-lp-table-row` (and `-header` for the first); the delimiter line's text is hidden and the line collapsed (sticky); leading/trailing/inner pipes and their padding spaces are sticky, drawn as cell borders. Inline constructs inside cells are decorated by the ordinary inline pass (bold, chips), as in a paragraph.
- Only rows in the viewport are decorated.
- Caret confinement: Task 6's rule keeps the caret out of pipes. In addition, in an editable table: `Tab` → start of the next cell (wrapping to the next row; **in the last cell, apply `insertRow` and go to its first cell**); `Shift-Tab` → previous cell; `Enter` → same column of the next row (in the last row, as Tab-in-last-cell but same column); ArrowUp/Down keep the column.
- Cell input rules, as a `transactionFilter` for user input whose changes fall inside an editable table: inserted text is passed through `escapeCell` (so a typed `|` becomes `\|` and a pasted multi-line text becomes one line — Review Focus 1); a deletion or replacement whose range crosses a cell boundary within one row is clipped to the cell the selection's anchor is in; a selection that spans beyond the table (starts or ends outside it) is **not** filtered — it behaves as in Source (Review Focus 3). Composition transactions are not filtered until composition ends (Review Focus 4).
- Changes that are not user input (remote collab changes, undo/redo, `applyChanges`) are never filtered.
- When an edit makes a table non-editable (a row is no longer one line, counts differ), it falls back to `TableWidget` on the next update, and back again when repaired.

- [ ] **Step 1: Failing tests** (`tableGrid.test.js`, real editor): caret in a cell, type `x` → only that cell's text grows, one change of length 1; type `|` → `\|` in the source; paste `a\nb|c` → `a b\|c`; Tab from the last cell adds a row with the right number of cells and puts the caret in its first; Enter moves down a row keeping the column; Backspace at the start of a cell does nothing (does not eat the pipe); selecting from the paragraph above into a cell and pressing Delete equals the Source-mode result; a composition sequence inside a cell lands intact; a remote-style transaction (`annotations: Transaction.remote.of(true)`) inserting a raw `|` is not escaped; a ragged table is drawn by `TableWidget`, a clean one is not. No-op invariant (Task 7's test) passes on the corpus, which now exercises grid tables. Cost: in `PageEditor.previewCost.realCodeMirror.test.jsx`, a 500-row table stays within the existing budget, and a keystroke in one cell rebuilds decorations only for the viewport (assert via the test's existing meter).
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5:** Commit `feat(rich): tables are an editable grid over their own source lines`.

### Task 14: Row and column controls

**Files:** Create `components/editor/TableControls.jsx` (+ test); Modify `components/PageEditor.jsx` (render it when `currentTable` is non-null, positioned from `view.coordsAtPos(table.from)`), `utils/editorCommands.js` (commands below).

**Interfaces — Consumes:** `currentTable`, Task 11's operations. **Produces:** commands `table-row-above`, `table-row-below`, `table-column-left`, `table-column-right`, `table-delete-row`, `table-delete-column`, `table-move-column-left`, `table-move-column-right`, `table-align-left|center|right|none` (section `Table`; available in the command palette only while the caret is in an editable table), and `TableControls({ onRun, canDeleteRow, canDeleteColumn })` — a small toolbar `[+ row] [+ col] [⋮]` with the rest in the `⋮` menu, keyboard-reachable (`Alt-T` focuses it from inside a table).

Each command applies its operation as **one** `applyChanges` call and then places the caret in the sensible cell (the new row/column's cell in the caret's column/row; after a delete, the neighbouring cell).

- [ ] **Step 1: Failing tests.** `TableControls.test.jsx`: buttons by accessible name; `canDeleteRow={false}` disables "Delete row". Real-editor: with the caret in row 2, column 1, `table-column-right` produces the Task 11 text, the caret is in the new column of row 2, and **one undo restores the exact original text** (Review Focus 5); `table-delete-row` on the header row is a no-op; the commands are absent from the registry when the caret is outside a table.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5:** Commit `feat(rich): table row and column controls`.

### Task 15: Tables in shared sessions, and the browser check

**Files:** Extend `components/PageEditor.interleaving.realCodeMirror.test.jsx` (or a sibling `PageEditor.tableCollab.realCodeMirror.test.jsx` using the same two-client harness); extend `RichEditorIT.java`.

- [ ] **Step 1: Tests (expected to pass if Tasks 11–14 are right; a failure here is a defect to fix in the owning task).** Two clients on one document holding a 3×3 table:
  1. A types in cell (1,0) while B types in cell (2,2), interleaved keystroke by keystroke → both converge to the same text containing both edits; each client's caret is still in its own cell.
  2. A types in cell (1,1) while B runs `table-column-left` at column 0 → converge; A's typed text is in what is now column 2 of row 1; A's caret is in that cell.
  3. A is typing in row 2 while B deletes row 2 → converge, no exception, A's caret is at a valid position inside the table or just after it; a further keystroke from A does not produce a row with the wrong cell count **unless** the same sequence does so in Source mode (compare against the same operations replayed with live mode off) (Review Focus 2).
  4. A is typing in a cell while B deletes the whole table → converge, no exception.
- [ ] **Step 2:** Run; fix any failure at its source.
- [ ] **Step 3:** `RichEditorIT`: in Rich, click a cell, type, Tab to the next, add a column from the controls, save; assert the stored Markdown exactly; reload and assert the reader shows the table with the new column.
- [ ] **Step 4: Phase gate.** `bin/run-tests.sh --parallel 4` green. Commit `test(rich): table grid in shared sessions and in the browser`.

---

# Phase 5 — Source mirror pane

### Task 16: Pane state and the mirror

**Files:** Create `components/editor/SourceMirror.jsx` (+ test); Modify `components/PageEditor.jsx` (`previewOpen` ~line 1008, `togglePreview` ~1054, the pane render ~1980–2034), `utils/editorCommands.js` (one command), the pane's CSS.

**Interfaces — Produces:**
- `PageEditor` state `pane: 'hidden' | 'preview' | 'source'` replaces `previewOpen` (initial `'preview'`, not persisted, exactly as `previewOpen` is today). `shownPane = pane === 'source' && !liveActive ? 'preview' : pane`. Everything that read `previewOpen` reads `shownPane === 'preview'` (embed cache lifecycle, link preview) or `shownPane !== 'hidden'` (layout class).
- `togglePreview` hides the pane, or restores the last non-hidden value.
- A pane header with a two-option control `Preview` | `Source`; `Source` is disabled outside Rich with the title "Shown in Rich mode". New command `pane-source` ("Show Markdown source beside the editor", section View).
- `SourceMirror({ text, line })` — the document's Markdown, read-only, monospace, with line `line` (1-based, the main caret's line) highlighted and scrolled into view when it leaves the visible area. Implement with the existing `CodeEditor` in `readOnly` mode with `livePreview={false}` and no collab extensions, fed `text` on the same debounce the preview uses (`previewSource`). It is `aria-label="Markdown source (read-only)"`. Clicking in it selects text and does nothing else: it must not change the editor's mode or the main editor's selection.

- [ ] **Step 1: Failing tests.** `SourceMirror.test.jsx`: renders the text; `line={3}` marks the third line with the highlight class. `PageEditor.test.jsx`: in Rich, choosing Source shows the mirror and hides the preview; switching the editor to Source mode shows the preview again and re-enabling Rich brings the mirror back; "Toggle preview" hides and restores the last pane; clicking inside the mirror leaves the mode `live` and the main selection unchanged; a legacy page offers no Source pane.
- [ ] **Step 2–4:** fail, implement, pass; `PageEditor.previewCost` unchanged with the pane on Preview.
- [ ] **Step 5: Phase gate.** Gate green. Commit `feat(rich): read-only Markdown source pane beside the Rich editor`.

---

# Phase 6 — Inline marks (spike first; may fall back)

### Task 17: Spike (throwaway)

- [ ] **Step 1:** In a scratch build, make `EmphasisMark`, the strikethrough and highlight marks and `CodeMark` sticky, and try the spec's rules: marks atomic; a caret at the end of a run is placed before the closing mark (types formatted); at the start, before the opening mark (types plain); the toggle command with an empty selection at the end of a run moves the caret past the closing mark and sets a one-shot "outside" flag that the caret rule honours until the next caret move; a deletion that would leave one mark of a pair removes its partner.
- [ ] **Step 2:** Use it for ten minutes of real writing in a browser: bold a word mid-sentence and keep typing plain; extend a bold run; delete across a run boundary; nested `***both***`; bold next to a chip; inline code containing backticks.
- [ ] **Step 3: Decide.** If the edge behaviour is predictable in all six, write the refined rules into Task 18 and proceed. If not, **stop here**: record what failed in the spec's "Inline marks" section, leave inline marks in reveal-at-caret, skip Task 18, and go to Task 19. Show the owner the outcome either way before continuing.

### Task 18: Hidden inline marks

**Files:** Create `utils/livePreview/inlineSticky.js` (+ test); Modify `ranges.js` (`markedInline` passes `{ sticky: STICKY_INLINE }`), `index.js`, `PageEditor.jsx` (`applyFormat` for bold/italic/strike/highlight/code in Rich).

**Interfaces — Produces:** `export const STICKY_INLINE = true` in `inlineSticky.js` — the single switch; with it `false`, behaviour is exactly Phase 5's. `inlineStickyExtension`: the caret-at-edge rule, the one-shot "outside" flag (a `StateField` cleared by any selection change not caused by the toggle), and `balancePairs`, a `transactionFilter` on user deletions that adds the partner mark's range when exactly one mark of a pair falls inside the deleted range.

In Rich, the toggle commands act on tree nodes rather than on adjacent characters: with a selection, wrap or unwrap the selection (splitting a run when the selection is a strict part of it); with an empty selection inside a run at its end, step out; elsewhere, insert an empty pair and place the caret inside.

- [ ] **Step 1: Failing tests** (`inlineSticky.test.js`, real editor), using the rules as refined by Task 17: caret after `bold` in `a **bold** c` then typing `x` → `a **boldx** c`; toggle then `x` → `a **bold**x c`; caret before `bold`, typing → plain text before the run; selecting `ol` inside and toggling bold → `a **b**ol**d** c`; deleting a range covering `ld**` → `a **bo** c` (pair kept balanced) and the same for the opening side; `***both***`, `~~s~~`, `==h==`, `` `code` `` each pass the type-at-end and delete-across cases; composition at a run's end lands inside the run (Review Focus 4); copy of a selection across a run yields the Markdown text; a remote transaction is never altered by `balancePairs`. No-op invariant on the corpus. `linearCost`: 20 000 bold runs, caret moves stay flat. With `STICKY_INLINE = false` (set through a test-only override), Phase 5's `ranges.test.js` expectations hold.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5:** Commit `feat(rich): inline marks stay hidden at the caret`.

### Task 19: Final verification and docs

- [ ] **Step 1:** `RichEditorIT`: insert a Mermaid diagram from the toolbar and see it drawn; toggle Rich → Source → Rich and save with no edits → byte-identical; (if Task 18 shipped) bold a word with the toolbar, keep typing plain, assert the saved source.
- [ ] **Step 2:** Update the editor's user-facing help page in `docs/wikantik-pages/` (find it with `grep -rln "live preview\|Live preview" docs/wikantik-pages`) to describe Rich, Source, the cards, the grid and the source pane. Prod is authoritative for content: note in the commit that the page must be pushed to prod by the owner's usual route.
- [ ] **Step 3:** Update the spec's status line to "implemented", listing anything that fell back.
- [ ] **Step 4:** `bin/run-tests.sh --parallel 4` green; `mvn pmd:check -Pcomplexity-gate` clean (no Java changed beyond the IT, but run it); request the final cross-cutting review (`superpowers:requesting-code-review`).
- [ ] **Step 5:** Commit `docs: Rich editor mode — help page and spec status`.
