# Native Wikilinks & Embeds — Design

**Status:** approved for implementation 2026-10-01 (user directive: "handle it fully, design and
implementation"). Rulings recorded inline as **Ruling:**.
**Scope:** Obsidian-style stored syntax — `[[Page]]`, `[[Page|Alias]]`, `[[Page#Heading]]`,
`[[#Heading]]`, `![[Page]]`, `![[Page#Heading]]`, `![[Owner/file.png]]` — rendered server-side and in
the editor preview, understood by every subsystem that reads links (Page Graph, backlinks, broken
links, rename, unlinked mentions, raw-markdown output, plain-text excerpts, Obsidian export), and
inserted natively by the editor's `[[` completion.
**Sequence:** first of three (this → `2026-10-01-obsidian-vault-import-design.md` →
`2026-10-01-live-preview-daily-notes-design.md`). The importer keeps vault links verbatim because of
this work; live preview renders this syntax.
**Explicitly out of scope:** block references (`^id`, `[[Page#^id]]`), vault-wide bare-filename
attachment lookup (`![[file.png]]` resolves on the current page only), `[[Page|300]]`-style sizing of
page embeds, highlight `==x==`, Obsidian comments `%%`, converting existing `[Text](Page)` links (both
syntaxes are first-class forever), `[{InsertPage}]` reference tracking.

## 1. Problem

Obsidian users write `[[Note]]` by reflex; today it renders as literal text, the `[[` completion
silently rewrites it into `[Note](Note)`, pasted notes lose every link, and `![[Note]]` transclusion
has only the clumsy `[{InsertPage page=Note}]` equivalent. Nothing that reads links (the Page Graph,
backlinks, rename) can see `[[ ]]`, so adopting the syntax piecemeal would silently break the graph.

## 2. Syntax (normative)

| Form | Meaning |
|---|---|
| `[[T]]` | link to page `T`, text `T` |
| `[[T\|A]]` | link to `T`, text `A` (`\|` escaped as `\\|` inside table cells) |
| `[[T#H]]` / `[[T#H\|A]]` | link to heading `H` of `T`; default text `T > H` |
| `[[#H]]` | heading `H` of the current page; default text `H` |
| `![[T]]` / `![[T#H]]` | embed (transclude) page `T` / its section `H` |
| `![[O/f.ext]]`, `![[f.ext]]` | embed attachment `f.ext` of page `O` / of the current page; images render inline, other files as an attachment link |
| `![[O/f.png\|300]]`, `\|300x200` | image width / width×height in px |
| `[[O/f.ext]]` | plain link to an attachment |

Rules:
- Not recognised inside code spans or fenced/indented code (flexmark already excludes these).
- The target must not be empty and must not begin with whitespace — so prose like `[[ -f "$x" ]]` stays
  text. Targets are trimmed at the end.
- A target containing `/` is an attachment reference `Owner/file` (page names cannot contain `/`).
  A target without `/` is an attachment of the current page iff the current page has an attachment
  with exactly that name; otherwise it is a page.
- Heading `H` is matched by the same slug algorithm the page view uses (`HeadingSlugs.slug` /
  frontend `headings.js slugify`), so `#My Heading` targets `id="section-…my-heading"` exactly as
  `[x](T#my-heading)` does today.

## 3. Target resolution — `WikiLinkResolver` (wikantik-main)

One resolver used by rendering, the reference scanner and the preview/embed endpoints:

1. `engine.getFinalPageName(T)` — exact name incl. the existing plural matching;
2. case-insensitive page-name match;
3. case-insensitive match against a page's `title` or any `aliases` entry (from `PageTitleIndex` via
   the structural index — reuse its phrase data; do not build a second index);
4. unresolved → `T` cleaned with `MarkupParser.cleanLink` (renders as a `createpage` link to
   `/edit/T`, counts as an uncreated reference).

Ambiguity at step 2 or 3 resolves to the lexicographically lowest page name (deterministic).
**Ruling:** steps 2–3 exist because Obsidian links are case-insensitive and alias-aware; without them
an imported vault would be a sea of red links. They apply to `[[ ]]` only — `[Text](Page)` keeps its
current exact resolution (no behaviour change for 5,000+ existing links). If the structural index is
not ready (startup), steps 2–3 are skipped (fail-soft to exact matching; the reference graph is
refreshed on the next save as today).

## 4. Server rendering (wikantik-main, Flexmark)

