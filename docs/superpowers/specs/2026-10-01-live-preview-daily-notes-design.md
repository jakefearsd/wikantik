# Live Preview & Daily Notes — Design

**Status:** approved for implementation 2026-10-01 (user directive: "handle it fully, design and
implementation"). Rulings recorded inline as **Ruling:**.
**Depends on:** `2026-10-01-native-wikilinks-design.md` (shipped first) — live preview renders
`[[ ]]` / `![[ ]]` and uses the `/api/pages/{name}/embed` endpoint.
**Scope:** an Obsidian-style *Live Preview* editing mode in the CodeMirror editor (markdown rendered in
place; raw syntax revealed on the lines you are editing), a Source/Live toggle, and an "Open today's
daily note" command. Frontend only.
**Explicitly out of scope:** rendering tables in place (they stay source, monospace), WYSIWYG table
editing, a reading view inside the editor, persisted fold state, configurable daily-note name formats
or folders, previous/next-day navigation, a `journal` page type.

## 1. Problem

Obsidian's default mode shows formatted text while you type. Our editor is pure source beside a
separate preview pane, which reads as "a developer tool" to note-takers and halves the writing width.

## 2. Mode and toggle

- `useEditorMode()` hook: `'source' | 'live'`, persisted per browser in `localStorage`
  `wikantik.editor.mode` (try/catch on every access, as `useRailOpen`). **Ruling:** default `source`
  — existing editors see no change; Obsidian users flip it once and it sticks.
- Toggle: a toolbar button (`aria-pressed`, label "Live"), the command `toggle-live-preview`
  ("Toggle live preview", section View) and `Mod-e` (Obsidian's editing-view toggle).
- The side preview pane is independent (its own existing toggle). Live mode does not hide it.
- Implementation: a `Compartment` in `CodeEditor` reconfigured through the view (`livePreview` prop);
  switching never recreates the editor, never changes the document, keeps undo history, selection and
  folds.

## 3. Markdown dialect in the editor

`markdown({ base: markdownLanguage, … })` (GFM: tables, strikethrough, task lists, autolinks) replaces
the CommonMark default in **both** modes, so the source-mode tree matches what the server and preview
render. Existing fold/callout/link plugins and their tests must stay green against the GFM tree.

## 4. Rendering rules (live mode)

**Active lines** = every line touched by any selection range (cursor line included). On active lines
nothing is hidden or replaced — the raw source shows, with only the styling classes applied. A block
construct (fenced code, block math, embed) is active if any of its lines is.

| Construct | Inactive rendering |
|---|---|
| ATX heading | `#…` + following space hidden; line class `cm-lp-h1`…`cm-lp-h6` (display font, sizes stepping like article headings) |
| `*em*`, `**strong**`, `~~del~~`, `` `code` `` | markers hidden; `cm-lp-em/strong/del/code` |
| `[text](url)` | `[`, `](url)` hidden; text styled `cm-lp-link` |
| `[[T]]`, `[[T\|A]]`, `[[T#H]]` | brackets/target hidden; shows `A`, `T`, or `T > H` styled `cm-lp-link` |
| `![alt](src)`, `![[O/f.png\|w]]` | replaced by an inline image widget (attachment URLs resolved like the preview's `remarkAttachments`; width honoured) |
| `![[T]]`, `![[T#H]]` | replaced by a block widget rendering `/api/pages/{T}/embed` HTML (shared fetch cache with the preview's `WikiEmbed`; loading/missing/restricted states) |
| `> quote` | `>` marks hidden; `cm-lp-quote` line class (left border) |
| `> [!type] Title` callout | quote marks hidden; first line's marker replaced by an icon + title widget; all callout lines get `cm-lp-callout` with the callout's `--callout-<style>` tint (same alias map as `remarkCallouts`) |
| `---` / `***` rule | replaced by an `<hr>` widget |
| `- ` / `* ` / `+ ` bullet | marker replaced by a `•` widget (nested indentation kept) |
| `- [ ]` / `- [x]` task | marker + box replaced by a checkbox widget; clicking toggles `[ ]`↔`[x]` via a view transaction (one undo step) — the only edit live mode ever makes |
| `$x$`, `$$…$$` | KaTeX widgets (inline / block); invalid TeX shows the source in `cm-lp-math-error` |
| fenced code | fence lines muted (`cm-lp-fence`), body lines `cm-lp-codeblock` background; nothing hidden |
| tables, HTML, `[{Plugin}]` | unchanged source; plugins get a muted `cm-lp-plugin` pill |
| frontmatter block | unchanged (already folded/handled by the existing frontmatter fold) |

Rules:
- **Decorations only.** Live mode never alters the document except the explicit checkbox click; the
  saved text in live mode is byte-identical to source mode (asserted by a test).
- Mod-click / Ctrl-hover on links keeps working (`linkInteraction` covers live widgets/marks); a plain
  click on rendered text places the cursor (revealing that line), as in Obsidian.
- Built only for `view.visibleRanges`; rebuilt on doc change, selection change, viewport change or
  syntax-tree progress. Replace-decorations never span a line break except block widgets, which use
  `block: true` decorations from a `StateField` (CodeMirror requirement).
- Styling lives in `editorChromeSpec` (`&.cm-editor`-rooted, tokens only — extend the token guard's
  theme-independent list for `--font-display`); KaTeX CSS imported where the math widget lives.
- **Ruling:** tables stay source — in-place table rendering needs cell editing to be usable and is the
  biggest single risk; Obsidian users tolerate source tables.

### 4.1 Structure

`utils/livePreview/` — `ranges.js` (pure: `livePreviewSpecs(state, activeLines)` → `[{from, to, kind,
…}]`, walking the Lezer tree plus a line regex for `[[ ]]`), `widgets.js` (Image, Embed, Math, Bullet,
Checkbox, Rule, CalloutTitle), `plugin.js` (ViewPlugin for inline marks/replacements + StateField for
block widgets), `index.js` (`livePreview()` extension). The pure function carries the logic and the
bulk of the tests.

## 5. Daily notes

- Command `daily-note` "Open today's daily note" (section Page, `Mod-Alt-d`) registered in
  `useGlobalCommands`, available everywhere.
- Name = today's local date `YYYY-MM-DD`. If the page exists → navigate (guarded) to `/edit/<name>`.
  Otherwise → `/edit/<name>` with `initialMetadata { type: 'article', date: <name>, tags: ['daily-note'],
  title: <long local date, e.g. "Thursday, 1 October 2026"> }` and `initialContent
  "# <long date>\n\n"`; the page is created on first save like any new page. If a hub declares cluster
  `journal`, `cluster: journal` is added. Existence via `api.listPages({ names: [name] })`.
- **Ruling:** no new page type and no server change — `article` + a `daily-note` tag keeps the schema
  and drift dashboards unchanged.

## 6. Error handling

Widget failures (KaTeX parse error, embed fetch error, image 404) render inside the widget and never
throw out of the plugin; fetch errors `console.warn` with the target. A decoration-builder exception is
caught at the plugin boundary, logged with `console.warn`, and that viewport renders as plain source
for the transaction (fail-open to source mode, never a blank editor).

## 7. Testing

- Pure `livePreviewSpecs` tests per construct, active vs inactive, multi-cursor, nested emphasis,
  wikilink forms, callout aliases, code-fence exclusion.
- Real-CodeMirror tests (`realEditorHarness`): toggle keeps doc/undo/selection; moving the cursor moves
  the revealed line; checkbox click toggles and undoes in one step; save in live mode == source text;
  typing in live mode under the typing-latch harness never loses keystrokes (extend the interleaving
  test to run a share of seeds in live mode).
- Daily note: new vs existing, `journal` cluster present/absent, guarded navigation when dirty.
- Token guard + build stays green; manual browser pass in light/dark at desktop width.

## 8. Review focus

1. A long page (2,000+ lines) — typing latency stays interactive (visible-range building only).
2. Cursor inside a hidden range after an external edit (rename/convert) — line reveals, no crash.
3. IME composition / mobile typing on a decorated line — no lost input (active line shows source).
4. A `$` currency sign in prose (`$5 and $10`) — not rendered as math (same rules as `remarkMath`).
5. Undo across a mode toggle — history intact.
