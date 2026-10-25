# Rendered Fences — design

Status: proposed (2026-10-24). First renderer: Mermaid.

## Intent

Pages carry diagrams written as text in fenced blocks. Today none of them draw: the
production corpus holds **63 ` ```mermaid ` fences on 43 pages** (41 `flowchart`,
13 `graph`, 7 `sequenceDiagram`, 1 `stateDiagram-v2`, 1 `erDiagram`; surveyed
2026-10-24 from `?format=md` of all 1,442 sitemap pages — prod is authoritative, the
repo mirror holds only 16 of them). They render as raw `<pre><code class="language-mermaid">`
because `MarkdownDocument` sets `GitLabExtension.RENDER_BLOCK_MERMAID=false` and the
frontend has no diagram library. The content agent (`~/source/wiki-content-agent`)
*requires* a Mermaid diagram on every non-hub page (`.agents/AGENTS.md:64`,
`bin/wikantik:374,733`), so the count keeps growing, and `WikantikEvolutionFromJSPWiki`
already advertises Mermaid as supported.

We want those diagrams drawn for human readers while every machine consumer still gets
the source text — and we want the mechanism to be a **general, extensible hook**
("rendered fences"), because UML (PlantUML) and charts (Vega-Lite) are expected next.
Mermaid is the only renderer this project ships.

## Goals

1. A fenced block whose language is registered as a rendered fence is drawn for human
   readers: the reader view, and both editor previews (the preview pane and the in-editor
   live-preview widgets), identically.
2. The source text is always present in the served HTML (SSR `/wiki/{slug}`, `contentHtml`
   from `/api/pages/*`), so agents, crawlers and no-JS clients read the diagram as text.
   `?format=md`, the for-agent projection, chunking/embeddings and Obsidian export are
   unchanged — they already carry raw markdown.
3. A shared **`example` modifier** in the info string (` ```mermaid example `) means "show
   this as an ordinary code block, never render". Handled once, generically, for every
   rendered-fence language — no renderer implements it.
4. The hook supports **two renderer kinds** from day one:
   - **browser renderer** — server emits the source in the standard envelope; the browser
     draws it (Mermaid now; Vega-Lite later);
   - **server renderer** — server emits a drawing *and* the source in the same envelope
     (PlantUML later). This path is built and proven by a **test-only renderer**; no
     production server renderer ships.
5. **Links inside diagrams count as real page links**: a renderer can report the page
   links in its source; they join the page's stored references, which drive backlinks,
   the Page Graph and the broken-links report. Mermaid reports `click <node> "/wiki/PageName"`
   / `click <node> href "/wiki/PageName"` targets. External `http(s)` targets are clickable
   but **not tracked** (the ReferenceManager tracks no external links).
6. Every existing prod diagram is checked; the ones that fail to parse are repaired on prod.
7. Code comments/Javadoc on the hook are written as the guide for adding the next renderer
   (what a browser renderer must do, what a server renderer must handle).

## Non-goals

- Migrating ` ```math ` onto the hook. Possible later; not touched now (it keeps working
  exactly as today).
- A production server renderer (PlantUML) or a second browser renderer (Vega-Lite).
- **Rewriting diagram links on page rename.** `DefaultPageRenamer` rewrites links with
  whole-text regexes; teaching it fence-scoped, renderer-specific rewriting is the risky
  half and is deferred. A rename leaves a diagram link broken, and it surfaces in the
  broken-links report. The hook's Javadoc must describe what the future rewrite needs
  (touch only that renderer's link syntax, only inside rendered fences, never `example`
  fences, never alter anything else).
- Plugin syntax (`[{Mermaid …}]`). Plugins parse as inline links (`MarkdownBodySource`
  appends `()`); multi-line diagram source with blank lines, `["…"]` labels and `<br/>`
  cannot survive that. Standard fences also keep GitHub/Obsidian rendering for free.

## Anti-goals (a failure even if it "works")

- Any rendered fence whose HTML lacks the source text.
- A broken diagram that blanks the space, throws, or breaks the rest of the page.
- Any path by which diagram source runs script: Mermaid `click … call fn()` callbacks,
  `javascript:`/`data:` link targets, or an `%%{init}%%` directive loosening security.
- Pages without rendered fences downloading the diagram library.
- Changing the output for unregistered fence languages (` ```java `, ` ```math `, etc.)
  or for ` ```mermaid example `, beyond the `example` word being dropped from the class.

## Constraints (verified in the codebase)

- **Sanitizer.** `WikantikHtmlSanitizer` (OWASP policy, applied when `allowHTML=true`)
  allows `figure`, `pre`, `code`, `details`, `summary`, `class` globally, but **no unknown
  `data-*` attributes and no SVG**. New envelope attributes must be added to its allowlist
  (precedent: `data-callout` with a value pattern). A future server renderer emitting SVG
  needs an explicit, narrow SVG allowlist — the Javadoc must say so; the test-only server
  renderer must prove its output survives the sanitizer.
- **CSP** (`CSPFilter`): `script-src 'self'` (no `unsafe-eval`), `style-src 'self'
  'unsafe-inline'`, `img-src 'self'`. Mermaid must render under this unchanged, and the
  bundled build must not need eval.
- **Two markdown implementations.** Server: Flexmark (`MarkdownDocument` extension list;
  prior art for a custom node renderer is `com.wikantik.markdown.extensions.callouts`).
  Browser: react-markdown in `components/editor/EditorPreviewMarkdown.jsx` and the
  CodeMirror widgets in `utils/livePreview/`. The "is this a rendered fence / is it an
  `example`" rule therefore exists twice and must be pinned by **one shared case table**
  (a JSON file read by both the JUnit and the vitest suites) so they cannot drift.
  (`inlineMath.cases.js` is the nearest precedent, but it is JS-only.)
- **Existing browser passes** in `PageView.jsx`: `renderMath` (lazy KaTeX) and
  `highlightCodeBlocks` (lazy lowlight), plus `addCopyButtons`. The new pass follows the
  same contract — idempotent, re-runs when `dangerouslySetInnerHTML` re-injects, stale-guard
  on navigation/unmount (see memory: React 19 innerHTML re-render wipe). Rendered fences
  must **not** be syntax-highlighted or get the generic copy button wrapped around the SVG.
- **Link collection has two paths; the one that matters is not the Flexmark one.**
  Backlinks, the Page Graph (`PageGraphSnapshotBuilder` via `findRefersTo`) and broken links
  (`GetBrokenLinksTool`) all come from `DefaultReferenceManager.scanWikiLinks`, a deliberate
  regex-only scan (`MarkdownLinkScanner`, wikantik-api — moved off the full parse as a
  10–50x speed-up; keep it that way). Diagram page links must be added **there**, by a
  lightweight fence scanner (no Flexmark) that finds rendered fences — skipping `example`
  fences, handling `~~~`, indented and list/blockquote-nested fences — and runs each
  registered renderer's link extractor over the body. This fence scanner is a **third
  implementation of the fence rule** and is pinned by the same shared case table.
  `MarkdownParser.collectLinks` (render-time mutator hooks) should report the same links
  for consistency, but it is secondary. Links inside `example` fences are never reported.
- **Existing pages**: references are serialized per page, so the 43 prod pages' diagram
  links won't appear until those pages are re-scanned. The builder must establish how
  references get rebuilt for existing pages (startup scan, an admin rebuild, or a scripted
  re-scan) and make that an explicit rollout step — no re-saving pages by hand.
- **Frontend supply chain**: pin the exact `mermaid` version; installs stay
  `npm ci --ignore-scripts`; lazy `import()` so it is its own chunk.
- `GitLabExtension.RENDER_BLOCK_MERMAID` stays `false` — the hook owns Mermaid.

## Decided design

### Info-string grammar

Language = first whitespace-delimited word of the fence info string, lowercased.
Remaining words are modifiers. `example` is the only modifier defined now. Unknown
modifiers are ignored (forward-compatible: a future `title=` etc.). A rendered-fence
language with the `example` modifier is emitted exactly like a non-rendered fence of that
language (`<pre><code class="language-mermaid">`, highlightable, copy button). Matching is
case-insensitive. The shared case table covers: plain, `example`, extra spaces, tabs,
mixed case, unknown modifiers, `~~~` fences, nested/indented fences in lists and
blockquotes, an empty body, and unregistered languages.

### Server: renderer registry (wikantik-main)

- A `RenderedFenceRenderer` contract keyed by language with a declared **kind**
  (`BROWSER` | `SERVER`), plus an optional **link extractor** (`source → page names /
  external URLs`). A registry holds them; Mermaid is the one production entry
  (`BROWSER`, with a link extractor).
- A Flexmark node renderer for `FencedCodeBlock` (registered in `MarkdownDocument`
  alongside the existing extensions) consults the registry. Unregistered languages and
  `example` fences fall through to default rendering untouched.
- **Envelope** (one fixed shape for every rendered fence; exact names are the builder's,
  but the shape is fixed):

  ```html
  <figure class="wk-fence" data-fence="mermaid" data-fence-kind="browser">
    <!-- SERVER kind only: the server-produced drawing goes here -->
    <pre class="wk-fence-source"><code class="language-mermaid">…escaped source…</code></pre>
  </figure>
  ```

  The source is HTML-escaped text — never raw HTML — so `<br/>` in labels reaches the
  browser as text and Mermaid interprets it.
- **Security (server-enforced, not just browser config)** for Mermaid: a fence is
  **refused** (never rewritten — the emitted source always equals the stored text) if it
  contains `click … call`/callback forms, a link target whose scheme is not `http`,
  `https` or a site-relative path, or an `%%{init}%%` / frontmatter `config` that touches
  security-relevant keys (`securityLevel`, `secure`, `htmlLabels`, `dompurifyConfig`, and
  whatever else the pinned Mermaid version treats as secure). A refused fence is emitted
  in the envelope flagged as refused (e.g. `data-fence-error="<reason>"`), and the browser
  shows the source plus the reason — never drawn, never blank. Defense in depth: the
  browser also initializes Mermaid with its `secure` key list so directives can't
  override the security level even if a check is missed.
- **The policy is one rule set in two languages.** The editor previews never touch the
  server, so the refusal rules are ported to JS and run there too; the shared case table
  includes the security cases (allowed link, `call` callback, `javascript:` target,
  security-touching init) so the preview refuses exactly what the saved page refuses.
- **Server renderer kind** (test-only now): the contract takes source → drawing HTML,
  with Javadoc stating what a real one (PlantUML) must handle — sandbox mode (`!include`
  file/URL access off), output sanitizer allowlist, render-cache interaction, size and
  time limits, failure → source + error. The test renderer proves the envelope, the
  sanitizer path and the failure path.
- **Links**: the Mermaid link extractor parses `click` lines; `/wiki/PageName` (optional
  `#section`) is reported as a local page link (see *Link collection* above for where it
  is called from). `http(s)` targets are allowed and clickable, not tracked. Page links are
  written `/wiki/PageName`, which is also a working URL as-is.
- **Sanitizer allowlist** additions for the envelope attributes, value-pattern-restricted.

### Browser: renderer registry (wikantik-frontend)

- A registry keyed by the same language names; each entry lazily imports its library.
  Mermaid entry: `securityLevel` one notch below `strict` (`antiscript`, so links work,
  scripts don't — builder verifies the exact level that permits `click` hrefs and nothing
  else), theme follows the wiki light/dark theme.
- One shared pass `renderFences(root, isStale)` finds `figure.wk-fence[data-fence-kind=browser]`,
  dispatches, and is: idempotent (marker attribute), stale-safe (no DOM writes after the
  view moved on), lazy (no import when there are no fences), and isolated (one failure
  doesn't stop the others).
- **What the reader sees**: the drawn SVG, fit to column width; when it's scaled down
  enough that text gets hard to read, a full-size view (builder's choice of dialog vs. new
  tab). A "Show source" toggle with a copy button; the source `<pre>` stays in the DOM
  (hidden visually when drawn) so text-reading tools still see it. Before the library
  loads, the source shows (no empty box). Parse failure → source plus Mermaid's error,
  clearly marked. Re-draws on theme switch. SVG gets `role="img"` and an accessible label.
- Used by `PageView` and by both editor previews (react-markdown `code`/`pre` component
  override using the shared rule; CodeMirror live-preview widget), so the preview matches
  the saved page. Editor previews must not re-render a diagram on every keystroke when its
  source hasn't changed (memoize by source, like `codeHighlight.js`'s cache).
- `highlightCodeBlocks` and `addCopyButtons` skip rendered-fence envelopes.

### Content follow-ups (in scope)

- **Corpus sweep**: run every prod Mermaid fence through Mermaid's parser (scripted,
  headless); produce a pass/fail list; repair failures on prod through the admin MCP tools
  (per the wiki-content skill: MCP only). **System pages** (e.g. `About`, which has a
  Mermaid fence) refuse MCP writes: fix them in `wikantik-wikipages/en/src/main/resources/`
  and push to prod with `bin/remote.sh pages-push` (the established system-page route).
  Mirror repairs into `docs/wikantik-pages/` where those pages exist.
- **`EditPageHelp`** (a system page — source `wikantik-wikipages/en/src/main/resources/EditPageHelp.md`,
  prod via `pages-push`): document rendered fences, the `example` modifier and the link form.
- **`WikantikEvolutionFromJSPWiki`**: its Mermaid claim becomes true — no edit needed.
- **Content agent** (`~/source/wiki-content-agent`): `AGENTS.md` learns the `example`
  modifier and the `/wiki/PageName` click-link convention; `bin/wikantik` (Python) counts
  only *rendered* fences as "has a diagram" (not `example` ones). Diagram **parse checking**
  is a lint finding (a `[REQUIRED]` fix in `inspect`/`lint`, not a hard block on
  `create`/`update`), done by calling the corpus-sweep script from the jspwiki checkout
  (path configurable; when absent, warn and skip the check). So the sweep script must accept
  files/stdin and emit machine-readable results; no Node dependency is added to the
  content-agent repo. *(Designer's call.)* **Both files already have uncommitted local changes** — edit on top of them,
  don't revert or overwrite.
- Publish this design to the live wiki under the relevant hub (project convention).

## Verification (acceptance)

- JUnit: shared case table; registry dispatch; unregistered languages byte-identical to
  today; `example` fences unchanged; envelope + escaping; sanitizer keeps the envelope;
  security refusals; link extraction feeding backlinks/broken links; links in `example`
  fences ignored; the test-only server renderer's success and failure paths.
- vitest: the same case table; pass idempotency, stale-guard, lazy loading (no import
  without fences), error display, theme re-render, both editor previews.
- Corpus sweep report: every prod diagram parses (after repairs).
- Selenide IT fixture page: a drawn SVG, an `example` fence left as code, a broken diagram
  showing its error, a clickable diagram link; plus the target page's backlinks include
  the diagram's page.
- Manual browser pass on a local deploy over real pages (`WikantikArchitecture` included),
  light and dark, narrow viewport.
- Full gate: `bin/run-tests.sh --parallel 4`.

## Left to the builder

Class/attribute names; Java package and type names; whether the server registry is a
static list or discovered (it must be trivially extensible and documented either way); the
full-size view mechanism; the corpus-sweep script's form; exact Mermaid version (current
stable, pinned).