- Add `flexmark-ext-wikilink` (`${flexmark.version}` = 0.64.8, already in the reactor's resolved
  graph) to `MarkdownDocument.options()` and `structuralOptions()` with
  `LINK_FIRST_SYNTAX=true`, `ALLOW_ANCHORS=true`, `ALLOW_PIPE_ESCAPE=true`, `IMAGE_LINKS=true`.
  **Ruling:** use the extension's parser rather than a hand-rolled inline parser — it already handles
  code exclusion, escaping and table-cell pipes. If an option proves unusable, a custom
  `InlineParserExtension` modelled on `InlineMathParser` is the fallback; record the choice in the ledger.
  The bare `LINK_SCANNER` used by `MarkdownParser.collectLinks` is unchanged.
- A new node post-processor (beside `WikantikLinkNodePostProcessor`) converts `WikiLink` nodes into the
  same output the existing local-link path produces, so every downstream consumer of rendered HTML
  (hover previews, `createpage` styling, SPA navigation, `rehypeSourceLine`-equivalents) works unchanged:
  existing page → `<a href="/wiki/T[#section-…]">` with exactly the classes/attributes
  `LocalReadLinkAttributeProviderState` emits today, missing → `class="createpage"`
  `href="/edit/T"`, attachment → the existing attachment link/image markup. Reuse
  `LocalLinkNodePostProcessorState` / the attribute-provider states rather than duplicating them.
- Leading whitespace rule (§2) enforced in the post-processor: a non-conforming `WikiLink` is replaced
  by its literal source text.

### 4.1 Embeds — `WikiEmbedRenderer`

`![[T]]` / `![[T#H]]` (page targets) render as

```html
<div class="wiki-embed" data-embed="T" data-section="H">
  <div class="wiki-embed-title"><a href="/wiki/T#section-…">T › H</a></div>
  <div class="wiki-embed-body">…rendered markdown of T (or section H)…</div>
</div>
```

- Body = target's markdown with frontmatter stripped; section = `HeadingSlugs.sectionBody` semantics
  (heading through the next heading of the same or higher level, heading line excluded). Rendered by a
  direct parse+render of that text with the target page set on a cloned context — **not**
  `RenderingManager.textToHTML` (its unconditional `CACHE_HTML` put is keyed by the outer page).
- **ACL:** the viewer must have `view` on `T`; otherwise the body is
  `<p class="wiki-embed-restricted">You don't have access to this page.</p>` and the title is plain text.
  Any render that contains an embed sets `Context.VAR_VIEWER_SENSITIVE` so it is never served from the
  principal-less caches. **Ruling:** correctness over cache hit rate; embed pages are a minority.
- **Missing target / missing section:** `<p class="wiki-embed-missing">` with a `createpage` link
  ("Not created yet") / "Section not found: H".
- **Cycles & depth:** a stack of page names in a context variable (as `InsertPage.ATTR_RECURSE`);
  re-entering a page or exceeding depth 3 renders `<p class="wiki-embed-error">Embed loop stopped</p>`.
- **Size:** embedded source capped at `wikantik.embed.maxChars` (default 20000, new config key, declared
  in `ini/wikantik.properties` per the config-surface rules); overflow truncates at a block boundary and
  appends a "Continue reading →" link.
- Attachment embeds: images → existing image markup with optional `width`/`height`; other files → the
  existing attachment link. Missing attachment → `wiki-embed-missing` with the filename.

### 4.2 Embed endpoint

`GET /api/pages/{name}/embed?section=H` (sub-path of `PageResource`, view-ACL gated like
`/preview`) → `{ "html": "…", "missing": bool, "restricted": bool, "truncated": bool }` produced by the
same `WikiEmbedRenderer` with the caller's session. Used by the editor preview (and live preview).
404 is never used for missing — `missing:true` (no existence oracle beyond what `/preview` already gives).

## 5. Link-graph and other consumers

| Consumer | Change |
|---|---|
| `MarkdownLinkScanner.findLocalLinks` (wikantik-api) | also returns targets of `[[ ]]` and `![[ ]]` (page targets; `Owner/file` → `Owner/file` as attachment refs do today), skipping fenced code blocks and inline code for the wikilink forms. Heading fragments stripped. |
| `DefaultReferenceManager.scanWikiLinks` | resolves wikilink targets through `WikiLinkResolver` before storing (so `[[foo bar]]` → `Foo Bar`); embeds count as references, so the existing referrer-eviction on save (`DefaultRenderingManager.actionPerformed`) evicts embedders too. |
| `DefaultPageRenamer` | rewrites `[[Old…]]`, `[[Old#H…]]`, `[[Old\|A]]`, `![[Old…]]`, `![[Old/file…]]` → `New`, preserving heading, alias, size and the `!`; also matches targets that resolved case-insensitively to `Old`. Alias-resolved links are left alone (the alias still resolves). Code-fence-aware for the new forms. |
| `MentionMasking` | masks `[[…]]` / `![[…]]` so they are never reported as unlinked mentions; their targets join the `linked` set via the scanner. |
| `WikiPageFormatFilter` (`?format=md`) | rewrites `[[T\|A]]` → `[A](<base>/wiki/T#…)`, `[[T]]` → `[T](…)`, page embeds → `[Embedded: T](…)` (not expanded), attachment embeds → `![](…/attachments/O/f)` — agents get standard markdown with absolute URLs, as for existing links. |
| `PageExcerpts`, `NodeTextAssembler.stripMarkdown` (summaries, for-agent) | `[[T\|A]]` → `A`, `[[T]]` → `T`, `[[T#H]]` → `T > H`, embeds removed. |
| Obsidian export (`ObsidianPageConverter`) | native `[[ ]]` targets are re-mapped through `VaultLayout` like `[x](Page)` links (target page name → vault basename); embeds keep `![[ ]]`. |
| `WikiToMarkdownConverter` (Convert button) | output must never contain an unescaped `[[` produced from legacy `[[` escapes — emit `\[`. |
| ContentChunker / embeddings | unchanged (raw text is acceptable embedding input). |

