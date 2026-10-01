# Editor Workspace & Callouts — Design

**Status:** approved design, not yet implemented (2026-09-30)
**Scope:** Group A of the second Obsidian editing-gap review (quick switcher + command palette +
slash commands, link hover preview + Ctrl-click, type-aware templates, outgoing unlinked mentions,
heading folding, tag autocomplete, in-app unsaved-changes guard) plus Obsidian-style callouts.
Every item helps any editor of this wiki and none changes the stored page format, so the work is
non-regrettable whichever direction the product takes.
**Predecessor:** `2026-09-30-editor-quality-of-life-design.md` (shipped c6000a5753..9281804878).
**Explicitly out of scope:** incoming unlinked mentions (writes to other pages), native `[[Page]]` /
`![[embed]]` stored syntax, inline live preview, persisted fold state, blocking the browser Back
button, templates editable as wiki pages, the rename-path validation defect and the `handleRename`
`setBody` race (separate follow-ups).

## 1. Problem

After the quality-of-life project the editor authors well, but navigation and structure still feel
unlike a knowledge-base tool:

- Ctrl-K opens a full-text search; there is no instant title switcher and no command palette.
  Every editor action lives only in the fixed toolbar or a handful of hard-coded shortcuts.
- Links can't be inspected or followed from the editor: no hover preview anywhere, and a click in
  the source does nothing.
- `NewArticleModal` offers three of the five schema types and seeds every page with
  `# Title\n\nWrite your article here.` — the schema's knowledge of what a runbook or design page
  looks like is never used.
