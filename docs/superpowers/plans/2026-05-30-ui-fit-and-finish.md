# UI Fit-and-Finish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land 58 small-to-medium UI improvements that close the fit-and-finish gap between the Wikantik SPA and industry-leading products (Notion, Linear, Stripe docs, GitHub).

**Architecture:** Build shared frontend infrastructure first (toast, spinner/skeleton, focus-trap, scroll-lock, portal Modal, date utility, Card/Badge/Chip/Icon primitives, semantic color tokens), then apply and extend it across navigation, reader, editor, search, feedback/modals, consistency refactors, admin, page graph, and micro-interactions. Frontend-only — no backend/API/DB changes.

**Tech Stack:** React 18, Vite, React Router 6, react-markdown + remark/rehype, Vitest + Testing Library, CodeMirror 6 (new, for #19). Source at `wikantik-frontend/`, code under `src/`.

---

## How to read this plan (granularity + TDD policy)

The user's directive: **TDD for any functional change; gentle (visual) verification for non-functional CSS/markup**. Every item below is tagged **[TDD]** or **[Gentle]** and specifies exact files, the test/verification approach, and success criteria.

Tasks are defined at **item granularity** (58 tasks across 10 phases). Each **[TDD]** task is executed with the standard red→green→commit micro-cycle:

1. Write the failing Vitest test (exact assertions given per task).
2. Run it, confirm it fails for the right reason — `cd wikantik-frontend && npx vitest run <file>`.
3. Write the minimal implementation.
4. Run the test, confirm green.
5. Commit, listing files by name, message ends with the item number, e.g. `feat(ui): toast system [#1]`.

Each **[Gentle]** task: implement → run the component's existing render test (and a new one for new presentational components) → `npm run build` → manual visual check in both themes → commit.

Inline code is shown where a signature/contract must be pinned down; full implementations against live files are written at execution time (this is why subagent-driven execution, a fresh agent per task, is the recommended path — see Execution Handoff).

**Per-phase gate:** `cd wikantik-frontend && npm test` (full Vitest suite) + `npm run build`. **Final gate:** `mvn clean install -T 1C -DskipITs` then `mvn clean install -Pintegration-tests -fae` (never `-T` for ITs — port conflicts, per CLAUDE.md).

**Test commands:** all tests `cd wikantik-frontend && npm test`; single file `npx vitest run src/path/to/file.test.js`; coverage `npm run coverage`.

---

## File Structure (what gets created)

New shared layer under `src/components/ui/` and `src/hooks/` / `src/utils/`:

- `src/components/ui/` — `Spinner.jsx`, `Skeleton.jsx`, `ToastProvider.jsx`, `Modal.jsx`, `Card.jsx`, `Badge.jsx`, `Chip.jsx`, `Icon.jsx`, `EmptyState.jsx` (moved from admin), `OverflowMenu.jsx`, `CodeBlock.jsx`, `Breadcrumbs.jsx`, `TableOfContents.jsx`, `EditorToolbar.jsx` (+ a `*.test.jsx` beside each).
- `src/hooks/` — `useToast.js`, `useFocusTrap.js`, `useScrollLock.js`, `useScrollSpy.js`, `useRecentSearches.js`, `useDocumentTitle.js`, `useGlobalHotkeys.js` (+ tests).
- `src/utils/` — `datetime.js`, `headings.js`, `readingTime.js`, `markdownFormat.js`, `highlight.js` (+ tests).
- CSS lives in the existing `src/styles/globals.css` and `src/styles/admin.css` (no new stylesheet).

Each file has one responsibility; primitives are dropped in beside existing code and adopted incrementally so nothing ships half-migrated.

---

## Current State

Strong foundation: token system (`globals.css:5–73`), full dark palette via `[data-theme]`, editorial typography, AAA contrast. Gaps: no toast system; success is implicit and several errors are console-only (`PageView.jsx:132,213,426,436,441`); `:focus-visible` on only two selectors; no `prefers-reduced-motion`; four duplicate date formatters; one native `confirm()` (`AttachmentPanel.jsx:125`); plain `<textarea>` editor (`PageEditor.jsx:286`); no `Cmd+S`; no breadcrumbs/TOC; search `Enter` jumps to the results page instead of opening the focused result (`SearchOverlay.jsx:57–66`); silent 50-result cap (`SearchResultsPage.jsx`).

## What We're NOT Doing

- No backend/API/DB changes. #11 reads verification fields already in the payload; #28 uses existing paging params if present, else client-side load-more over the existing response.
- No token redesign — we *add* `--success`/`--danger`/`--warning` but keep the palette.
- No component-library swap (MUI/Chakra/Radix) — primitives stay hand-rolled.
- No i18n beyond centralizing date formatting.
- No Cytoscape engine rewrite — only its loading/error/a11y shell.

---

## Phase 0: Shared Foundations

Build the infrastructure ~20 later items consume. Nothing user-visible breaks; primitives are adopted in later phases.

### Task 0.1 — Semantic state color tokens  *(supports #40, #11, #43)*  **[Gentle]**
**Files:** Modify `src/styles/globals.css` (`:root` + `[data-theme="dark"]`).
Add `--success`, `--success-bg`, `--danger`, `--danger-bg`, `--warning`, `--warning-bg`, `--focus-ring: var(--accent)`. Match existing hardcoded `#2a8d2a`/`#b13a3a`/`#f9a825`, tuned per theme.
- [ ] Implement; `npm run build`; visually confirm a swatch in each theme.
- [ ] Commit: `style(ui): semantic state color tokens [#0.1]`.

### Task 0.2 — Date/time utility — **#37**  **[TDD]**
**Files:** Create `src/utils/datetime.js`; Test `src/utils/datetime.test.js`.
Exports: `formatRelative(iso, now?)` ("just now" <60s, "Nm ago" <60m, "Nh ago" <24h, "Nd ago" <30d, else `formatDate`), `formatDate(iso)` ("May 30, 2026"), `formatDateTime(iso)`, `formatTime(iso)`. Invalid input returns the input unchanged. `now` injectable for determinism.
- [ ] Step 1 — failing tests: boundary cases (`59s→"just now"`, `90s→"1m ago"`, `2h`, `3d`, `40d→formatDate`), invalid string passthrough, injected `now`.
- [ ] Step 2 — `npx vitest run src/utils/datetime.test.js` → FAIL (module missing).
- [ ] Step 3 — implement.
- [ ] Step 4 — rerun → PASS.
- [ ] Step 5 — commit: `feat(ui): unified datetime utility [#37]`.

### Task 0.3 — Spinner + Skeleton — **#5**  **[Gentle]** (render test)
**Files:** Create `src/components/ui/Spinner.jsx`, `Skeleton.jsx` (+ `.test.jsx`); CSS in `globals.css`.
`Spinner`: sizes `sm|md`, `role="status"`, `aria-label`, keyframe gated by reduced-motion. `Skeleton`: `line|card|table-row`, shimmer gated by reduced-motion.
- [ ] Render test: Spinner exposes `role="status"`; Skeleton renders each variant.
- [ ] `npm run build`; visual both themes.
- [ ] Commit: `feat(ui): Spinner + Skeleton primitives [#5]`.

### Task 0.4 — Toast system — **#1**  **[TDD]**
**Files:** Create `src/components/ui/ToastProvider.jsx`, `src/hooks/useToast.js` (+ tests); CSS in `globals.css`. Mount `<ToastProvider>` at App root (`src/App.jsx`).
`useToast()` → `{ success, error, info }`. Auto-dismiss default 5s; errors sticky until dismissed; dedupe identical consecutive messages; visible stack cap 4; container `role="status"` (info/success) / `role="alert"` (error).
- [ ] Step 1 — failing tests (fake timers): add shows toast; auto-dismiss after 5s; error persists past 5s; dedupe consecutive identical; stack cap 4; manual dismiss removes.
- [ ] Step 2 — vitest → FAIL.
- [ ] Step 3 — implement provider + hook + container.
- [ ] Step 4 — rerun → PASS.
- [ ] Step 5 — commit: `feat(ui): toast notification system [#1]`.

### Task 0.5 — useFocusTrap — **#29**  **[TDD]**
**Files:** Create `src/hooks/useFocusTrap.js` (+ test).
`useFocusTrap(ref, active)`: trap Tab/Shift+Tab among focusable descendants, focus first on activate, restore previously-focused element on deactivate, no-op when inactive.
- [ ] Failing tests: Tab from last → first; Shift+Tab from first → last; restore on deactivate; inactive = no-op.
- [ ] vitest → FAIL → implement → PASS.
- [ ] Commit: `feat(ui): useFocusTrap hook [#29]`.

### Task 0.6 — useScrollLock — **#30**  **[TDD]**
**Files:** Create `src/hooks/useScrollLock.js` (+ test).
Locks `<body>` scroll while active; compensates scrollbar width; reference-counted so nested overlays don't unlock early; restores exact prior style.
- [ ] Failing tests: locks on activate; restores prior `overflow`/padding; ref-count holds until last release.
- [ ] vitest → FAIL → implement → PASS.
- [ ] Commit: `feat(ui): useScrollLock hook [#30]`.

### Task 0.7 — Modal portal shell — **#31, #33** (wraps #29/#30)  **[TDD]**
**Files:** Create `src/components/ui/Modal.jsx` (+ test). Ensure `#modal-root` exists (add to `index.html` or create lazily).
`createPortal` into `#modal-root`; flex-center with `max-height`/scroll (replaces `padding-top:15vh`); wires `useFocusTrap` + `useScrollLock`; Esc + backdrop close; inner click does not; `role="dialog"`, `aria-modal="true"`, `aria-labelledby` prop.
- [ ] Failing tests: Esc → onClose; backdrop click → onClose; inner click → no close; renders into portal; has dialog role.
- [ ] vitest → FAIL → implement → PASS.
- [ ] Commit: `feat(ui): portal Modal shell with focus trap + scroll lock [#31][#33]`.

### Task 0.8 — Card / .surface — **#36**  **[Gentle]**
**Files:** Create `src/components/ui/Card.jsx` (+ test); `.surface` class in `globals.css` (`border + radius + bg-elevated`, `:hover` lift gated by reduced-motion → ties #52).
- [ ] Render test (children + className passthrough); build; visual.
- [ ] Commit: `feat(ui): Card / surface primitive [#36]`.

### Task 0.9 — Badge / Chip — **#41**  **[Gentle]**
**Files:** Create `src/components/ui/Badge.jsx`, `Chip.jsx` (+ tests).
`Badge` variants `success|danger|warning|default` map to 0.1 tokens. `Chip` optional remove button with `aria-label`.
- [ ] Render test per variant + Chip remove fires callback; build; visual.
- [ ] Commit: `feat(ui): Badge + Chip primitives [#41]`.

### Task 0.10 — Icon set — **#16**  **[Gentle]**
**Files:** Create `src/components/ui/Icon.jsx` (+ test).
Named inline-SVG set: edit, trash, comment, search, sun, moon, copy, link, chevron, close, check, warning, more. `aria-hidden` default; `title` sets accessible label; unknown name `console.warn`s, never throws.
- [ ] Render test: named icon renders `<svg>`; unknown warns; no throw. Build; visual.
- [ ] Commit: `feat(ui): Icon component [#16]`.

### Task 0.11 — Promote EmptyState — **#38** (groundwork)  **[Gentle]**
**Files:** Move `src/components/admin/EmptyState.jsx` → `src/components/ui/EmptyState.jsx`; re-export from old path so admin imports don't churn; add optional `icon` prop (ties #55). Update `EmptyState.test.jsx` path.
- [ ] Existing test green after move; build.
- [ ] Commit: `refactor(ui): promote EmptyState to shared ui/ [#38]`.

**Phase 0 gate:** `npm test` green; `npm run build` ok; `npm run coverage` not regressed.

---

## Phase 1: Global CSS Polish — all **[Gentle]**

`src/styles/globals.css` unless noted. Verify each: `npm test` green (no render regressions) + `npm run build` + manual visual (both themes, reduce-motion).

- [ ] **#3 focus-visible sweep** — `:focus-visible { outline:2px solid var(--focus-ring); outline-offset:2px }` on `.sidebar-link`, `.btn`, `.btn-primary`, `.btn-ghost`, `.search-trigger`, and `input/textarea/select`. Commit `[#3]`.
- [ ] **#32 prefers-reduced-motion** — `@media (prefers-reduced-motion: reduce)` neutralizes `fadeIn`, `scaleIn`, `slideUp`, `comment-pulse`, spinner/skeleton shimmer, and all transitions. Commit `[#32]`.
- [ ] **#51 button press** — `.btn:active { transform: translateY(1px) }` (off under reduce-motion). Commit `[#51]`.
- [ ] **#52 hover-lift as CSS** — `.surface:hover`/`.card:hover` shadow + slight translateY (replaces JS handlers removed in #39). Commit `[#52]`.
- [ ] **#35 disabled affordance** — `:disabled,[aria-disabled="true"]{cursor:not-allowed}` + consistent `.btn:disabled` opacity. Commit `[#35]`.
- [ ] **#56 ::selection** — accent-tinted selection via `color-mix`. Commit `[#56]`.
- [ ] **#57 custom scrollbars** — theme-aware thin scrollbars for `.app-sidebar` + editor panes (webkit + Firefox). Commit `[#57]`.
- [ ] **#14 visited links** — distinct `.article-prose a:visited` color. Commit `[#14]`.
- [ ] **#17 anchor scroll offset** — `scroll-margin-top` on `.article-prose :is(h1,h2,h3,h4)` (pairs with #13). Commit `[#17]`.
- [ ] **#34 skip-to-content** — `.skip-link` (visually-hidden-until-focus) + render it atop the layout and add `id="main-content"` to `.app-content` in `src/App.jsx`. Commit `[#34]`.

---

## Phase 2: Navigation & Wayfinding

### Task #6 — aria-current on active sidebar link  **[TDD]**
**Files:** `src/components/Sidebar.jsx` (+ existing `Sidebar.test.jsx`).
- [ ] Failing test: at a route, the matching link has `aria-current="page"`, others don't.
- [ ] FAIL → implement → PASS → commit `[#6]`.

### Task #7 — Breadcrumbs  **[TDD]**
**Files:** Create `src/components/Breadcrumbs.jsx` (+ test); render in `src/components/PageView.jsx` header. Derive from page cluster/canonical metadata on the page object.
- [ ] Failing tests: with cluster → "Home › {cluster} › {title}" with correct hrefs; without cluster → "Home › {title}".
- [ ] FAIL → implement → PASS → commit `[#7]`.

### Task #8 — Table of contents  **[TDD]**
**Files:** Create `src/utils/headings.js` (+ test) — `extractHeadings(markdownOrDom) → [{id,text,level}]` with slugged unique ids; create `src/components/TableOfContents.jsx` (+ test) — sticky right-rail, hidden under editor breakpoint, only when ≥3 headings.
- [ ] Failing tests: extractor ordered + unique-slugged; component renders nested list; hides when <3.
- [ ] FAIL → implement → PASS → commit `[#8]`.

### Task #10 — Scroll-spy  **[TDD]**
**Files:** Create `src/hooks/useScrollSpy.js` (+ test, mock IntersectionObserver); consumed by TOC.
- [ ] Failing test: given mocked intersections, returns topmost visible id.
- [ ] FAIL → implement → PASS → commit `[#10]`.

### Task #9 — Mobile tab discoverability  **[Gentle]**
**Files:** `src/styles/globals.css` (clearer chevron + subtle first-visit pulse gated by reduce-motion), `src/components/Sidebar.jsx` (chevron `<Icon>`).
- [ ] Visual on narrow viewport; commit `[#9]`.

**Phase 2 gate:** `npm test` + build.

---

## Phase 3: Reader Experience

### Task #11 — Verified/confidence chip  **[TDD]**
**Files:** `src/components/PageMeta.jsx` (+ test). Render `<Badge>` from `verified_at`/`confidence` (authoritative→success, provisional→default, stale→warning) + "Verified {formatRelative}" tooltip; no-op when absent.
- [ ] Failing tests: each confidence → correct variant/label; absent → renders nothing. FAIL→impl→PASS→commit `[#11]`.

### Task #12 — Copy-to-clipboard code blocks  **[TDD]**
**Files:** Create `src/components/ui/CodeBlock.jsx` (+ test); register as `code`/`pre` renderer in the ReactMarkdown maps in `PageView.jsx` and `SearchResultsPage.jsx`. Hover-reveal Copy via `navigator.clipboard` (mock in test); transient "Copied ✓".
- [ ] Failing tests: click calls `clipboard.writeText` with block text + shows copied; clipboard rejection → error toast (not silent). FAIL→impl→PASS→commit `[#12]`.

### Task #13 — Heading anchor links  **[TDD]**
**Files:** heading renderers in PageView's ReactMarkdown map; reuse `src/utils/headings.js` slugger.
- [ ] Failing tests: `## Foo Bar` → `id="foo-bar"` + anchor href `#foo-bar`; duplicates get unique suffixes. FAIL→impl→PASS→commit `[#13]`.

### Task #15 — Reading time + word count  **[TDD]**
**Files:** Create `src/utils/readingTime.js` (+ test); display in `PageMeta.jsx`.
- [ ] Failing tests: word count strips frontmatter/code; minutes = ceil(words/200); empty → "0 min". FAIL→impl→PASS→commit `[#15]`.

### Task #16-apply — Emoji → Icon in reader  **[Gentle]**
**Files:** `src/components/PageView.jsx` (✎/🗑/💬 → `<Icon>`); update `PageView.test.jsx` to query by accessible name/test-id.
- [ ] Tests green; build; visual; commit `[#16]`.

(#17 anchor offset already landed in Phase 1; confirm with #13.)

**Phase 3 gate:** `npm test` + build.

---

## Phase 4: Editor

### Task #4 — Cmd/Ctrl+S save  **[TDD]**
**Files:** `src/components/PageEditor.jsx` (+ test).
- [ ] Failing tests: Cmd+S calls save + `preventDefault`; no-op while already saving; success → success toast. FAIL→impl→PASS→commit `[#4]`.

### Task #20 — Unsaved-changes guard  **[TDD]**
**Files:** `PageEditor.jsx` (+ test) + React Router blocker. Dirty → attach `beforeunload` + block in-app nav with the Phase 0 Modal; clean → detach.
- [ ] Failing tests: dirty registers `beforeunload`; clean removes it; in-app nav while dirty triggers guard. FAIL→impl→PASS→commit `[#20]`.

### Task #21 — Draft banner: dismiss + relative time  **[TDD]**
**Files:** `PageEditor.jsx:267` (+ test). `formatRelative(draft.savedAt)`; dismiss (×) clears prompt, keeps draft.
- [ ] Failing tests: banner shows relative time; dismiss hides but draft persists. FAIL→impl→PASS→commit `[#21]`.

### Task #18 — Formatting toolbar  **[TDD]**
**Files:** Create `src/utils/markdownFormat.js` (+ test) and `src/components/ui/EditorToolbar.jsx` (+ test). Buttons bold/italic/link/h2/list/code wrap/toggle selection; shortcuts Cmd+B/I/K.
- [ ] Failing tests: `toggleBold` wraps/unwraps; `insertLink` → `[sel](url)`; selection range updated. FAIL→impl→PASS→commit `[#18]`.

### Task #22 — Drop-zone hint  **[Gentle]**
**Files:** `PageEditor.jsx` (uses existing `useEditorDrop`). Dashed overlay + "Drop images to upload" on dragover.
- [ ] Editor tests green; manual drag; commit `[#22]`.

### Task #19 — Markdown syntax highlighting  **[Gentle]** (integration-heavy; land last)
**Files:** Add CodeMirror 6 deps to `wikantik-frontend/package.json`; replace `<textarea>` at `PageEditor.jsx:286` with a CodeMirror markdown editor preserving value/onChange, autosave, selection helpers (#18), and Cmd+S / Cmd+B/I/K.
- [ ] Behavior tests from #4/#18/#20 stay green (they assert behavior, not the node); add a smoke render test; record `npm run build` size delta (lazy-load editor route if first paint regresses). Commit `[#19]`.

**Phase 4 gate:** `npm test` + build (note bundle delta).

---

## Phase 5: Search

`src/components/SearchOverlay.jsx` / `SearchResultsPage.jsx` (+ tests) unless noted.

### Task #24 — Enter opens focused result  **[TDD]**
- [ ] Failing tests: arrow-down then Enter → navigate to focused result url; Enter with no selection → `/search?q=…`. FAIL→impl→PASS→commit `[#24]`.

### Task #25 — Arrow-key wrap  **[TDD]**
- [ ] Failing tests: Down at last → first; Up at first → last (replaces clamp at `:57–66`). FAIL→impl→PASS→commit `[#25]`.

### Task #23 — Global Cmd/Ctrl+K  **[TDD]**
**Files:** Create `src/hooks/useGlobalHotkeys.js` (+ test); wire in `src/App.jsx`; show "⌘K" hint in `.search-trigger`.
- [ ] Failing tests: Cmd+K on document opens overlay; Esc closes; ignored while typing in a non-search input. FAIL→impl→PASS→commit `[#23]`.

### Task #26 — Recent searches + recently viewed empty state  **[TDD]**
**Files:** Create `src/hooks/useRecentSearches.js` (+ test, localStorage, capped, dedup, recent-first); empty overlay renders recents + `useRecentlyViewed`.
- [ ] Failing tests: record stores/dedupes/caps; empty state lists them; click runs search. FAIL→impl→PASS→commit `[#26]`.

### Task #27 — Highlight matched terms  **[TDD]**
**Files:** Create `src/utils/highlight.js` (+ test); apply in results + overlay snippets. Case-insensitive `<mark>`, regex-escaped, HTML-safe.
- [ ] Failing tests: terms wrapped; special chars escaped; no-match untouched; adjacent terms ok. FAIL→impl→PASS→commit `[#27]`.

### Task #28 — Pagination / load-more  **[TDD]**
**Files:** `SearchResultsPage.jsx`. Replace silent 50-cap with "Load more" (server paging if available, else client page-through) + "Showing X of Y".
- [ ] Failing tests: >page-size renders first page + working Load more; count line correct. FAIL→impl→PASS→commit `[#28]`.

**Phase 5 gate:** `npm test` + build.

---

## Phase 6: Feedback, Modals & A11y Wiring (apply Phase 0)

### Task #1-apply — Route errors/success through toasts  **[TDD]**
**Files:** `src/components/PageView.jsx` — replace the five `console.warn` swallow sites (`:132,213,426,436,441`) with `error` toasts (and success toasts where appropriate); sweep other components for silent user-facing `console.warn`.
- [ ] Failing tests: each simulated failure shows a toast citing a reason (per project norm). FAIL→impl→PASS→commit `[#1-apply]`.

### Task #2 — Replace native confirm()  **[TDD]**
**Files:** `src/components/AttachmentPanel.jsx:125` — inline two-step confirm mirroring `CommentsDrawer.jsx:67–84`.
- [ ] Failing tests: delete shows inline confirm; confirm calls `onDelete`; cancel doesn't; `window.confirm` never called. FAIL→impl→PASS→commit `[#2]`.

### Task #29/#30/#31/#33-apply — Migrate modals to `<Modal>` shell  **[TDD]**
**Files:** `NewArticleModal.jsx`, `LoginForm.jsx`, delete-confirm in `PageView.jsx`/`BlogHome.jsx`, version-conflict modal `PageEditor.jsx:308–341`, `CommentsDrawer.jsx` (drawer variant).
- [ ] Per modal failing tests: Esc closes; focus trapped; body locked while open; focus restored on close; existing content tests stay green. FAIL→impl→PASS→commit per modal `[#29/#30/#31/#33]`.

### Task #5-apply — Spinner/skeleton everywhere  **[Gentle]**
**Files:** `PageView.jsx:333`, `DiffViewer.jsx:50`, admin `.admin-loading` sites, `GraphLoadingFallback` (→ #49). Tests query `role="status"` instead of literal "Loading…".
- [ ] Tests green; build; visual; commit `[#5-apply]`.

### Task #54 — Optimistic UI  **[TDD]**
**Files:** mark-read (`MentionsPage.jsx`), resolve/reopen comment (`PageView.jsx`/`CommentsDrawer.jsx`), mark-verified if surfaced. Apply immediately; roll back + error toast on failure.
- [ ] Failing tests: success updates state before promise resolves; failure reverts + error toast. FAIL→impl→PASS→commit `[#54]`.

### Task #55 — Polish empty states  **[Gentle]**
**Files:** pass `icon` + clear action to `<EmptyState>` at comments/attachments/search/mentions sites.
- [ ] Render tests assert action/label; build; visual; commit `[#55]`.

**Phase 6 gate:** `npm test` + build.

---

## Phase 7: Consistency Refactors (apply Phase 0 primitives)

Refactors guarded by existing tests; behavior unchanged unless noted.

- [ ] **#36-apply** Adopt `<Card>`/`.surface` in `SearchResultsPage.jsx:114` (remove JS hover handlers → CSS #52), `MentionsPage.jsx`, `BlogHome.jsx`/`BlogEntry.jsx`, admin tiles. Tests green; visual. Commit `[#36-apply]`.  **[Gentle]**
- [ ] **#37-apply** Replace `formatWhen` (`MentionsPage.jsx`) + local `formatRelativeTime` (`ProposalReviewQueue.jsx`) + ad-hoc `toLocale*` (`BlogEntry.jsx`, `SearchResultsPage.jsx`, `PageGraphView.jsx`, `PageMeta.jsx`) with `datetime.js`. Update tests asserting old strings; behavior now covered by `datetime.test.js`. Commit `[#37-apply]`.  **[TDD-adjacent]**
- [ ] **#38-apply** Use shared `<EmptyState>` in `SearchResultsPage.jsx:50` + `MentionsPage.jsx` empty branch. Tests green; visual. Commit `[#38-apply]`.  **[Gentle]**
- [ ] **#39** Inline styles → classes: `UserBadge.jsx:36–41`, `Sidebar.jsx:71`, `PersonalZone.jsx:63`, `ProposalReviewQueue.jsx` badge/chip/property renderers (→ `<Badge>`/`<Chip>`), `SearchResultCard`, `PageGraphView.jsx` info banner. Add classes to `globals.css`/`admin.css`. **Manual dark-mode check** (inline styles were a dark-mode bug vector). Commit `[#39]`.  **[Gentle]**
- [ ] **#40** Status colors → tokens: `ProposalReviewQueue.jsx:100` verdict hex + any other hardcoded hex → 0.1 tokens via `<Badge>`. Visual both themes. Commit `[#40]`.  **[Gentle]**
- [ ] **#41-apply** Adopt `<Badge>`/`<Chip>` for admin badges, search/graph chips, mention author markers. Tests green; visual. Commit `[#41-apply]`.  **[Gentle]**

**Phase 7 gate:** `npm test` green; grep shows no remaining local date formatters or hardcoded status hex.

---

## Phase 8: Admin Panel

### Task #42 — Sort-direction carets  **[TDD]**
**Files:** admin table header component (sortable headers, `admin.css:77`) + test. Active column ▲/▼ (`<Icon>`); inactive faint neutral.
- [ ] Failing tests: header click sets caret + sort state; toggle flips; only one active column. FAIL→impl→PASS→commit `[#42]`.

### Task #43 — Style .admin-error  **[Gentle]**
**Files:** `src/styles/admin.css` — danger-token banner matching `.error-banner`.
- [ ] Visual; commit `[#43]`.

### Task #44 — Table action overflow menu  **[TDD]**
**Files:** Create `src/components/ui/OverflowMenu.jsx` (+ test); apply to `.admin-cell-actions` when >2 actions.
- [ ] Failing tests: >2 actions collapse into menu; open/close; choosing invokes action; Esc/outside closes. FAIL→impl→PASS→commit `[#44]`.

### Task #45 — Remove dead CSS  **[Gentle]**
**Files:** `admin.css` — delete unused `.admin-section-help` + unused density variants (grep-confirm no refs).
- [ ] Grep clean; build; commit `[#45]`.

**Phase 8 gate:** `npm test` + build.

---

## Phase 9: Page Graph (`src/components/pagegraph/*`)

### Task #47 — Canvas a11y + text fallback  **[TDD]**
**Files:** `PageGraphView.jsx`/`GraphCanvas.jsx` (+ test). `role="img"` + computed `aria-label`/visually-hidden summary ("142 pages, 380 links; N clusters").
- [ ] Failing test: summary reflects node/edge counts. FAIL→impl→PASS→commit `[#47]`.

### Task #46 — Cluster patterns/shapes  **[Gentle]**
**Files:** `graph-style.js`/`GraphLegend.jsx`/`FilterPanel.jsx` — encode cluster by node shape (ellipse/rect/diamond/hexagon) + color; legend shows shape.
- [ ] Visual; legend matches nodes; commit `[#46]`.

### Task #48 — Responsive legend/zoom  **[Gentle]**
**Files:** `graph.css` (`.graph-bottom-right`) — reposition/collapse under mobile breakpoint.
- [ ] Visual narrow viewport; commit `[#48]`.

### Task #49 — Graph loading spinner  **[Gentle]**
**Files:** `GraphLoadingFallback.jsx` — `<Spinner>` + keep slow hint.
- [ ] Render test `role="status"`; visual; commit `[#49]`.

**Phase 9 gate:** `npm test` + build.

---

## Phase 10: Micro-interactions & Delight

### Task #50 — Animated theme toggle  **[Gentle]**
**Files:** `Sidebar.jsx` theme button → `<Icon sun/moon>` morph/crossfade synced to the 300ms body transition; reduce-motion = instant; add `aria-label` (fixes `Sidebar.jsx:73`).
- [ ] Render test asserts `aria-label`; visual; commit `[#50]`.

### Task #53 — Stagger-in lists  **[Gentle]**
**Files:** `MentionsPage.jsx`, `SearchResultsPage.jsx` — translateY/opacity entrance, per-item delay, gated by reduce-motion.
- [ ] Visual; reduce-motion = none; commit `[#53]`.

### Task #58 — Document title per route  **[TDD]**
**Files:** Create `src/hooks/useDocumentTitle.js` (+ test); apply in `PageView` (page title), search ("Search: q"), admin, blog. (SEO server `<title>` stays.)
- [ ] Failing test: mount sets `document.title`; route change updates/restores. FAIL→impl→PASS→commit `[#58]`.

(#51/#52/#56/#57 landed in Phase 1 — confirm they read well now that cards/buttons are pervasive.)

**Phase 10 gate:** `npm test` + build.

---

## Testing Strategy

- **[TDD] items:** failing Vitest test first (fake timers for toast/debounce; mock `navigator.clipboard`, `IntersectionObserver`, `localStorage`; `user-event` for keyboard), then implement to green.
- **[Gentle] items:** new presentational components get one render test; CSS-only edits rely on existing render tests staying green + build + manual visual.
- **Integration:** changes are frontend-only; run `mvn clean install -Pintegration-tests -fae` once at the very end to confirm WAR packaging/SPA routing intact. Never `-T` for ITs.
- **Manual per phase** (`npm run dev`): light/dark + OS reduce-motion pass; keyboard-only pass (Tab rings, focus traps, skip link, Esc); narrow-viewport pass (nav, graph, tables, modals).

## Performance Considerations
- CodeMirror (#19) is the only meaningful bundle add — record `npm run build` delta; lazy-load the editor route if first paint regresses.
- Toasts/skeletons/IntersectionObserver negligible. Reduce-motion lowers work for sensitive users.

## Migration Notes
- No DB migrations (frontend-only). `EmptyState` moves to `src/components/ui/` with an admin re-export to keep the diff small.

## References
- Audit source: this session's three-part component audit.
- Mirror existing patterns: inline confirm (`CommentsDrawer.jsx:67–84`), version-conflict modal (`PageEditor.jsx:308–341`), token system (`globals.css:5–73`).
- Project rules (CLAUDE.md): TDD-first; never swallow exceptions / always surface a reason; stage files by name (no `git add -A`); commit per tested change; full IT before final commit.

## Overall Success Criteria
- [ ] All 58 items implemented (every phase checklist complete)
- [ ] `cd wikantik-frontend && npm test` green; coverage ≥ prior (target 90%+)
- [ ] `mvn clean install -T 1C -DskipITs` green (WAR + vite build)
- [ ] `mvn clean install -Pintegration-tests -fae` green
- [ ] Manual a11y pass (keyboard, reduce-motion, dark mode) clean
- [ ] No remaining: native `confirm()`, console-only user-facing errors, duplicate date formatters, hardcoded status hex, emoji action glyphs
```
