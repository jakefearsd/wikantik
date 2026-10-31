# Obsidian Block References (+ highlight, comments, task toggling, inline tags) — Design

**Status:** draft 2026-10-31. User directive: "skip [the questions] and use judgement for general use-case
coverage — overall we need a robust set of features." Every decision below that the user did not make
is marked **Call:** so it can be checked while reading.
**Sequence:** four independently shippable phases, in order:

| Phase | Feature | Size |
|---|---|---|
| 1 | Block references — `^id` anchors, `[[Page#^id]]` links, `![[Page#^id]]` embeds, copy-block-link, `#^` completion, import fidelity | large |
| 2 | `==highlight==` and `%% comments %%` | small |
| 3 | Toggle task checkboxes from the reader | small–medium |
| 4 | Inline `#tags` — rendered, indexed, completed | medium |

Each phase gets its own implementation plan; phase 1's is written first.

**Builds on:** `2026-10-01-native-wikilinks-design.md` (syntax, `WikiLinkSyntax`, `WikiLinkResolver`,
`WikiEmbedRenderer`, parity fixtures), `2026-10-01-obsidian-vault-import-design.md` (importer),
`2026-10-01-live-preview-daily-notes-design.md` (live preview). That spec listed every feature here as
out of scope; this one brings them in.

## 0. Why

The wiki now covers everyday Obsidian use: wikilinks, embeds, callouts, the quick switcher, live
preview, daily notes, diagrams, paste-as-Markdown, vault import/export. What's left is syntax that
heavy Obsidian users rely on but that **import currently deletes**: block ids (`VaultBodyRewriter`
strips `^id` markers and `RewriteCtx.cutBlock` turns `[[x#^id]]` into `[[x]]`), and `%% comments %%`.
Highlights render as literal `==`. Inline `#tags` are lifted into frontmatter on import but do nothing
when typed in the editor. Task checkboxes can be ticked in the editor's live preview but not on the
page itself.

**Goals:** a vault that uses these features imports without loss and behaves as the user expects;
someone writing new content in the wiki can use the same syntax and features; every subsystem that
reads page text (rendering, Page Graph, rename, export, `?format=md`, excerpts, chunker/embeddings,
search) handles the new syntax correctly.

**Non-goals:** Obsidian Canvas, Dataview queries, block-id-addressed citations (§1.12), renaming a
block id with automatic referrer rewrite, block-level backlinks, `[[Page#^id|300]]` embed sizing,
unterminated-`%%` "comment out the rest of the file" behaviour, nested-tag hierarchy (Phase 4),
dedicated MCP block tools (§1.11a).

**Anti-goals (failure even if it "works"):**
- Any existing page silently loses visible text because a pattern now parses as new syntax. Each phase
  ships only after a corpus audit (§5).
- A reader toggle or block-id assignment overwrites a concurrent edit. Every server-side write is
  version-checked.
- `%% comments %%` presented as private. They are hidden from readers, not secret (§2.2).
- Server and editor-preview rendering disagree. Every syntax gets a shared parity fixture, as
  wikilinks and callouts already have.

---

## Phase 1 — Block references

### 1.1 Syntax (normative)

A **block id** is `^` followed by one or more of `[A-Za-z0-9-]`. It is attached to a block in two ways:

| Form | Applies to |
|---|---|
| Inline marker: whitespace + `^id` at the very end of the last line of a paragraph or list item's text (trailing spaces allowed) | that paragraph (`<p>`) or list item (`<li>`) |
| Standalone marker: a paragraph consisting only of `^id` | the **immediately preceding sibling block** in the same container (table, blockquote/callout, list, fenced code, math block, paragraph) |

Links and embeds:

| Form | Meaning |
|---|---|
| `[[T#^id]]` / `[[T#^id\|A]]` | link to block `id` on page `T`; default text `T > ^id` (unchanged `displayText`) |
| `[[#^id]]` | block on the current page |
| `![[T#^id]]` | embed just that block |