## 6. Client

- **`remarkWikiLinks`** (new remark plugin, runs before `remarkMissingLinks`) turns `[[ ]]` in text
  nodes (mdast; never in `inlineCode`/`code`) into `link` nodes with the same href scheme as the server
  (`/wiki/T`, `#section-…` via `headings.js`) so `remarkMissingLinks`, `useLinkPreview` and SPA
  navigation work unchanged. The client cannot resolve aliases; it passes the raw target, and
  `useMissingPages` (`GET /api/pages?names=`) learns a `resolve=true` flag that returns, per requested
  name, the resolved page name (or null) using `WikiLinkResolver`; the plugin rewrites hrefs to the
  resolved name. `![[O/f.png]]` / attachments → image/link nodes like `remarkAttachments` produces.
- **Embeds in the preview:** a `WikiEmbed` component (block) fetches `/api/pages/{name}/embed` (deduped
  per name+section, cached for the editor session, re-fetched on preview open), renders the returned
  HTML in a memoized host (React 19 innerHTML re-render rule), with title link + states for
  missing/restricted/loading/error.
- **Parity fixture:** `utils/__fixtures__/wikilinks.json` (`{name, markdown, pages, expected:[{href,
  class, text}]}`), asserted by a frontend test against `remarkWikiLinks` output and by a Java test
  against server HTML — same pattern as `callouts.json`.
- **Editor `[[` completion** (`wikiLinkComplete.js`) inserts native syntax: page → `[[Name]]`, new page
  → `[[text]]`, heading → `[[Page#Heading]]`; `]]` auto-close handling unchanged. `](` target completion
  is unchanged (it serves existing markdown links). Toolbar Mod-K and paste/drop keep markdown syntax.
- **Source-mode Ctrl-hover / Ctrl-click** (`linkInteraction.js`): `linkAt` also recognises `[[ ]]` /
  `![[ ]]` on the current line by regex (not the Lezer tree, which has no node for it) and maps to the
  same href; `linkRanges` marks them `cm-link-range`.

## 7. Error handling

Rendering never throws on odd input: an unparseable/forbidden form renders as literal text; embed
failures render the in-place `wiki-embed-error` paragraph and `LOG.warn` with the page names (never an
empty catch). The embed endpoint returns 403 only when the *outer* request lacks view on `{name}`.

## 8. Testing

- Java: `WikiLinkResolver` (exact, plural, case, alias, ambiguity, index-not-ready); renderer (every §2
  form, table pipe, code exclusion, leading-space rule, missing page, attachment vs page, sizes);
  `WikiEmbedRenderer` (ACL denied, missing, section, cycle, depth, truncation, viewer-sensitive flag set);
  scanner/reference manager (graph contains `[[ ]]` + embeds, resolved names); renamer (every form,
  alias preserved, code fences untouched); mention masking; `?format=md`; excerpts; export re-mapping;
  parity fixture.
- Frontend: `remarkWikiLinks` + parity fixture, `WikiEmbed` states, completion inserts native syntax,
  `linkInteraction` recognises `[[ ]]`.
- IT (wikantik-it-test-rest): save a page with `[[ ]]` and `![[ ]]`; backlinks list it; rename rewrites
  it; `/wiki/X?format=md` emits standard links; embed endpoint ACL.

## 9. Review focus (inputs no task's happy path covers)

1. Legacy pages containing `[[` as prose or bash conditionals outside code — must stay literal (§2 rule).
2. `[[T]]` where `T` exists only as an alias of two pages — deterministic choice, no exception.
3. A page that embeds itself, or A↔B mutual embeds — loop stop, no stack overflow.
4. Restricted embedded page viewed by a guest and then by an admin — no cache cross-talk.
5. Rename of a page referenced only via `[[lowercase name]]` — rewritten; aliased references untouched.
