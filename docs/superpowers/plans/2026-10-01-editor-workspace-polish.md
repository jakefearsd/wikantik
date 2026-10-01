# Editor Workspace Polish Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix the two defects and the visual issues found in the 2026-10-01 browser pass of the editor workspace, so every new surface looks finished in light and dark themes and at narrow widths.

**Architecture:** Frontend-only except Task 2 (the page list gains `title`, and word-start fuzzy ranking in `PageTitleIndex`). All styling uses existing theme tokens, defined in `wikantik-frontend/src/styles/globals.css` under `:root` and `[data-theme="dark"]`. No new runtime dependencies: icons are inline SVG or CSS masks.

**Spec:** `docs/superpowers/specs/2026-09-30-editor-workspace-design.md`. This plan refines its presentation and changes no behaviour except where a task says so.

## Global Constraints

- Every colour, background and border uses a token defined in BOTH themes. `src/styles/tokens.test.js` must stay green; extend it if you add a token.
- No new npm dependencies. Icons are inline SVG components or CSS `mask-image` data URIs coloured with `currentColor`/tokens.
- Server and preview callout parity must hold: `utils/__fixtures__/callouts.json` and both parity tests unchanged and green. Icon changes are CSS-only.
- Every changed component keeps or gains tests; `npx vitest run` + `npm run lint` clean; Java changes keep `mvn test` green for touched classes; `mvn pmd:check -Pcomplexity-gate` stays green, with no baseline additions.
- Never swallow exceptions. Stage files by name. Commit trailer exactly:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg
  ```
- Visual work: before committing, run `cd wikantik-frontend && npm run build` to prove the CSS compiles. The controller does the live visual pass after each task, so describe the intended visuals precisely in the report.

---

### Task 1: Defects — missing-page preview on the read view; double prompt after "Leave without saving"

**Files:** `src/hooks/useLinkPreview.js` (+ test), `src/navigation/navigationGuard.js`, `src/navigation/NavigationGuardProvider.jsx` (+ test), `src/components/PageEditor.jsx` (+ test)

- **D1:** The server renders missing-page links as `<a class="createpage" href="/edit/Name">`, and `previewTargetOf` returns null for `/edit/…`, so the read view never shows the "Not created yet" card. Fix: treat `/edit/{Name}` (base-path aware, decoded with `safeDecode`) as a preview target, always marked `missing: true` when the anchor has class `createpage`. A bare `/edit/X` link without `createpage` gets no card (it's an edit link, not a page reference). Tests: a `createpage` `/edit/Nope` anchor gives "Not created yet" with no fetch; a plain `/edit/X` anchor gives no card.
- **D2:** After the guard dialog's "Leave without saving" on a non-SPA link (`window.location.assign`), the editor's still-registered `beforeunload` handler raises the browser's native "Leave site?" prompt, so the user is asked twice. Fix: `navigationGuard.js` exports `markLeaving()` / `isLeaving()`, a module flag set immediately before `location.assign`. `PageEditor`'s beforeunload handler returns early when `isLeaving()`. Tests: the provider calls `markLeaving` before `location.assign` (spy on assign); the PageEditor beforeunload handler doesn't `preventDefault` when the flag is set.
- Commit: `fix(editor): read-view missing-page previews; no second prompt after Leave without saving`

### Task 2: Quick overlay — titles, full-width single highlight, key hints, sections, word-start fuzzy

**Files:** server `wikantik-rest/.../PageListResource.java` (+ test), `wikantik-main/.../pagegraph/spine/PageTitleIndex.java` (+ test); client `src/components/QuickOverlay.jsx` (+ test), `src/utils/fuzzy.js` (+ test), new `src/utils/keyHints.js` (+ test), `src/styles/globals.css`

- **Titles:** `GET /api/pages` entries gain `title` when the structural index has a descriptor whose title differs from the slug. Source it from the same `loadSpineMeta` sitemap pass that already supplies `cluster`; no extra queries. In the overlay, page and full-text rows show the title as the primary text and the page name below it in small muted text. When `title` is absent, use the de-CamelCased name: a client `beautify(name)` that splits CamelCase and digits the same way `TextUtil.beautifyString` does; test the cases. Recently-viewed rows already carry `title`.
- **Single, full-width highlight:**
  - Rows are full-width flex rows (`width: 100%`, `text-align: left`).
  - Only `.focused` paints a background. Remove the `:hover` background inside the quick overlay; mouse movement already moves focus via `onMouseEnter`.
  - Use the selection token (`--accent` at low alpha via `color-mix`, or an existing selection token) so light and dark both read clearly.
- **Sections:**
  - With a page query, show small uppercase muted headers: "Pages" (when there are page rows), "Full-text matches" (when there are full-text rows), "Commands" in command mode, and "Recent" for the empty query.
  - A thin divider separates the "Search full text…" and "Create page…" action rows from the results.
  - The action rows get small inline-SVG icons: magnifier and plus. Replace the 🔍 emoji on full-text rows with the same SVG magnifier.
  - Headers aren't selectable and aren't `quick-row`s; keep every existing test id.
- **Key hints:** `formatKeys('Mod-Shift-p')` gives `Ctrl+Shift+P` on non-Mac and `⌘⇧P` on Mac (detect with `navigator.platform`/`userAgentData`, injectable for tests). Map `Mod`, `Alt`, `Shift`, `Ctrl`, and brackets/letters (upper-case single letters). Render the hint in a `<kbd>` styled like the existing `search-view-all-kbd`. Commands and the toolbar's `title` tooltips use it.
- **Word-start fuzzy** (fixes "bond" matching BackgroundJobProcessing):
  - The subsequence tier only matches when each query character either continues a matched run or starts a word. A word start is the start of a CamelCase segment, the character after a space, hyphen, underscore or `/`, or the first character.
  - Apply this both server-side (`PageTitleIndex.isSubsequence` becomes word-start-aware; tokenise keys BEFORE lower-casing so CamelCase boundaries survive) and client-side (`fuzzyRank` for commands).
  - Keep the `lcifi` → LowCostIndexFundInvesting test.
  - Add "bond" must NOT match `BackgroundJobProcessing` / `BasicsOfCompoundInterest`.
  - "togsid" must match "Toggle sidebar".
- Commit: `feat(overlay): titles, sections, single full-width highlight, readable key hints, word-start fuzzy`

### Task 3: Editor chrome — themed completion popups, fold markers, callout markers in source

**Files:** `src/components/CodeEditor.jsx` (+ test), `src/utils/markdownFold.js` (+ test), new `src/utils/editorTheme.js` (+ test if logic), `src/styles/globals.css`

- **Themed autocomplete** (the slash menu and wiki-link completion share `.cm-tooltip-autocomplete`):
  - UI font, not monospace; 0.85rem; padding 4px 0; radius `--radius-md`; border `--border`; background `--bg-elevated`; shadow `--shadow-strong`.
  - Selected row: `aria-selected` background and text from tokens, readable in BOTH themes. This fixes the near-invisible dark selection.
  - Detail text (key hints) is right-aligned and muted.
  - Do it with an `EditorView.theme` (or `baseTheme`) keyed on `&light` / `&dark`, or CSS under `.cm-tooltip-autocomplete` in `globals.css` using tokens. Make sure it beats the default theme's specificity.
  - Slash items get a small leading icon or glyph per kind (heading H1/H2/H3, callout, table, code, math, rule, image, link) via a `type`/`section` mapped to CodeMirror's completion `type`, styled with `.cm-completionIcon-*`, or render with `addToOptions` if simpler.
- **Fold markers only where useful:**
  - The gutter offers folds ONLY for ATX headings, the leading frontmatter block, and fenced code blocks; no markers on paragraphs, blockquotes or lists. Implement by replacing the markdown language's node-based folding for other node types, e.g. a custom `foldService` plus disabling `foldNodeProp` folding for Paragraph/Blockquote/List via `markdownLanguage.configure`, or by filtering in a custom `foldGutter({ markerDOM })` that checks the line.
  - The heading fold range must still end before the next heading of the same or higher level.
  - Tests use `EditorState` + `foldable(state, lineFrom, lineTo)`: a paragraph line, a blockquote line and a list line return null; a heading, frontmatter and a fence return a range.
- **Fold placeholder:** `.cm-foldPlaceholder` gets a token-coloured pill (`--bg-elevated`, `--border`, muted text, `0 6px` padding, radius) showing "⋯". It must be clearly visible in both themes.
- **Callout markers in the source:** `[!type]` plus an optional fold marker at the start of a blockquote line is currently styled as a link (underlined, link colour). Add a small ViewPlugin decoration that marks it `cm-callout-marker`, a muted, non-underlined, semi-bold monospace pill-ish style tinted with the callout's style colour token (`--callout-<style>`, via the same alias map as `remarkCallouts.styleOf`). It must override the link styling. Test the range detection as a pure function.
- Commit: `feat(editor): themed completion popups, heading-only fold markers, visible fold placeholder, callout markers in source`

### Task 4: Dialog, rail, preview card, callout icons, dark-mode frontmatter form

**Files:** `src/navigation/NavigationGuardProvider.jsx`, `src/components/editor/UnlinkedMentionsPanel.jsx` (+ test), `src/components/LinkPreviewCard.jsx` (+ test), `src/styles/globals.css`, `src/styles/article.css`, and the frontmatter form styles wherever the `.fm-*` input rules live (grep `-a` in `src/styles/*.css`)

- **Guard dialog buttons:** "Stay" is a secondary button and "Leave without saving" a danger button. Use the app's global button classes if they exist in `globals.css`; `.btn` currently lives only in `admin.css`, which isn't loaded outside admin. Otherwise add small global `.btn`, `.btn-secondary` and `.btn-danger` styles from tokens. Right-align the actions with a gap, give the title and body proper spacing, and make Stay the autofocused default. Check both themes.
- **Unlinked-mention rows:**
  - Layout per row: line 1 is the phrase in quotes (semi-bold, `--text`) followed by "→ Title" as a muted link-coloured span. It may wrap as a unit; no orphaned arrow.
  - Line 2 is the context: a small muted single line with ellipsis and the line number as a muted prefix chip. It is clickable, with hover underline.
  - Line 3 holds the actions: real small buttons. Link is subtle-primary (accent border and text); Ignore is a ghost button. Both have visible borders in BOTH themes, a focus ring, and comfortable hit targets (≥24px tall).
  - Rows are separated by a hairline border with consistent padding.
- **Preview card:**
  - Show the cluster prettified. `index-fund-investing` becomes "Index Fund Investing"; `parent/child` becomes "Parent › Child".
  - Remove the stray blank spacing: render cluster, summary and excerpt elements only when non-empty, with no empty wrappers, and use consistent `gap` instead of margins.
  - Use the type badge pill style.
  - Test: no empty element nodes for a payload without a cluster.
- **Callout icons:** replace the text glyphs with CSS `mask-image` SVG icons, Lucide-style simple strokes, inlined as data URIs, `background-color: var(--c)`, 1em square. One icon per style: note (pencil), abstract (clipboard-list), info (info circle), todo (check-circle-2), tip (flame), success (check), question (help-circle), warning (alert-triangle), failure (x), danger (zap), bug (bug), example (list), quote (quote). This is CSS only in `article.css`; the server HTML is unchanged.
- **Dark-mode frontmatter form:** the Type, Status and Summary inputs (and Title, Tags, Cluster) render with a white background and light text in dark mode. Style all `.fm-*` inputs, selects, textareas, tag inputs and comboboxes with `--bg` or `--bg-elevated` backgrounds, `--text` colour, `--border` borders and a placeholder colour from `--text-muted` in BOTH themes, so dark mode is readable.
- Commit: `style(editor): real dialog buttons, clearer mention rows, tidy preview card, SVG callout icons, readable dark frontmatter form`

---

## Final verification (controller)

- Full `npx vitest run`, lint, `bin/run-tests.sh --parallel 4`, and the PMD gate.
- Redeploy locally, then a live browser pass with screenshots of every touched surface, in light and dark, plus headless narrow screenshots of the read view. Each visual item is checked off against this plan.