Rules:
- Never recognised in code spans, fenced or indented code, or math.
- The marker must be preceded by whitespace, so `x^2`, `2^10` and `e^x` are never markers.
- **Heading lines do not take block ids.** A ` ^id` at the end of a heading stays literal text; headings
  are linked by text, as in Obsidian.
- The `heading` part of a wikilink ref is a **block ref iff it starts with `^`**. This is the single
  discriminator everywhere (server and client). Slugged heading anchors can never start with `^`, so the
  two namespaces cannot collide.
- Ids match **case-sensitively**, as written. **Call:** Obsidian generates lowercase ids, and exact
  matching avoids having two ids that differ only in case point at different blocks.
- Duplicate id on one page: the **first** occurrence (document order) wins. Later duplicates still
  have their marker removed from output but get no anchor. The editor flags them (§1.8).
- A standalone marker with no preceding sibling (first block in its container) renders an empty
  anchor `<span id="^id" class="block-anchor"></span>` in place, so links still land.

### 1.2 Anchor representation — **Call**

The rendered element gets `id="^id"`, and links use the fragment `#^id`, exactly as Obsidian Publish
does. `^` is legal in an HTML5 id, so no slug, prefix or encoding scheme is needed. Fragment handling
must `decodeURIComponent` the hash, because some browsers serialise `^` as `%5E`.
`WikantikHtmlSanitizer` already allows `id` globally. A test must prove a `^`-prefixed id survives
sanitisation and the SSR path.

This **changes today's behaviour**: `[[T#^abc]]` currently slugs to `T#abc`. Both `wikiLinkHref`
(frontend) and `NativeWikiLinkPostProcessor.pageLink` / `WikiEmbedRenderer` / `WikiPageFormatFilter`
(server) must pass a `^`-heading through **unslugged**.

### 1.3 Shared block model — `BlockIds` (wikantik-main, package `com.wikantik.parser.markdown`, beside the package-private `MarkdownBodySource` it needs)

