# Live Preview Completion — Design

**Status:** approved scope (2026-11-01); builder-checked; ready to plan.
**Area:** `wikantik-frontend` editor (CodeMirror 6 live preview) plus one small REST addition.

## 1. Intent

The page editor already has an Obsidian-style *live preview* mode (Mod+E, toolbar, command palette): the
Markdown source stays the document, and decorations hide markup and draw widgets everywhere except the
lines the caret is on. It covers headings, emphasis, links, wikilinks, images, page embeds, math,
callouts, task checkboxes, rules, code fences, Mermaid/PlantUML drawings and block-id chips.

This work finishes it so it can be the editor most people use — Obsidian users first (it is how
Obsidian edits), and everyone else, because a page full of pipes, `[{…}]` lines and `<url>`s is the
main thing that makes the editor feel like a code editor.

Because both modes edit the same text, switching is free and lossless. That property is
**load-bearing** and must survive this work: no feature here may change the document except through an
explicit user edit.

### Goals
1. The editor mode is a **per-user preference**, stored server-side, defaulting to **live** for users who
   have never chosen.
2. **Tables** render as tables when the caret is outside them.
3. **Plugin, variable and access-rule markup** (`[{…}]`) renders as compact labelled chips.
4. **Less common Markdown** renders: autolinks and bare URLs, reference-style links, footnotes,
   backslash escapes, setext headings; HTML blocks get a distinct "markup" look.

### Non-goals
- A ProseMirror / rich-text mode. The preference is a string enum so a third value can be added later;
  nothing else is built for it now.
- Table editing UI (add row/column, Tab between cells, column resize). Editing a table = caret into it,
  which reveals the source.
- Rendering raw HTML blocks or inline HTML as HTML.
- Phase 2–4 syntax of the Obsidian program (`==highlight==`, `%% %%`, inline `#tags`) — each of those
  phases adds its own live-preview support.
- Frontmatter in live preview: the CodeMirror buffer holds the **body only** (the structured
  FrontmatterEditor owns frontmatter — `PageEditor.jsx` comment at the body/metadata split), so there is
  nothing to render. *(Dropped from the audit list after verification.)*

### Anti-goals (would be a failure even if it "works")
- Any change to the saved text caused by toggling modes, opening a page, or moving the caret.
- Rendering page-authored HTML into the editor DOM (an XSS path). Widgets build DOM with
  `textContent`; the only `innerHTML` stays the existing KaTeX output and server-sanitised embed HTML.
- A regression in typing latency on large pages (the incremental block-candidate machinery and the
  benches in `src/bench/` must keep holding).
- Losing the existing per-browser behaviour for anonymous or offline use.

## 2. Editor-mode preference

### Storage
- A **user-profile attribute**, key `wikantik.editor.mode`, value `"source"` or `"live"`. Profile
  attributes already persist in `users.attributes` (serialized map, `JdbcUserProfileRowMapper`), so
  **no migration**.
- Absent attribute = "never chose".

### REST (`AuthResource`, `/api/auth/profile`)
- `GET` adds `"editorMode": "source" | "live" | null`.
- `PUT` accepts an optional `"editorMode"`. Accepted values: `"source"`, `"live"`. Anything else → 400
  `"editorMode must be 'source' or 'live'"`, nothing saved. Absent → unchanged.
- Only this one attribute is exposed; the attribute map is never serialised wholesale (it holds
  `sso.subject` and similar).
- A preference-only `PUT` must not alter the password hash, the display name, or any other field
  (test it — including an SSO user whose password is NULL).

### Frontend resolution (`useEditorMode`)
Order, first hit wins:
1. **Server preference** — for an authenticated user, fetched once per editor mount via
   `api.getProfile()`.
2. **This browser's stored choice** — `localStorage['wikantik.editor.mode']` (existing key; written only
   after an explicit toggle, as today).
3. **Default: `live`.**

Rules:
- First paint uses (2) or (3) immediately; when (1) arrives and differs, the mode switches once. No
  flicker loop, no second fetch.
- A toggle updates state and localStorage immediately, and for an authenticated user PUTs
  `{editorMode}`. A failed PUT logs `console.warn` with context and keeps the local choice (no toast —
  the toggle still worked for this session).
- Profile fetch failure (or 401) → fall through to (2)/(3) with a `console.warn`.
- Legacy JSPWiki-syntax pages stay forced to source (existing `isLegacyWiki` rule) and **do not** write
  the preference.
- The hook returns the same `[mode, toggle]` shape it does today, so `PageEditor` call sites do not
  change.

**Ruling — default `live`:** Obsidian's default is live preview, and the goal is that most users never
see raw markup. Users who already toggled have a localStorage value and keep it; the change only affects
users who never chose. Cost if wrong: a one-line default change.

### Preferences page
`UserPreferencesPage.jsx` gains an **Editor** section: a select "Default editing mode" with
"Live preview" / "Source", saved through the existing profile update. It shows the server value
(or "Live preview" when null).

## 3. Tables