- Nothing helps densify the link graph while writing (Obsidian's unlinked mentions).
- `CodeEditor` sets `foldGutter: false`; long pages can't be collapsed by heading.
- The frontmatter `tags` widget never receives suggestions although `TagInput` supports them and
  `/api/structure/tags` exists.
- Clicking a sidebar link with a dirty draft navigates away without warning (the localStorage draft
  survives, silently).
- `> [!note]` callouts — ubiquitous in Obsidian notes — render as plain blockquotes with a literal
  `[!note]`.

## 2. Architecture overview

| Unit | Kind | Responsibility |
|---|---|---|
| `commands/registry.js` + `useRegisterCommands` | frontend | Single command registry; components register contextual commands while mounted |
| `QuickOverlay.jsx` | frontend | Replaces `SearchOverlay`: switcher, `>` command mode, full-text section |
| slash completion source | frontend | CodeMirror completion fed by `slash: true` commands |
| `LinkPreviewCard.jsx`, `usePagePreview`, `useLinkPreview` | frontend | Hover card, cached fetch, hover delegation for rendered HTML |
| CodeMirror link-interaction extension | frontend | Ctrl/Cmd-hover preview and Ctrl/Cmd-click in the source |
| `UnlinkedMentionsPanel.jsx`, `useUnlinkedMentions` | frontend | Rail section listing outgoing unlinked mentions |
| `NavigationGuardProvider`, `useNavigationGuard`, `useGuardedNavigate` | frontend | In-app unsaved-changes guard |
| `remarkCallouts.js` | frontend | Callouts in the editor preview |
| `GET /api/pages/{name}/preview` | server | Preview card data |
| `GET /api/page-templates` | server | One template per schema type |
| `POST /api/mentions/scan` | server | Outgoing unlinked-mention matches for a draft |
| `PageTitleIndex` | server (wikantik-main) | name / de-CamelCased name / `title` / `aliases` per page; feeds `?q=` ranking and the mention scan |
| `PageTemplates` | server (wikantik-api) | Template definitions beside `FrontmatterSchema` |
| callout Flexmark extension | server | `com.wikantik.markdown.extensions.callouts`, registered in `MarkdownDocument` |
| `aliases` field | schema | Optional list in `FrontmatterSchema` |

**Key-binding rule.** The global hotkey handler ignores any keydown whose `defaultPrevented` is
already true, so a binding CodeMirror handled (Mod-k = insert link inside the editor) never also
fires a global action. Outside the editor Ctrl/Cmd-K and Ctrl/Cmd-O open the overlay; Ctrl/Cmd-P
opens it in command mode everywhere (the browser's print shortcut is consumed; menu print still
works).

## 3. Quick overlay, command registry, slash menu

### 3.1 Overlay modes

`QuickOverlay` replaces `SearchOverlay` (App-level, same mount point). Ctrl/Cmd-K and Ctrl/Cmd-O
open it with an empty query; Ctrl/Cmd-P opens it prefilled with `>`.

- **Empty query:** recently viewed pages (`useRecentlyViewed`) when logged in; recent changes
  otherwise.
- **Page query** (no leading `>`): a page section ranked by `GET /api/pages?q=` (§3.2), shown as
  soon as it returns; below it the full-text section (the existing `api.search` typeahead,
  debounced as today); then two fixed rows — **Search full text for "…"** (navigates to `/search`)
  and **Create page "…"** (shown when no page matches the query exactly; opens `NewArticleModal`
  with the title prefilled so the type/template choice still happens).
- **Command query** (leading `>`): registry commands whose title matches the rest of the query
  (case-insensitive subsequence, title-start matches first), each showing its key binding if any.
- **Keys:** ↑/↓ move; Enter activates; Ctrl/Cmd-Enter opens a page result in a new tab; Escape
  closes. Page activation uses `useGuardedNavigate` (§8).

### 3.2 Ranking (`?q=` on the page list)

`PageListResource`'s `q=` gains title and alias awareness via `PageTitleIndex`. Each candidate's
match keys are: page name, de-CamelCased name, `title:`, each `aliases:` entry. Rank tiers, best
key wins per page:

1. exact (case-insensitive, whitespace-insensitive)
2. prefix
3. substring
4. subsequence (fuzzy: `lcifi` → `LowCostIndexFundInvesting`)

Ties break alphabetically by page name (as today). Results stay view-filtered. While
`PageTitleIndex` is warming, `q=` falls back to the current name-only ranking.

### 3.3 Command registry

`registry.js` holds commands `{ id, title, section, keys?, slash?, run(ctx) }`.
`useRegisterCommands(commands, deps)` registers on mount and unregisters on unmount, so editor
commands exist only while an editor is mounted. A single runner executes commands; it catches any
exception, logs `console.warn` with the command id, and shows a "Command failed" toast.

Initial commands:

- **Global:** Go to page; Search full text; New page; Edit this page (read view); Page history;
  Recent changes; Toggle sidebar.
- **Editor:** Save; Bold; Italic; Insert link; Heading 1/2/3; Insert callout (note, tip, warning,
  danger, info); Insert table; Code block; Math block; Horizontal rule; Insert image (file picker →
  the existing upload pipeline); Fold all; Unfold all; Toggle preview; Toggle rail.

`EditorToolbar` buttons invoke the same command ids, so toolbar, palette and shortcuts can't
diverge.

### 3.4 Slash menu

A CodeMirror completion source offering commands with `slash: true` (headings, callouts, table,
code block, math block, rule, image, link). Triggers on `/` at line start or after whitespace; never
inside frontmatter, fenced code, inline code or math (decided from the syntax tree). Choosing an
item deletes the typed `/query` and runs the command; Escape, or a query with no matches, leaves
the typed text untouched.

## 4. Link hover preview and Ctrl-click

### 4.1 Scope

Internal wiki-page links in the read view, the editor preview pane and the source editor; the
target is resolved with the existing `wikiLinkTarget`. External URLs, attachments and same-page
`#anchor` links get no card. A link to a missing page shows a "Not created yet" card.

### 4.2 Rendered HTML (read view, preview pane)

`useLinkPreview` attaches one delegated listener to the content container. Open after 400 ms hover;
a 200 ms grace period lets the pointer move into the card; close on leaving link and card, Escape,
or scroll. Keyboard focus on a link shows the card on the same timing. Touch devices: no change.

### 4.3 Source editor

A CodeMirror extension resolves the link under the pointer from the syntax tree (`[text](Target)`,
autolinks). While Ctrl/Cmd is held, links get a pointer cursor and underline; Ctrl/Cmd-hover shows
the card; Ctrl/Cmd-click opens the target in a new tab (`window.open(url, '_blank', 'noopener')`) —
this also applies to external links, which get no card.

### 4.4 Card and endpoint

Card: title, type badge, cluster, then `summary` or else the excerpt. Text only — no Markdown is
rendered, nothing executes.

`GET /api/pages/{name}/preview[?section=slug]` →
`200 {name, title, type, cluster, summary, excerpt, lastModified}`.

- View-ACL'd; missing and not-viewable both return the identical 404.
- `excerpt`: body with frontmatter, ACL markup and plugin syntax removed, links reduced to their
  text, plain text, ≤ 280 chars cut at a word boundary. With `section=`, the excerpt comes from
  the section whose `HeadingSlugs` slug matches; an unknown slug falls back to the page excerpt.
- `title` falls back to the de-CamelCased name when frontmatter has none.

`usePagePreview`: session-lifetime in-memory cache (~200 entries, LRU), 404 cached as "missing",
in-flight request aborted when the hover ends, entry evicted for a page the user just saved.

## 5. Templates, aliases, tag autocomplete

### 5.1 Templates

`PageTemplates` (wikantik-api, beside `FrontmatterSchema`) defines one template per schema `type`:
a metadata map plus a body skeleton. Placeholders: `{{title}}` and `{{date}}` only, substituted on
the client.

| Type | Body skeleton |
|---|---|
| article | `# {{title}}` |
| reference | `# {{title}}`, `## Summary`, `## Details`, `## See also` |
| design | `# {{title}}`, `## Context`, `## Goals`, `## Non-goals`, `## Design`, `## Alternatives considered`, `## Status` |
| runbook | `# {{title}}`, `## Notes`; metadata carries the empty `runbook:` block skeleton `RunbookBlockEditor` expects |
| hub | `# {{title}}`, `## Overview`, `## Start here` |

Metadata matches today's modal: `type`, `status: active`, `date`, plus `cluster` when the modal has
one. Empty-valued fields are omitted, not written as `""`. For `hub` the modal requires a cluster
before Create is enabled.

`GET /api/page-templates` → `[{type, label, description, metadata, body}]`.

`NewArticleModal`: type picker lists all schema types with a one-line description and a read-only
preview of the skeleton. If the fetch fails: fall back to `# {{title}}`, show "Templates
unavailable" inline, `console.warn` the error.

### 5.2 `aliases:`

New optional `FrontmatterSchema` field `aliases`: list of free-text strings (spaces allowed).
Advisory WARNING when an entry is blank or longer than 100 chars, consistent with other field-value
checks. Rendered as a chip list in the structured frontmatter editor. Used only by the `?q=`
ranking and the mention scan — not displayed on the page, not used for SEO or the ontology.
Obsidian export passes it through unchanged (it is Obsidian's own field). Added to the standard
frontmatter field list in the MCP instructions text.

### 5.3 Tag autocomplete

The frontmatter `tags` widget passes `suggestions` to `TagInput`, sourced from one
`/api/structure/tags` fetch per editor session. Free entry still works; on fetch failure the widget
behaves as today and the failure is logged.

## 6. Outgoing unlinked mentions

### 6.1 Endpoint

`POST /api/mentions/scan` body `{page, text}` →
`200 {mentions: [{target, title, phrase, from, to, line, context, more}]}`, at most 50, document
order. `from`/`to` are UTF-16 code-unit offsets into `text` (Java and JS string indices agree);
`line` is 1-based; `context` is ≈ 60 chars around the match; `more` counts further eligible
occurrences of the same target. Errors: 400 (missing/invalid body), 413 (`text` > 1 MB), 503
`{warming: true}` while `PageTitleIndex` is building. Covered by the existing CSRF filter. Each
target is returned only if the caller can view it.

### 6.2 Eligibility

The draft is parsed with the production Flexmark parser. Only plain text nodes inside paragraphs,
list items, table cells and blockquotes are eligible. Skipped: frontmatter, headings, fenced and
inline code, math, link text and URLs, autolinks/bare URLs, HTML, `[{Plugin}]` markup, image alt
text.

### 6.3 Matching

Phrases come from `PageTitleIndex` (de-CamelCased name, `title`, `aliases`). Case-insensitive,
whole-word; overlapping matches resolve to the longest phrase. Phrases shorter than 4 characters
are dropped, as are single words on a built-in common-word list (constants in code, no config
keys). Excluded targets: the page itself, and any page the draft already links to anywhere. Only
the first eligible occurrence per target is reported.

### 6.4 `PageTitleIndex`

Built in the background at startup from page frontmatter; kept current by page save, rename and
delete events. An exception while applying an event is logged at WARN and the index stays usable;
the next startup rebuilds it fully.

### 6.5 Client

`useUnlinkedMentions` scans only while the rail is open, debounced 1.5 s after typing stops, and
aborts an in-flight scan on newer input. `UnlinkedMentionsPanel` (an `EditorRail` section with a
count badge) shows per row: phrase → target title, line + context (click moves the cursor there),
**Link** and **Ignore**.

**Link:** verify the draft text at `from..to` still equals `phrase`; if not, relocate to the
nearest occurrence of the phrase; if none remains, rescan and show "Text changed — rescanned".
Replacement is `[phrase](Target)` preserving the author's casing, applied through a new CodeEditor
handle method `replaceRange(from, to, text)` as one undoable, non-External transaction.
**Ignore:** hides the target for the rest of the editing session. States: loading, empty ("No
unlinked mentions"), warming ("Mentions available shortly"), failed (retry).

## 7. Callouts

### 7.1 Syntax

Obsidian's: a blockquote whose first line is `[!type]`, optionally followed by `+` or `-`, then an
optional title. The rest of the blockquote is the body. Callouts nest.

Types (case-insensitive) map to 13 styles: note; abstract (summary, tldr); info; todo; tip (hint,
important); success (check, done); question (help, faq); warning (caution, attention); failure
(fail, missing); danger (error); bug; example; quote (cite). An unknown type renders in note style
with its own name as the default title. Default title: the capitalised type as written. A custom
title may contain inline Markdown.

### 7.2 Output

```html
<div class="callout" data-callout="warning">            <!-- no fold marker -->
  <div class="callout-title"><span class="callout-icon" aria-hidden="true"></span>
    <span class="callout-title-inner">Title</span></div>
  <div class="callout-content">…</div>
</div>
```

With `-` the wrapper is `<details class="callout" data-callout="…">` and the title is a
`<summary class="callout-title">`; `+` adds `open`. `data-callout` carries the style name,
normalised to `[a-z]+`. If the server render path applies an HTML allowlist, `details`, `summary`
and `data-callout` are allowed. Malformed markers fall back to a plain blockquote; the extension
never throws.

Server: Flexmark node post-processor on `BlockQuote` in `com.wikantik.markdown.extensions.callouts`,
registered in `MarkdownDocument`. Preview: `remarkCallouts.js` producing the same structure.
Parity: a shared fixture (same pattern as `heading-slugs.json`) asserted by both the Java and JS
tests. Styling: icons are CSS glyphs; colours are tokens defined for both light and dark themes.

Raw Markdown consumers (`?format=md`, MCP reads, the chunker, Obsidian export) are unchanged.

## 8. Heading folding and navigation guard

### 8.1 Folding

Enable the fold gutter in `CodeEditor` using the heading folds `@codemirror/lang-markdown` already
provides, plus a fold service for the frontmatter block. CodeMirror's fold keymap (Ctrl-Shift-[ /
], Ctrl-Alt-[ / ]) and the Fold all / Unfold all commands. Fold state is not persisted and never
affects saved text. **Reveal rule:** every programmatic cursor move — preview click-to-source,
outline rail, mention context, math-error jump, search — unfolds any fold containing the target
first.

### 8.2 Navigation guard

`NavigationGuardProvider` at the App root; `PageEditor` calls `useNavigationGuard(dirty)`. A
capture-phase `click` listener on `document` intercepts, while a guard is active, only: primary
button, no modifier keys, an `a[href]` without `target`/`download`, same origin, and not a
same-page hash jump. It cancels the click and opens a `Modal`: "You have unsaved changes. Your draft
is kept in this browser but isn't saved to the wiki." — **Stay** / **Leave without saving**.
`useGuardedNavigate()` applies the same dialog to programmatic navigation (overlay, commands, any
chrome that calls `navigate()`; the plan audits Sidebar and header call sites). The editor's
existing Cancel confirmation uses the same dialog. Save-then-navigate clears the dirty flag first.
The browser Back button is not intercepted (the localStorage draft covers it); reload/close remain
covered by `beforeunload`.

## 9. API summary

| Method | Path | Notes |
|---|---|---|
| GET | `/api/pages?q=` | adds title/alias/fuzzy tiers (§3.2) |
| GET | `/api/pages/{name}/preview[?section=]` | §4.4; 404 for missing or not viewable |
| GET | `/api/page-templates` | §5.1 |
| POST | `/api/mentions/scan` | §6.1; 400/413/503 |
| GET | `/api/structure/tags` | existing; now used by the tags widget |

All new error responses go through `RestServletBase.sendError`. No new configuration keys; no
schema migration (`aliases` is frontmatter).

## 10. Error handling

No swallowed exceptions anywhere. Client fetch failures `console.warn` with context and show a
degraded but working state: title search failure shows "Page search failed" while full-text
results still show (and vice versa); preview failure shows no card; templates fall back with a
notice; tags fall back to free entry; mention scan shows a retry row. Command failures are caught by
the runner (§3.3). Server-side, `PageTitleIndex` event failures log WARN; callout parsing degrades
to a blockquote.

## 11. Testing (TDD — each test demonstrated failing first)

**Java**
- `PageTemplates`: every schema `type` has a template; every rendered template validates with zero
  ERRORs and zero WARNINGs.
- `PageTitleIndex`: de-CamelCasing, `title`, `aliases`; save, rename, delete events; event failure
  leaves index usable.
- Mention scanner: table-driven eligible/excluded contexts (§6.2); longest match; self and
  already-linked exclusion; common words and min length; first-occurrence + `more`; offsets with
  emoji and CRLF text.
- Preview endpoint: summary vs excerpt, `section=` and unknown slug, restricted page returns the
  same 404 as a missing one.
- `?q=` ranking tiers including alias and subsequence; warming fallback.
- Callout extension against the shared fixture: every type/alias, unknown type, `+`/`-`, custom
  title with inline Markdown, nesting, malformed markers.
- Resource tests for each new endpoint (400/404/413/503 paths); ACL IT for preview and mention
  results alongside `WikiPageFormatAclIT`.

**Frontend (vitest)**
- Registry: register/unregister on mount, runner catches and reports failures.
- Overlay: empty/page/command modes, ordering of sections, keys, create row, Ctrl-Enter, guarded
  navigation.
- Slash source: trigger and non-trigger contexts (frontmatter, code, math, mid-word).
- Preview: 400 ms delay, grace period, abort on leave, cache hit, 404 → "Not created yet",
  eviction on save.
- Source-editor link extension: Ctrl/Cmd-hover card, Ctrl/Cmd-click opens new tab, plain click does
  nothing special.
- Mentions: debounce, rail-closed no scan, stale-offset relocation, vanished phrase → rescan,
  `replaceRange` undo, Ignore.
- Navigation guard: interceptor condition table, dialog choices, `useGuardedNavigate`.
- `remarkCallouts` against the shared fixture.
- `NewArticleModal`: all types, preview, hub requires cluster, fetch-failure fallback.
- Folding: reveal rule for each jump source.
- Global hotkeys: `defaultPrevented` events ignored; Ctrl-O/P/K mapping.

**Browser:** one Selenide IT — Ctrl-O opens the switcher and navigates to a page; a callout renders
on the read view. Then a manual browser pass of every feature.

**Gates:** vitest + lint, `bin/run-tests.sh --parallel 4`, `mvn pmd:check -Pcomplexity-gate`
(no baseline additions), `mvn clean install -Pcoverage -DskipITs`, no new heavy frontend
dependencies. CHANGELOG Unreleased entry. The design is published to the wiki against its hub.