One server utility that every consumer uses. It works on page **source** and parses it the way
rendering does: run the same pre-parse steps the renderer runs (notably `DisplayMathPreProcessor`,
which rewrites `$$` blocks into ```` ```math ```` fences, with offsets tracked by `MarkdownBodySource`),
then parse with `MarkdownDocument`'s structural options. That way tables, callouts, display math and
fences are recognised exactly as rendering sees them. All `sourceStart`/`sourceEnd`/`markerStart`
offsets are mapped back to the **raw** source through `MarkdownBodySource`, because `assign` and
`strip` edit raw text. Code/math exclusion for the marker regexes uses `CodeMask` **plus** a math-span
mask (`MathSpanExtractor` or equivalent): `CodeMask` alone does not mask `$…$` / `$$…$$`.

- `List<Block> list(String markdown)`: every addressable block in document order. `Block(id | null,
  kind, sourceStart, sourceEnd, markerStart | -1, plainText)`. Kinds: paragraph, listItem, table,
  blockquote, callout, code, math, list. A list's items are listed individually; the list itself is
  listed only when a standalone marker addresses it.
- `Optional<Block> find(String markdown, String id)`.
- `String sourceOf(String markdown, Block b)`: the block's source with its marker removed. A list item
  includes its nested children and keeps its list marker, so an embed renders as a one-item list.
- `String assign(String markdown, Block b, String id)`: inserts ` ^id` at the block's end, or as a
  standalone marker after it (with a blank line before it) for block kinds that can't take an inline
  marker.
- `String strip(String markdown)`: removes every marker (inline and standalone), keeping line count
  stable (a standalone line becomes an empty line). Used by `?format=md`, excerpts, chunker and search
  indexing.
- `String newId(Set<String> existing)`: 6 chars `[a-z0-9]`, retried until unique on the page
  (Obsidian's format).

`plainText` is the block's text with markdown and wikilinks reduced the way `WikiLinkSyntax.toPlainText`
/ `PageExcerpts` do. It is used for completion labels and for the reader's block matching (§1.7).

### 1.4 Server rendering

- New node post-processor in the flexmark pipeline, beside `NativeWikiLinkPostProcessor`, applying the
  §1.1 rules to the AST:
  - an inline marker: trim it from the trailing `Text` node and set `id` on the paragraph, or on the
    enclosing `ListItem` when the paragraph is the item's own first paragraph;
  - a standalone marker: remove the marker paragraph and set `id` on the previous sibling block.
  Use the existing attribute mechanism (`AttributesExtension` / `NodeAttributeCarrier`) rather than
  string post-processing.
- **Source ordinals — Call.** The same post-processor stamps `data-block="i"` on every addressable
  block, with `i` being its index in `BlockIds.list` order. Phase 3 adds `data-task="j"` on task items.
  The reader's copy-link (§1.7) and task toggle (Phase 3) then send an exact index instead of guessing
  from DOM order. Ordinals depend only on page source, never on the viewer, so they are safe in the
  principal-less render caches.
  - **Each parse numbers its own blocks from 0. The reader ignores ordinals inside included
    content.** *(Amended 2026-10-31 during planning.)* Included content is already wrapped:
    `[{InsertPage}]` in `div.inserted-page`, embeds in `div.wiki-embed`. So the client ignores any
    `[data-block]`/`[data-task]` whose `closest('.inserted-page, .wiki-embed')` is inside the
    article. The host's own numbering is unaffected by what it includes, because an include is a
    single plugin/embed node in the host AST. No server-side gating is needed. Test this with
    InsertPage explicitly.
  - `WikantikHtmlSanitizer` must allow `data-block`/`data-task`.
  - A parity test asserts that rendered ordinals equal `BlockIds.list` indices over a fixture with
    callouts, nested lists, tables and display math.
- `NativeWikiLinkPostProcessor.pageLink`: a `^` heading gives `#^id`, unslugged, for both same-page
  (`Kind.ANCHOR`) and cross-page links.
- `WikiEmbedRenderer`:
  - a `^` heading resolves through `BlockIds.find` and renders `BlockIds.sourceOf`. The ACL, cycle,
    depth, truncation and `VAR_VIEWER_SENSITIVE` behaviour is unchanged.
  - not found gives `<p class="wiki-embed-missing">Block not found: ^id</p>`.
  - The title is the page name (not `T › ^id`), linking to `T#^id`. **Call:** `› ^id` is noise to a
    reader, and Obsidian shows a block embed without its id.
- `GET /api/pages/{name}/embed?section=^id`: the same rule; `section` beginning with `^` is a block.
- `GET /api/pages/{name}/preview?section=^id`: the excerpt is the block's plain text, and an unknown
  block adds `"blockMissing": true` and falls back to the page excerpt (the existing fallback). The
  hover card shows "Block not found" when the flag is set.
- Render-cache correctness: block links are page references (`DefaultReferenceManager` already
  records them), so the existing referrer eviction on save covers embeds of changed blocks. No new
  cache work.

### 1.5 Other consumers

Wherever this table says "strip", the call goes through `ReaderText.derived` (§1.11a), not
`BlockIds.strip` directly, so Phase 2 can add comment stripping in one place.

| Consumer | Change |
|---|---|
| `WikiPageFormatFilter` (`?format=md`) | `#^id` fragment kept verbatim (today it slugs to `#id`); markers stripped from the body via `BlockIds.strip` |
| `PageExcerpts`, `NodeTextAssembler.stripMarkdown` | strip markers; link display text stays `T > ^id` |
| `ContentChunker` | chunk `BlockIds.strip(body)`, so markers never reach embeddings and adding an id doesn't change chunk hashes. One-time churn: pages that already contain markers re-embed once |
| Lucene body indexing | index `BlockIds.strip(body)` |
| `MarkdownLinkScanner`, `DefaultReferenceManager`, `DefaultPageRenamer`/`WikiLinkRenamer`, `MentionMasking`, `ObsidianWikiLinks` export | already correct: they ignore or preserve the `#…` suffix. Add a regression test for each, no code change expected |
| Obsidian export (`ObsidianPageConverter`) | markers are body text and pass through untouched; test the round trip |
| Broken-links report (`GetBrokenLinksTool` + `AdminContentResource` + its admin UI) | add `brokenBlockRefs: [{page, target, id}]`: links and embeds whose target page exists but has no such block, computed on demand by scanning page sources (~1.2k pages is fine) |

### 1.6 Importer — stop deleting

- `VaultBodyRewriter`: delete `TRAILING_BLOCK` / `LINE_BLOCK` stripping and the `blockref:` warning.
- `RewriteCtx.cutBlock` / `WikiLinkRewriter`: keep `#^id` fragments. A renamed target keeps its
  fragment, as heading fragments already do. `![[x#^id]]` stays a block embed.
- Code-masking behaviour is unchanged. Tests in `VaultBodyRewriterTest` invert from "stripped" to
  "preserved".
- Pages imported before this ship already lost their markers. That can't be recovered from the wiki;
  re-importing the vault restores it. **Call:** no automated repair. Note it in the release notes.

### 1.7 Copy block link (reader)

- Hovering a block in the article (`p`, `li`, `table`, `blockquote`/callout, `pre`, display math)
  shows a small link button in the left gutter. It uses the same visual language and toast as
  `headingAnchors.js`'s `#` button and its "Link copied" toast.
- **The block has an id** (`id^="^"`): copy `[[PageName#^id]]` to the clipboard. **Call:** copy the
  wikilink, not the URL, because the user is going to paste it into another page. The button's menu
  also offers "Copy URL" (`origin/wiki/Page#^id`).
- **The block has no id:** the button appears only when `page.permissions.edit` is true and the page
  isn't derived (`derived_from` frontmatter: the body is machine-owned, so a reflow would wipe the
  id). Click → `POST /api/pages/{name}/blocks` `{ version, index, fingerprint }` → the server picks the
  block, assigns `newId`, saves a new version through the normal save path (author = caller, change
  note `Added block id ^xxxxxx`, the same permission and `expectedVersion` checks as
  `PUT /api/pages`; there are no edit locks to honour) and returns `{ id, version }`. The client sets
  the element's id, copies the link and updates its known version.