- **Which tables:** a GFM `Table` node that is a **direct child of the document** (same rule as fences
  and display math — nested tables in lists/quotes stay source).
- **When:** shown as a block widget when **no line of the table is active**; a caret or selection on any
  table line reveals the whole table's source.
- **Widget:** an HTML `<table>` with `<thead>`/`<tbody>`, column alignment taken from the delimiter row
  (`:--`, `:-:`, `--:`), class `cm-lp-table` styled like the reader's tables.
- **Cell content:** inline Markdown rendered by a new **textContent-only mdast→DOM inline renderer**
  (no new npm dependency): parse the cell with the existing `unified` + `remark-parse` + `remark-gfm` +
  `remark-math` and the project's wikilink remark plugin; render `text`, `emphasis`, `strong`, `delete`,
  `inlineCode`, `link` (text only, styled as a link, not navigable), wikilinks (alias or target text,
  styled as a link), `image` (alt text in a muted span), `inlineMath` (KaTeX through the existing cached
  path), `break`; anything else → its source text. Escaped pipes `\|` render as `|`.
- **Interaction:** a mousedown on a row puts the caret at the start of **that row's source line** (the
  header row → the header line), which reveals the table. Ctrl/Cmd-click on a link inside the table does
  nothing special (it is not a navigable link in the widget).
- **Perf:** `Table` joins the block-candidate set used by `blockCandidates`/`updateBlockCandidates`, so
  the incremental update rules apply unchanged. Widget `eq` compares the table's source text.
- **Must keep:** the inline pass still skips table ranges (the existing `SKIP` entry), so nothing inside
  a table gets double-decorated.

## 4. Plugin, variable and access-rule chips

Applies to the existing `[{…}]` matches in the line pass (outside code, and not overlapping a claimed
wikilink/math range). On an inactive line the source is replaced by an inline chip widget; on the
caret's line it stays source (current tint behaviour).

| Source | Chip text | Class |
|--------|-----------|-------|
| `[{ALLOW view Admin,Editors}]` | lock icon + `view: Admin, Editors` | `cm-lp-chip cm-lp-chip-acl` |
| `[{DENY edit Guest}]` | lock icon + `deny edit: Guest` | `cm-lp-chip cm-lp-chip-acl` |
| `[{$username}]` | `$username` | `cm-lp-chip cm-lp-chip-var` |
| `[{TableOfContents title='x'}]`, `[{INSERT com.foo.Bar …}]`, `[{Bar}]()` | `⚙ TableOfContents` / `⚙ Bar` (plugin name only: drop a leading `INSERT`, keep the last `.`-segment) | `cm-lp-chip cm-lp-chip-plugin` |
| `[{SET name='v'}]` and anything else | the inner text, truncated to 40 chars | `cm-lp-chip` |

- The chip's `title` attribute is the full source text.
- ACL keyword match (`ALLOW`/`DENY`) is case-insensitive, consistent with how `AclDirectives` reads
  them; the chip is presentation only and decides nothing.
- Mousedown on a chip places the caret inside the source (revealing it).

## 5. Less common Markdown

All decorations follow the existing rule: hidden/replaced only on inactive lines; a construct cut by a
claimed wikilink/math range is dropped whole.

1. **Autolinks** `<https://x>` / `<a@b.c>` (Lezer `Autolink`): hide `<` and `>`, style the URL as a link.
   **Bare URLs** (GFM `URL` node outside a `Link`/`Image`/`Autolink`): style as a link.
2. **Reference-style links** `[text][label]`, `[text][]`, `[text]`: styled as a link — hide the brackets
   and the `[label]` part, keep the text — **only when the document contains a matching definition**
   (`[label]: …`, CommonMark label matching: case-insensitive, internal whitespace collapsed). No
   definition → untouched source (matches the reader, which renders it as literal text). Definition lines
   stay source with a muted line class `cm-lp-linkdef`.
3. **Footnotes:** a reference `[^id]` (not at line start followed by `:`) → a superscript chip showing
   `id` (`cm-lp-footnote-ref`). A definition line `[^id]: text` → the `[^id]:` label is replaced by a
   muted `id:` chip (`cm-lp-footnote-def`). Detected by regex in the line pass (Lezer has no footnote
   node), skipping excluded/claimed ranges. Not to be confused with block ids `^id` (no brackets) or
   `[[#^id]]` (claimed by wikilinks first).
4. **Backslash escapes** (Lezer `Escape`): hide the backslash.
5. **Setext headings** (`SetextHeading1/2`): the text line(s) get the matching `cm-lp-h1`/`cm-lp-h2`
   line class; the underline line gets `cm-lp-setext-underline` (muted, small), its characters left in
   place.
6. **HTML blocks** (`HTMLBlock`, already skipped by the inline pass): each line gets `cm-lp-html`
   (monospace, muted). Never rendered.

## 6. Testing

Frontend coverage must not drop (project goal ≥ 90% lines; currently ~92%).

