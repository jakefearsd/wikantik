# Editor Quality-of-Life — Design

**Status:** approved design, not yet implemented (2026-09-30)
**Scope:** Tier 1 of the Obsidian editing-gap review plus the editor side rail, and six defect
repairs found during that review. Every item is a general improvement for any user of the existing
wiki, independent of any Obsidian-compatibility direction.
**Explicitly out of scope:** rename-path defect (referrer rewrites skip save validators and search
reindex — separate follow-up), inline live preview, templates, command palette / slash commands,
Mermaid, `#heading`-existence checking on link targets, 3-way merge on conflict.

## 1. Problem

The editor is a competent CodeMirror 6 source editor with a side-by-side react-markdown preview,
but the most frequent authoring actions carry avoidable friction and the preview diverges from the
saved page:

- Images cannot be pasted; OS file drops are a no-op although the editor shows "Drop images to
  upload" on every drag.
- `[[` link completion works from a one-time `listPages({limit:1000})` snapshot. The list endpoint
  caps `limit` at 1000 and sorts alphabetically, so on a corpus of >1000 pages the tail of the
  alphabet is never offered; the fetch error is swallowed silently. No `](` or `#heading` completion.
- The preview (react-markdown) and the page view (server Flexmark HTML) are different renderers:
  broken links, plugins (`[{…}]`) and `cite://` links are invisible until after save; no code
  highlighting anywhere.
- History and diff are read-only; there is no way to bring back an old version.
- Outline, backlinks and word count exist only on the read view.

Defects found during the review:

| # | Defect | Fixed in |
|---|--------|----------|
| 1 | Link completion snapshot capped at 1000 pages; error swallowed | §3 |
| 2 | Drop hint shown for any drag; OS file drop does nothing | §2 |
| 3 | `wikantik.attachment.{maxsize,allowed,forbidden}` enforced only by the legacy `AttachmentServlet`, not by `AttachmentResource` (the SPA's upload path), while `/admin` overview displays them as policy | §2 |
| 5 | `PageLockService`/`PageLock` are dead code (no REST endpoint or UI locks), with a live reaper thread | §7 |
| 6 | `HeadingSlugs.slug` (export) disagrees with the page view's `slugify` (`utils/headings.js`): keeps Unicode letters and `_`, does not collapse hyphens, no duplicate suffix — so exported `[[Page#Heading]]` anchors fail for such headings | §3 |

(Defect 4 — rename referrer rewrites via `putPageText` bypass pre-save validators and are not
reindexed — is deferred to its own follow-up.)

## 2. Paste and drop attachments

**Triggers.** A CodeMirror `paste` DOM handler acts when `clipboardData.files` is non-empty; a
`drop` handler acts when `dataTransfer.files` is non-empty. Both feed one upload routine. Text
paste and the existing attachment-row drag (`text/plain` payload, `useEditorDrop`) are unchanged.

**Flow per file (sequential when several):**
1. Insert placeholder at the caret: `![Uploading <name>…]()`.
2. `api.uploadAttachment(page, file, name)` → existing `POST /api/attachments/{page}`.
3. Replace the placeholder with the same markup an attachment-row drag produces:
   `![stem](fileName)` for images, `[stem](fileName)` otherwise.
4. Refresh the attachment panel list.
On failure: remove the placeholder, toast the server's message (413 size, 415 type, 403 permission).

**Naming** (`normalizeAttachmentName`, beside `utils/attachmentNameValidator.js`, tested against the
same rules as `AttachmentNameValidator`):
- Pasted (clipboard) images: `pasted-YYYYMMDD-HHMMSS.<ext>`, ext from the MIME type.
- Dropped files: original name normalised — characters outside `A-Za-z0-9._-` removed, all but the
  last `.` become `-`, no leading/trailing `-`/`_`, stem truncated so the whole name is ≤ 40 chars.
- Collision with an existing attachment on the page: suffix the stem `-2`, `-3`, …

**Unsaved new page.** `DefaultAttachmentManager.storeAttachment` refuses attachments to a
non-existent page (`attach.parent.not.exist`). On an unsaved page, paste/drop inserts nothing and
shows a toast "Save the page once to add attachments" with a **Save and upload** action that runs
the normal save and then the upload.

**Drop hint (defect 2).** Shown only when `dataTransfer.types` includes `Files`; text becomes
"Drop to upload". `dragover` `preventDefault` is likewise gated on a file payload.

**Upload policy (defect 3).**
- Extract the extension/size policy logic from `AttachmentServlet` into
  `com.wikantik.attachment.AttachmentUploadPolicy` (wikantik-main), reading
  `wikantik.attachment.maxsize`, `wikantik.attachment.allowed`, `wikantik.attachment.forbidden`.
- `AttachmentServlet` and `AttachmentResource` both call it before storing. Rejections:
  size → **413**, type → **415** with a message naming the extension (via
  `RestServletBase.sendError`, never `response.sendError`).
- Enforced only at these two user-upload entry points — not inside `storeAttachment` — so
  connector/ingest syncs that store source attachments are unaffected by an upload policy.
- Defaults remain blank: no behaviour change on a default install.

## 3. Link authoring

**Live page search (defect 1).**
- `GET /api/pages` gains `q=`: case-insensitive substring match on the page name. Ranking: exact
  match, then prefix matches, then other substring matches; alphabetical within each group.
  Existing `filterViewable` ACL filtering applies unchanged. The editor requests `limit=20`.
- The editor queries with ~150 ms debounce. The `listPages({limit:1000})` snapshot is removed;
  fetch failures log `console.warn` with context.

**Triggers** (CodeMirror completion sources):
- `[[` — unchanged output: `[Name](Name)`.
- `](` — completes the link target: page names (via `q=`) plus the current page's attachment names.
  Suppressed when the target starts with `http`, `/` or `mailto:`.
- `#` within either trigger — heading completion: `[[Page#` / `](Page#` offer the target page's
  headings; `](#` offers the draft's own headings.

**Heading anchors must match the page view.** The page view assigns ids to h2/h3 only, via
`extractHeadings` in `utils/headings.js` (`slugify`: lowercase, whitespace→`-`, strip
`[^a-z0-9-]`, collapse `-`, trim `-`; duplicates `-2`, `-3`, …). Completion reuses that code:
`headings.js` gains `headingsFromMarkdown(md)` — parses with the preview's remark stack, takes
plain text of h2/h3 (formatting stripped as rendering would), and applies the same `slugify` and
`uniqueId`. Target-page markdown comes from `GET /api/pages/{name}`, cached per editing session.
Only h2/h3 are offered.

**Slug parity (defect 6).** The page view is canonical (its ids are what existing corpus links
target). `HeadingSlugs.slug` is changed to the view's algorithm, including duplicate suffixes in
`headingsBySlug`. One shared JSON case table (heading text → slug, including duplicates, `_`,
`&`, repeated spaces/hyphens, non-ASCII) is asserted by both a vitest and a JUnit test.

**Link to a page that doesn't exist.** When the typed query matches no page name exactly, the last
completion item is **Link to new page: <Name>**, where `<Name>` comes from the same title→name
function `NewArticleModal` uses. It inserts the link only; no page is created. The preview marks it
missing (§4); on the saved page the server's existing create-link opens the editor.

## 4. Preview signals

All are remark/rehype plugins added to the editor preview's pipeline; the preview stays
synchronous per keystroke, and scroll sync / click-to-source (`rehype-source-line`) are untouched.

**Broken links.**
- A plugin collects distinct wiki link targets, skipping external URLs, `#anchor`, `cite://`,
  and attachment references (`file.ext` on this page, `Page/file.ext`).
- Debounced (~500 ms) batch check: `GET /api/pages?names=A,B,C` — new parameter on the list
  endpoint returning the subset that exists **and** is viewable by the caller. Max 50 names per
  request (**400** above that); the client chunks. Results cached per editing session; only new
  targets are checked.
- Missing targets get the same CSS class/styling the server render uses for create-links, so a
  broken link looks identical before and after save.
- A page the caller cannot view is reported missing (consistent with completion; discloses nothing
  the listing doesn't).

**Plugins and directives.** Every `[{…}]` — both `[{X}]()` and bare `[{X}]` — renders as an inline
chip showing the name (e.g. "⚙ TableOfContents", "🔒 ALLOW view Admin"), full markup in the
`title` tooltip. AST-based, so occurrences inside code spans/blocks are left alone.

**Citations.** `[claim](cite://Target/heading "span")` renders as the claim text plus a badge
"↗ Target § heading", span in the tooltip. The target joins the broken-link check.

**Code highlighting.**
- Editor: `@codemirror/language-data` for fenced-code highlighting, languages lazy-loaded.
- Preview and page view share one module on highlight.js "common" (~35 languages), loaded via
  dynamic import so pages without code don't pay for it. Preview: rehype plugin. Page view: applied
  in the same post-mount DOM pass that renders math (survives the React 19 innerHTML re-render).
- Only blocks with a declared language (`class="language-x"`); no auto-detection.
- Theme colours as CSS tokens on `:root` with dark-mode values.
- Server-rendered SEO HTML stays unhighlighted.

## 5. Restore a version

- Entry points, shown only with edit permission: a **Restore** action on each non-current row of
  `ChangeNotesPanel`'s history table; a **Restore version N** button in `DiffViewer` for the "from"
  version (`DiffViewer` fetches page permissions, which it does not currently know).
- Navigates to `/edit/{name}` with router state `{ restoreVersion: N }`. `PageEditor` loads the
  current page as usual (so `expectedVersion` = current and concurrent saves hit the normal 409
  flow), then fetches `GET /api/pages/{name}?version=N` and replaces **body and frontmatter**,
  pre-fills the change note "Restored version N", and shows a banner "Editing a copy of version N —
  saving creates version M+1."
- Save goes through the normal path, so schema/math validators apply; an old version whose
  frontmatter fails today's schema shows inline errors and blocks save until fixed.
- Restore is explicit and replaces any local draft for that page.
- No new server endpoint.

## 6. Side rail and status bar

**Rail.** Third grid column: ~240 px open, 28 px collapsed strip. Open state remembered per
browser in `localStorage` (every access in try/catch; renders correctly without it). Default open
at viewport ≥ 1100 px; below that it is a right-hand drawer toggled from the toolbar, default
closed.
- **Outline:** h1–h4 of the live draft, ~300 ms debounce, indented by level, via
  `headingsFromMarkdown` extended to return level and source line. Click → existing
  `jumpToLineAligned`; the preview follows through scroll sync. The section at the editor's top
  visible line is highlighted.
- **Backlinks:** reuse `BacklinksPanel` (saved server state), refreshed after save. New page:
  "No backlinks yet".

**Status bar** under the editor: body word count (excluding frontmatter and fenced code), reading
time via `utils/readingTime.js`, cursor `Ln x, Col y`; with a selection, "42 of 1,284 words".

## 7. Dead-code removal (defect 5)

Delete `PageLockService`, `DefaultPageLockService` (and its reaper), `com.wikantik.api.pages.PageLock`,
the `lockPage`/`unlockPage`/`getCurrentLock`/`getActiveLocks` methods on `PageManager` and
`DefaultPageManager`, their `PageSubsystem`/`PageSubsystemFactory` wiring, the no-op unlock in
`DefaultAclManager`, lock stubs in `StubPageManager`, `FakePageManager`,
`FailingPureTextPageManager`, the lock tests, and the `PageLock` mention in
`build-support/pmd-ruleset.xml`.

## 8. API summary

| Change | Surface |
|--------|---------|
| `q=` (substring, ranked) | `GET /api/pages` |
| `names=` (≤ 50, existing ∧ viewable subset; 400 above) | `GET /api/pages` |
| 413 / 415 from upload policy | `POST /api/attachments/{page}` (and legacy `AttachmentServlet`) |

No new servlets (the `/api/*` count in CLAUDE.md is unchanged) and no new config keys: debounce
intervals and the 50-name cap are constants.

## 9. Testing (TDD — each test demonstrated failing first)

- **vitest:** `normalizeAttachmentName` cases; upload flow (placeholder → replaced; failure removes
  placeholder; unsaved-page toast + Save-and-upload); drop hint only for `Files`; completion
  sources (`[[`, `](`, `#`, new-page item, `http` suppression, debounce, ranking passthrough);
  `headingsFromMarkdown` ids equal `extractHeadings` ids on the rendered HTML for a fixture set;
  shared slug case table; preview plugins (missing-link class, chips, untouched inside code,
  citation badge); highlighter only on `language-x`; restore flow (body+frontmatter replaced,
  change note, banner); rail (persisted toggle, outline click jumps); status-bar counts.
- **JUnit:** `PageListResource` `q=` ranking + ACL filtering, `names=` subset + ACL + 400 over cap;
  `AttachmentUploadPolicy` cases; `AttachmentResource` 413/415; legacy servlet still enforces;
  `HeadingSlugs` against the shared case table (export link conversion tests updated to the
  canonical slugs).
- **REST IT:** a non-admin user's `?q=` and `?names=` never return a page restricted from them.
- **Gates:** `bin/run-tests.sh --parallel 4`, PMD complexity gate (no baseline additions), JaCoCo
  floors, vitest, ESLint 0.