- **Locator:**
  - `index` is the element's `data-block` ordinal (§1.4), or the `index` from the blocks list (§1.8).
  - `fingerprint` is a safety check: the block's text lowercased, reduced to letters and digits, and
    the first 200 chars. The server compares it with `Block.plainText` reduced the same way.
  - A version mismatch → 409 `{ reason: "stale" }`, and the toast offers a reload.
  - An index out of range at the right version → 409 `{ reason: "block-not-located" }`, and the
    toast reads "Couldn't locate this block — add an id in the editor".
  - A fingerprint mismatch at the right version is **logged at WARN with both fingerprints but does
    not block**. *(Amended 2026-10-31 during planning.)* Rendered text of math (KaTeX MathML), links
    and footnotes legitimately differs from source plain text, while version + ordinal already pin
    the exact block. The fingerprint is a drift diagnostic, not a gate.
  - A derived page → 409 `{ reason: "derived" }`.
  - Elements without `data-block` (e.g. plugin output) get no button.
- Blocks inside `.wiki-embed` get no button (they belong to another page).

### 1.8 Editor

- **Completion** (`wikiLinkComplete.js`): `[[Page#^query` lists that page's blocks, and `[[#^query`
  lists the current buffer's.
  - Other pages: `GET /api/pages/{name}/blocks?q=&limit=20` (view-gated) →
    `{ version, canAssign, blocks:[{ index, id|null, kind, text }] }`, with `text` being the
    `plainText` excerpt (≤120 chars) and `q` filtering by substring of text or id.
  - Current page: a client-side `blocksFromMarkdown(buffer)` (§1.9).
  - Picking a block with an id inserts `[[Page#^id]]`.
  - Picking one without an id: on the current page, the marker is inserted into the buffer at the
    block's end in the same transaction. On another page with `canAssign`, the client calls
    `POST …/blocks {version, locator:{index}}`, then inserts. Without `canAssign`, id-less blocks are
    listed greyed with "no id — needs edit access".
  - `[[Page#` (no `^`) keeps listing headings only.