- **Unit (vitest, happy-dom):** spec-level tests in `ranges.test.js` for every construct (active vs
  inactive line, nested vs top-level, cut-by-wikilink); widget tests in `widgets.test.js` (DOM shape,
  `eq`, no `innerHTML` for cell text — assert a `<script>`/`<img onerror>` cell renders as text);
  incremental-update equivalence for tables in `ranges.incremental.test.js`; property test
  (`plugin.property.test.js`) still passes with tables in its generator.
- **Hook:** `useEditorMode.test.js` covers the resolution order, single switch on late server value,
  PUT on toggle when authenticated, no PUT when anonymous, warn-and-keep on PUT failure, profile-fetch
  failure fallback, legacy pages not writing.
- **Preferences page:** select renders server value, saves `editorMode`.
- **Java:** `AuthResource` tests for GET/PUT `editorMode`, invalid value 400, absent = unchanged,
  preference-only save keeps password hash and fullName, SSO (NULL password) user.
- **Round-trip invariant:** a test that toggles source→live→source on a fixture page containing every
  construct and asserts the document text is byte-identical and no transaction changed it.
- **Selenide IT:** extend the editor ITs (`EditorInsertHelpersIT` / `EditWikiPage` page object): open a
  page with a table and an ACL line in live mode, assert the table widget and chip render, move the caret
  into the table and assert source appears; toggle mode, reload, assert the mode persisted (server-side).
- **Bench:** run `src/bench/livePreview.bench.js` before/after; no material regression on the large-page
  case.

## 7. Docs

- `docs/wikantik-pages/EditPageHelp.md`: a short "Live preview" section (what it is, Mod+E, the
  preference, that editing a table/chip = put the caret in it).
- Sync the shipped default copy under `wikantik-wikipages/` (closes the deferred item from the
  block-references work).

## 8. Rulings from the builder check (2026-11-01)

1. **Existing tests pin source.** The new `live` default applies to everyone, including fresh test
   browsers. Every existing editor test (vitest `PageEditor.*`, Selenide editor ITs and the `EditWikiPage`
   page object) that assumes source mode pins it explicitly (localStorage `source` before the editor
   mounts; the IT user has no server preference). Tests that asserted "localStorage stays null after
   mount" keep holding — mounting still never writes. New tests cover live.
2. **Auth state comes from the existing `useAuth` context** (`src/hooks/useAuth.jsx`). The profile is
   fetched only when the user is authenticated — no guaranteed 401 for anonymous editors.
3. **Precedence + one-time migration.** Server value non-null → it wins, and localStorage is updated to
   match. Server value null **and** an explicit localStorage choice exists → that choice is PUT to the
   server once (migrates users who toggled before this change). Server null and nothing local → `live`,
   nothing written. A PUT that failed is simply lost; the next mount shows the server's value. Accepted.
4. **Tables are modelled exactly on fences / display math** (whole-line block replace, same reveal rule,
   same incremental machinery). A table Lezer does not parse as `Table` (bad delimiter row, mid-edit)
   stays source.
5. **Chips replace the `cm-lp-plugin` tint on inactive lines**; the caret's line keeps the tint. Each
   `[{…}]` match is its own chip, wherever on the line — a line of three rules shows three chips. The
   existing access-rule/block-marker logic is about *block-id* markers; those chips are marks on the
   `^id` text and never overlap a `[{…}]` range, so both coexist unchanged. ACL principal lists are shown
   re-joined with `", "` (presentation only).
6. **Reference-link definitions:** a label set computed lazily — only when a visible line holds a
   possible reference link — by walking the syntax tree for `LinkReference` nodes without descending into
   leaf blocks (`referenceDefs`). Once something has asked for it, the definition list is carried through
   edits by the `referenceDefsField` StateField: `updateReferenceDefs` re-walks only the top-level window
   around each change (the same window as `updateBlockCandidates`), keeps the definitions before it and maps
   those after it — equal to a full walk by construction, and tested against one. A partial old or new parse
   tree falls back to the full walk, and a tree-only update (the background parser) drops the field so the
   next request walks afresh; that walk is memoised per (document, syntax tree). The label `Set` is reused
   while the labels are unchanged. Typing a definition re-decorates because the label set changes.
7. **PUT `editorMode: null`** clears the preference (removes the attribute). Any other non-string or
   unknown value → 400. Not exposed on admin or user-list surfaces.
8. **Footnotes are rendered by both readers** — server flexmark (`FootnoteExtension` in
   `MarkdownDocument`) and client remark-gfm — so footnote chips represent the page faithfully.
9. **Docs:** `docs/wikantik-pages/EditPageHelp.md` is the source of truth; copy it to
   `wikantik-wikipages/en/src/main/resources/EditPageHelp.md`, preserving any frontmatter differences the
   shipped copy needs (check with a diff before overwriting).

## 9. Left to the builder
Exact CSS values (match the reader's table/link styles and the existing `cm-lp-*` tokens; both themes),
chip icon (CSS, not an emoji font dependency, unless the existing chips already use text glyphs), test
fixture contents, and function/file decomposition inside `src/utils/livePreview/` (a new
`tableWidget.js` / `inlineCells.js` is fine).