- **Command "Copy block link"** (palette + block context menu in live preview): on the block at the
  cursor, adds a marker if needed and copies `[[ThisPage#^id]]`.
- **Live preview:** a block marker renders as a small muted chip (`cm-lp-blockid`) when the cursor is
  off its line, and as raw text when it's on. A duplicate id gets a warning style and title.
- **Source-mode Ctrl-click** (`linkInteraction.js`): works through `wikiLinkHref`'s new `^` handling.
- **Lezer note:** the editor grammar enables superscript (`^x^`). A line with two carets might mis-tint
  a marker. Tolerated: decorations come from the `blockIds.js` scan, not the Lezer tree.

### 1.9 Client rendering (editor preview)

- `utils/blockIds.js`: the marker regexes, `blocksFromMarkdown(md)` (via the remark/mdast tree the
  preview already builds), `newBlockId`, and `insertMarker`.
- `remarkBlockIds` plugin: the same AST rules as §1.4, setting `data.hProperties.id`. It runs before
  `remarkWikiLinks`.
- `wikiLinkSyntax.wikiLinkHref`: a `^` heading gives `#^id`, unslugged.
- `WikiEmbed` / `embedCache`: `section` passes the `^id` through, and the title shows the page name
  for block embeds.
- **Parity fixture** `utils/__fixtures__/block-ids.json` (`{ name, markdown, expected: [{ tag, id,
  text }] }`) is asserted by `remarkBlockIds.test` and a Java `BlockIdParityTest`, like
  `wikilinks.json`. `wikilinks.json` gains `#^id` cases.

### 1.10 Reader landing

- `fragmentScroll.js` / `PageView.jsx` look the target up with `decodeURIComponent(hash)`.
- On landing on a block target, add `block-target-flash` (a ~2 s background fade, `prefers-reduced-motion`
  → static outline for 2 s).
- A block hash with no element shows a toast: "Block ^id not found on this page".

### 1.11 Error handling

Rendering never throws on odd input: an unparseable marker stays literal. Embed and assign failures
`LOG.warn` with page and id (no empty catches).

Status codes for the new `/blocks` and `/tasks` endpoints:
- a missing page **or** one the caller can't view → **404** for GET and POST alike. This follows
  `/preview` and doesn't reveal hidden pages.
- viewable but not editable (POST) → 403;
- 409 as above.

### 1.11a Source vs reader text — which surfaces strip what (**Call**)

Block markers and (Phase 2) comments are part of page **source**. One helper, `ReaderText`, defines the
two derivations, so every call site picks one consciously:

- **Derived text** (`ReaderText.derived(body)`): markers and comments stripped. It is used by
  rendered HTML, `?format=md|json`, excerpts/summaries, for-agent projection, chunker/embeddings (and
  so bundles and briefings), Lucene, feeds/sitemap and KG extraction input. None of these is ever
  written back.
- **Source surfaces** return the body as stored. **Block markers are never stripped from a source
  surface**: they are needed to link to a block, and agents that read before `update_page` must not
  silently delete them. Comments on source surfaces follow the caller, through the same seam as
  `AclDirectiveText.forViewer(canEdit, …)`:
  - `GET /api/pages/{name}` `content`: comments kept for callers with `edit`, stripped otherwise
    (stripped in the same step that removes ACL directives today).
  - admin-MCP `read_page` / `diff_page` / history: raw (admin writers).
  - knowledge-MCP `get_page` / `read_pages` / `get_page_by_id` (read-only keys): comments stripped,
    markers kept. Agents that write go through admin-MCP `read_page`, which is raw, so nothing written
    back loses comments.
  - page history / diffs in the UI: raw. History is not a reader surface, and the docs say comments
    aren't private.
- **No block MCP tools — Call.** Agents can see ids in source, link with `[[P#^id]]`, embed with
  `![[P#^id]]`, and add an id by writing a marker via `update_page`. The admin MCP instructions text
  (`wikantik-mcp-instructions.txt`) gains a short block-reference paragraph; the advertised tool set
  doesn't change, so the drift tests stay green.

### 1.12 Deliberately not in phase 1

- Citations addressed by block id (`cite://…#^id`). The citation model is heading-path + span hash.
  Block anchors are a natural future target, but changing the citation identity key is its own design.
- Automatic rewrite of `[[X#^old]]` when an id is edited. The broken-links report catches it.
- Block-level backlinks ("what links to this paragraph").

---

## Phase 2 — `==highlight==` and `%% comments %%`

### 2.1 Highlight

- `==text==` → `<mark>text</mark>`, using the same flanking rules as `~~strikethrough~~` (implement as a
  delimiter processor on `=` run length 2 on the server; nested emphasis works:
  `==**x**==`). Not in code or math.
- Frontend: an equivalent micromark/remark extension, so nesting matches. A text-node regex is not
  acceptable, because it breaks on nested marks. Parity fixture `highlight.json`, including nested,
  adjacent and `a == b` (no mark) cases.
- `WikantikHtmlSanitizer` already allows `mark` (verify with a test). CSS: a token-based highlight
  colour defined for light **and** dark themes.
- Editor: live preview `cm-lp-mark`, toolbar/palette "Highlight" (Mod-Shift-H). Paste-as-Markdown
  turns `<mark>` into `==…==` (Turndown rule). Excerpts/plain text: the delimiters are removed.

### 2.2 Comments

- `%%…%%` (inline, or spanning lines) is removed from **every reader-facing output**: rendered HTML,
  `?format=md|json`, excerpts and summaries, for-agent projection, chunker/embeddings, Lucene, feeds
  and KG extraction input.
- One implementation: `ObsidianComments.strip(text)` in wikantik-api. It masks code with `CodeMask`
  and math with a math-span mask (`CodeMask` does not cover `$…$`/`$$…$$`). It replaces each comment
  with the newlines it contained (so line numbers and preview scroll sync stay stable), and leaves an
  **unterminated `%%` literal**. **Call:** Obsidian hides everything after an unclosed `%%`; that's
  too easy to lose a page to.
- Applied at the rendering entry (before parse) and through `ReaderText` (§1.11a). Phase 2 adds comment
  stripping to `ReaderText.derived` and to the per-caller source rule; phase 1 has already routed every
  derived-text call site through it, so there is one list of call sites.
- The editor shows comments: live preview dims them (`cm-lp-comment`), and the palette command
  "Toggle comment" (Mod-/) wraps or unwraps the selection.
- **Not private:** anyone who can edit or view history sees them. `EditPageHelp` says so plainly.
- Importer: stop stripping `%%` comments and drop the `comment:` warning.

---

## Phase 3 — Toggle tasks from the reader

- Server rendering changes only by adding `data-task` ordinals (§1.4): task checkboxes stay
  `disabled` in HTML (the cache is principal-less). The client enables them when `page.permissions.edit` is true, the page isn't
  derived, and the page is markdown (not legacy wiki markup). Checkboxes inside `.wiki-embed` stay
  disabled.
- Click → optimistic flip → `POST /api/pages/{name}/tasks` `{ version, index, checked, text }`:
  - `index` is the item's server-stamped `data-task` ordinal (§1.4 source ordinals; only the page's
    own tasks carry one);
  - `checked` is the state it is changing **from**;
  - `text` is the item's text fingerprint.
- The server finds the `index`-th task item in source (`BlockIds`-style flexmark walk, code excluded).
  It verifies the current state equals `checked` and the fingerprint matches, flips `[ ]`↔`[x]`, and
  saves through the normal save path (change note `Checked "…"` / `Unchecked "…"`). It returns
  `{ version }`.
- A mismatch → 409, the client rolls back and shows "This page changed — reload to see the latest".
  Any other failure rolls back with an error toast.
- Rapid clicks are serialised client-side: each request carries the version returned by the previous
  one.
- Keyboard and screen-reader accessible: real `<input type=checkbox>` with an `aria-label` from the
  item text, matching the live-preview widget's labels.

---

## Phase 4 — Inline `#tags`

- **Syntax:** the importer's existing `InlineTags` rule, moved to wikantik-api as `InlineTagSyntax` and
  shared by importer, renderer and indexers. As implemented today the rule is:
  - `#` preceded by start-of-line or whitespace;
  - tag chars `[\p{L}\p{N}_/-]`, with at least one non-digit (so `#123` is not a tag);
  - not in code (via `CodeMask`), URLs or wikilinks/link destinations;
  - the whole heading line is skipped.

  Phase 4 adds a **math** exclusion (a math-span mask, since `CodeMask` doesn't cover `$…$`).
- **Canonical form — Call:** a tag's identity is the importer's existing `normaliseTag` (lowercase,
  `/`→`-`), applied to **both** inline and frontmatter tags wherever tags are *matched or listed*
  (index, filter, facets, completion, `list_tags`).
  - `#Foo`, `#foo` and frontmatter `foo` are one tag.
  - `#a/b` is the tag `a-b`, which lines up with every past import, whose frontmatter already holds the
    flattened form. No duplicates and no cleanup needed.
  - The body keeps what the author wrote, and the rendered chip shows it as written (`#a/b`).
  - **Nested-tag hierarchy (`#a` matching `a/b`) is a non-goal.** Normalising both sides is new work:
    the structural filter is exact-match, case-sensitive today.
- **Rendering:** `<a class="tag inline-tag" href="/search?tag=a-b">#a/b</a>` on the server and in the
  editor preview. Parity fixture `inline-tags.json`.
- **Tag view:** `/search?tag=foo` (no `q`) lists pages carrying the tag, through the structural index
  tag filter (`/api/structure/…?tag=`) comparing normalised forms. The reader's frontmatter tag chips
  switch from `/search?q=tag` to `/search?tag=<normalised>`, so both kinds of tag land on the same view.
  **Call:** reuses the existing search route rather than adding a SPA route (avoids the web.xml +
  `SpaRoutingFilter` dual-registration).
- **Indexing:** a page's tag set for **finding** = frontmatter `tags` ∪ inline tags. That covers the
  Lucene `tags` field (search facets), the structural index tags (`list_tags`,
  `list_pages_by_filter`, `/api/structure`) and the tag view. **Call:** SEO/feeds/sitemap/JSON-LD
  stay **frontmatter-only**, because tags written in passing in body text shouldn't become
  `<meta keywords>` or News-sitemap eligibility.
- **Editor:** typing `#` plus a character in prose opens completion of existing tags (from the
  structural index tag distribution, ranked by count). Live preview renders the tag as a chip
  (`cm-lp-tag`).
- **Importer:** stop lifting inline tags into frontmatter, since they are now first-class where they
  are written. **Call:** this keeps the imported body as written and avoids duplicate tag sources on
  export round-trip.

---

## 5. Corpus audit (required before each phase deploys)

Each new syntax can capture text that exists today. Before a phase is **deployed** (not before it's
built), run its detector over the **live prod corpus** (prod is authoritative; plan from the live
index, not the checkout) and the repo corpus. Record findings and any content fixes in
`docs/superpowers/specs/2026-10-31-obsidian-syntax-corpus-audit.md`, as the 2026-10-02 GFM/math
alignment did.

| Phase | Detector (outside code/math) |
|---|---|
| 1 | lines ending in whitespace + `^[A-Za-z0-9-]+`; lines that are only `^[A-Za-z0-9-]+` |
| 2 | `==…==` spans; any `%%` |
| 4 | inline-tag matches |

Each finding is reviewed and, if unintended, escaped (`\^`, `\=\=`, `\%\%`, `\#`) via the MCP content
tools, with a re-pull to verify.

## 6. Testing (TDD per CLAUDE.md: a failing test first)

- **Java:**
  - `BlockIds`: every §1.1 form, code/math exclusion, headings ignored, duplicates, orphan
    standalone, `assign`/`strip` line stability, `newId` uniqueness.
  - The render post-processor and `^`-id sanitizer survival.
  - `NativeWikiLinkPostProcessor` unslugged `#^id`.
  - `WikiEmbedRenderer` block found/missing/ACL/cycle.
  - embed/preview/blocks/tasks endpoints: permissions, 409 paths, derived-page refusal, version check,
    and a `FaultInjectingDataSource`-style or mocked-save failure leaving no partial write.
  - The `?format=md` fragment + strip; chunker strip; the broken-block report; importer preservation.
  - Regression tests for the consumers listed "already correct".
  - Phase 2/4 detectors and strips; parity tests for every fixture.
- **Frontend:**
  - `remarkBlockIds` + parity fixtures, `blocksFromMarkdown`/`insertMarker`, completion (both
    branches, `canAssign` false).
  - Reader copy-link button states, landing flash + not-found toast, task-toggle optimistic/rollback.
  - The highlight/comment/tag plugins + fixtures.
- **IT:**
  - REST (`wikantik-it-test-rest`): a block link renders `#^id`; block embed; `POST /blocks` assigns
    and bumps version; stale version → 409; task toggle round trip; `?format=md`.
  - Selenide: copy block link on an id-less paragraph; follow `[[X#^id]]` and land with flash;
    `[[X#^` completion inserts; tick a task in the reader and reload; inline tag click → tag view.
- **Gate:** `bin/run-tests.sh --parallel 4` before each phase's commit series is declared done.

## 7. Docs

`EditPageHelp` (syntax and "comments are not private"), the wiki design page, NativeWikilinksDesign's
out-of-scope line updated to point here, and release notes per phase. The release notes include the
note that pre-existing imports lost their block ids.

## 8. Review focus (inputs no happy path covers)

1. A paragraph ending in ` ^2` written as prose (e.g. "raised to the power ^2"): the corpus audit must
   find it. Rendering takes it as an anchor by design.
2. Two users: A opens page v5, B assigns a block id (v6), A clicks "copy block link" on another block
   → 409 stale, never a silent overwrite of v6.
3. `![[A#^x]]` where block `x` itself contains `![[A#^x]]`: loop stop, as for page embeds.
4. A restricted page's block embedded in a public page and viewed by a guest, then an admin: no cache
   cross-talk (`VAR_VIEWER_SENSITIVE` already set by the embed renderer).
5. Rename of page `A` referenced as `[[a#^x|alias]]`: the target is rewritten and the fragment and
   alias are kept.
6. A task list inside a callout and inside a nested list: the reader ordinal matches the server
   ordinal (fingerprint check catches drift).
7. `%%` inside a Mermaid fence (a Mermaid comment) is untouched.
8. `#fff` in prose becomes a tag (Obsidian does the same). The audit decides whether any need escaping.
