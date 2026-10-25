# PlantUML Rendered Fences — Design

Status: draft for review, 2026-10-25. Builds on the rendered-fences hook shipped in 2.5.0
(`docs/superpowers/specs/2026-10-24-rendered-fences-design.md`; wiki page `RenderedFencesDesign`).

## Intent

Wikantik pages need real UML notation, which Mermaid cannot provide:

- **Activity diagrams** with explicit fork/join synchronisation bars, swimlanes, partitions, decisions and loops.
- **Class diagrams** that go deep into structure: specialisation and realisation, abstract classes, interfaces,
  generics, stereotypes, packages, visibility, multiplicities, composition and aggregation.

The owner said that if PlantUML could not draw activity diagrams, this would be a false start. The spike
(below) showed that it can.

People and content agents write these diagrams by hand and edit them by hand. If a diagram is meant to come from
code, that is the agent's job; the wiki does not generate diagrams from source.

The product must stay commercially licensable. Nothing GPL, or otherwise viral, may enter the WAR.

## Spike findings (2026-10-25, throwaway; nothing committed)

Tested: `net.sourceforge.plantuml:plantuml-mit:1.2026.9` (17.9 MB), Maven Central, SHA-1 verified.

- **Activity diagrams** (`fork` / `fork again` / `end fork`, `split`, swimlanes, partitions, `while`, notes) render
  correctly. They use PlantUML's own layout engine. Output was byte-identical with and without Graphviz installed,
  so docker1 needs nothing.
- **Class diagrams** with the built-in Graphviz port ("Smetana", `!pragma layout smetana`) come out close to real
  Graphviz. The owner judged the quality **good enough, so no Graphviz install**. One visible weakness: a note's
  connector was drawn straight through other boxes.
- **Speed** in a warm JVM: 10–25 ms per typical diagram, about 140 ms for 120 classes. The first render in a JVM
  takes about 350 ms.
- **Failure:** a 300-class / 600-edge diagram threw a `StackOverflowError` inside Smetana. PlantUML caught it
  internally. Limits are mandatory (see below).
- **Errors are structured.** `SourceStringReader.getBlocks()` → `BlockUml.getDiagram()` returns a `PSystemError`
  that carries the line number and the messages.
- **Sandbox** (`PLANTUML_SECURITY_PROFILE=SANDBOX`):
  - `!include` of a file and of a URL fails.
  - `%getenv` returns empty.
  - `%file_exists` reports false.
  - Local and remote `<img:…>` are not loaded.
  - The bundled stdlib (`!include <C4/…>`) still works, because it is inside the jar.
- **The SVG output is plain:** `svg, g, defs, a, ellipse, line, path, polygon, rect, text`. There is no script,
  `foreignObject` or embedded HTML. `[[/wiki/Page]]` becomes `<a href="/wiki/Page" …>`.
- **Dark mode:** PlantUML's own `--dark-mode` rendering looks right.
- **Stripping Ditaa** (`zip -d jar 'org/stathissideris/*'`) leaves class, activity and error rendering fully
  working.

The spike harness and images are in the session scratchpad. They are not needed to build this.

## License findings and decisions (owner-approved)

Source-header audit of the MIT sources jar:

| Part | License | Decision |
|---|---|---|
| PlantUML core (Arnaud Roques) | MIT (POM, `-license`, headers) | Use. 2,672 files also carry a leftover GPL-3 header from the same sole author; the MIT grant governs this artifact. Record that explanation in the third-party notice. |
| Smetana (Graphviz port, © AT&T), `gen/`, `h/`, `smetana/` | EPL-1.0, file-level weak copyleft | **Keep**, because class diagrams need it. Add a notice and a pointer to the sources jar. The WAR already ships EPL-2.0, CDDL and MPL-1.1 jars, so this is the same category. |
| Ditaa (© Sideris), `org/stathissideris/` | LGPL-3.0-or-later | **Strip from the shipped jar.** It is in every PlantUML flavour (MIT, ASL and EPL were checked), and we never use it. |
| Zopfli; Base64Coder | Apache-2.0; multi-licensed incl. MIT | Fine. |
| Stdlib icon sets (AWS, Azure, GCP, Office, Font Awesome, Twemoji…) | Various non-copyleft (CC-BY, vendor terms) | **Refuse `!include <…>`** except an allow-list (C4, which is MIT). This also narrows what an author can reach. |

PlantUML's license says generated images belong to the author of the source.

PlantUML's license also says it may show sponsored messages on its *welcome and error images*. **Wikantik never
shows a PlantUML-generated error or welcome image.** Errors come from the structured `PSystemError`, drawn by
our own envelope.

## Goals

1. A ` ```plantuml ` fence draws as a diagram on the server, as SVG, in every view that shows rendered page HTML:
   - the reader;
   - server-rendered `/wiki/*` for crawlers and readers without JavaScript;
   - `![[embeds]]`.
2. ` ```plantuml example ` stays an ordinary code block (the existing shared `example` word).
3. The source is always in the HTML (the existing hidden `<pre class="wk-fence-source">`). `?format=md`, the agent
   view and the change feed are unchanged.
4. Both editor previews (the preview pane and the in-editor live preview) draw PlantUML by asking the server.
   - They redraw about 500 ms after typing stops.
   - The last good drawing stays visible during a redraw.
   - A syntax error shows PlantUML's message with its line number.
5. Light and dark drawings both exist, and the reader's current theme picks between them with CSS. No JavaScript
   is required, and a theme switch redraws nothing.
6. `[[/wiki/Page]]` links (including the `[[/wiki/Page label]]` / `[[/wiki/Page{tooltip}]]` forms) become real wiki
   links in backlinks, the Page Graph and the broken-links report. This uses `pageLinks()`, as Mermaid `click`
   links do. External `http(s)` links are clickable but not tracked.
7. The same toolbar as Mermaid: Show source, Copy, and Expand when the diagram is shrunk to fit.
8. A refused or failed diagram shows its source plus a reason, never a blank space. This is the existing
   `data-fence-error` behaviour.

## Non-goals

- Installing Graphviz anywhere. The ELK layout.
- PlantUML diagram types other than `@startuml`: no `@startgantt`, `@startmindmap`, `@startjson`, `@startsalt` or
  `@startditaa`. Within `@startuml`, every UML diagram type and C4 is allowed. *(My call: the scope was UML, and
  Mermaid already covers gantt and mindmap. Adding a type later is a one-line policy change.)*
- Generating diagrams from code.
- Rewriting diagram links on page rename. This is deferred, as it is for Mermaid.
- PNG output, a PlantUML server, or Kroki.
- Vega-Lite. It is the next renderer, not this one.

## Anti-goals (failure even if it "works")

- Any GPL or LGPL code in the WAR.
- PlantUML reading server files, environment variables or URLs, at render time or in the preview endpoint.
- A pathological diagram stalling page renders, exhausting threads, or crashing the JVM.
- A transient failure (timeout, busy) getting cached as the page's rendering.
- An author smuggling hand-written SVG or script into a page because SVG is now allowed somewhere.
- PlantUML advertising or welcome images reaching a reader.

## Constraints and decisions

**Syntax.**
- The language key is `plantuml`. *(My call: no `puml` alias. The registry rejects duplicate keys, and one name
  keeps the agents consistent.)*
- `@startuml` / `@enduml` are optional. When the source has none, the renderer wraps it.
- A source whose first `@start…` line is anything other than `@startuml` is refused (`unsupported-diagram`).
- `!pragma layout smetana` is always in force. The renderer forces it; authors don't write it.

**Policy (`check()`), fail-closed, in the same spirit as the Mermaid policy.** The sandbox is the primary
guarantee and the policy is defence in depth. It refuses:
- any `!include*` / `!import` / `!includeurl` / `!includesub` / `!includedef`, except `!include <C4/…>`;
- any `%getenv`, `%load_json`, `%file_exists`, `%dirpath`, `%filename` or similar file/env built-in;
- `<img:` and `<img src` in labels;
- an embedded `{{ … }}` sub-diagram (`embedded-diagram`): it is laid out on its own, outside the forced Smetana
  pragma and the `@startuml`-only check, so PlantUML would run the Graphviz binary for it. Independently, the
  sandbox points `GRAPHVIZ_DOT` at a non-existent path, so no source can make PlantUML execute `dot`;
- `[[…]]` links whose target is not a relative `/wiki/…` path, `#anchor` or `http(s)://`;
- a source over the size cap.

`check()` must never throw. The builder owns the exact reason codes (lowercase-hyphen, per the existing sanitizer
pattern).

**The PlantUML dependency is isolated in its own module.** It follows the `wikantik-ingest` pattern:
- A new module (suggested name `wikantik-plantuml`) owns the PlantUML jar. It exposes a pure, wiki-agnostic API:
  source → light SVG + dark SVG, or a structured error.
- It handles the sandbox, limits and SVG scrubbing.
- The Ditaa classes are stripped at build time, for example with maven-shade with an `org/stathissideris/**`
  exclude. The jar that ends up in the WAR must contain no `org/stathissideris` entries, and a test or build check
  enforces that.
- `wikantik-main` gets a thin `PlantUmlFenceRenderer` (kind `SERVER`) that adapts it to `RenderedFenceRenderer`
  and registers it in `RenderedFenceRegistry.defaults()`.

**Version.** Pin an exact `plantuml-mit` version, choosing the newest release that is at least two weeks old at
implementation time. 1.2026.9 was eight days old during the spike.

**Sandbox.** Set `PLANTUML_SECURITY_PROFILE=SANDBOX` process-wide before PlantUML's first use. A test must prove
that `!include /etc/hostname` fails *with `check()` bypassed*, so the sandbox is proven on its own. If PlantUML
reads the profile only from the environment (not from a system property), the builder must find the in-process
equivalent and prove it with that test.

**Where the SVG goes. This is a deliberate change from the hook's current Javadoc.** The Javadoc says a server
drawing "must pass `WikantikHtmlSanitizer`", which would mean adding an SVG allow-list to the sanitizer. That
would also let *authors* hand-write SVG into any page whenever `allowHTML` is on. Instead:
- The sanitizer policy stays unchanged, so hand-written `<svg>` is still stripped.
- During Markdown rendering, the fence renderer draws nothing. It emits a **placeholder** naming the diagram:
  a hash of the source, plus an HMAC of that hash under a per-JVM secret, so page text cannot forge one.
  Placeholders are *stable* across renders.
- The placeholders are resolved into drawings **once, at the outermost point where rendered HTML leaves the
  rendering manager**. That point is after the last sanitize pass and after the HTML cache and `EmbedBodyCache`
  reads. Every path that serves page HTML must go through it: the SPA page API, SSR `/wiki/*`, embeds, and the
  preview/convert endpoints. The builder must enumerate those paths and prove each one with a test.
- Consequences, all intended:
  - The HTML and embed caches store placeholders, never drawings. Nested embed sanitizing can't strip a drawing,
    because none exists yet at that point.
  - A transient failure happens only at resolve time, so it **can never be cached in a page cache**. That removes
    any need to plumb an "uncacheable" flag from Flexmark's `NodeRendererContext`, which can't reach the request
    `Context` anyway.
  - A placeholder with a bad HMAC resolves to nothing.
- `pageLinks()` and `check()` still run at parse/render time, as they do now. Only the drawing is deferred.
- The drawings are trusted because they come only from PlantUML, run through our own narrow SVG scrub:
  - an element allow-list matching PlantUML's output;
  - presentation attributes only;
  - no `on*` attributes and no `style` that can load URLs;
  - `href` / `xlink:href` limited to relative `/wiki/…`, `#…` or `http(s)`;
  - no `foreignObject` / `script` / `image`.
- Update the `RenderedFenceRenderer` Javadoc to match.

The splice must work whether `allowHTML` is on or off.

**Duplicate SVG ids.** Every page will hold two variants of each diagram, and possibly several diagrams. The scrub
must prefix every `id` and every `url(#…)` / `href="#…"` reference with a per-drawing, per-variant prefix, so that
`<defs>` never cross-bind. The Expand dialog clones the visible variant, and it must not reintroduce duplicate ids
either.

**Limits.** All values are configurable. Every key is declared in `ini/wikantik.properties` with section,
description, `Type:` and default, as required by `ConfigSurfaceDriftTest`.
- Source size cap: suggested 64 KB.
- Output size cap per variant: 512 KB, measured on PlantUML's raw SVG before the scrub (final review: 2 MB per
  variant let one page and the cache pin gigabytes).
- Wall-clock timeout per render: suggested 5 s.
- Renders run on a dedicated bounded pool.
  - Suggested maximum of 2 concurrent renders, with a small queue.
  - Threads are created with a large stack (suggested 16 MB), so deep Smetana recursion fails cleanly instead of
    taking a request thread down.
  - Java cannot kill a runaway render, so a timed-out render keeps its pool slot until it finishes.
  - A full pool refuses with a "busy" reason instead of blocking the page. So does a pool whose every thread is
    held by a render already past its timeout, and a caller whose render is still queued after waiting the timeout
    (that render stays queued, runs and is cached).
- **Slow and over-complex diagrams.** These rules exist so one bad diagram can't starve the pool. *(My calls.)*
  - A `StackOverflowError` is **deterministic**. It is cached as a refusal (`too-complex`) like a syntax error.
  - A timeout is cached as a refusal (`too-slow`) for a short TTL (suggested 10 min). After that it may be retried
    once. A diagram that times out again gets the refusal cached for the normal cache lifetime.
  - While that diagram's render is still running, a second request for the same source joins the in-flight
    render instead of starting another one, so one diagram never holds more than one slot.
  - Only "busy" (pool full) and unexpected exceptions are purely transient and uncached.
  - **Per-page budget:** at most N diagrams resolved per page response (suggested 20), splicing at most
    `pageMaxBytes` of drawing text (8 MiB), within a total wall-clock budget (suggested 10 s). Diagrams beyond the
    count or size limit resolve to `page-limit` (the same on every view), beyond the time budget to `page-budget`
    (reload may help); both are transient and never cached. The light and dark variants are produced in one render job, not two.
- **Kill switch:** suggested key `wikantik.fences.plantuml.enabled`, default true, read at engine startup.
  - When it is off, the envelope and `check()` still apply and `[[…]]` links **still count as references**. Only
    the drawing is replaced, by a `plantuml-disabled` refusal that shows the source.
  - So flipping the switch never changes the reference graph and never needs a rescan. *(My call.)*
- **Configuration path.** `RenderedFenceRegistry.defaults()` is a static singleton with no configuration
  (`RenderedFenceRegistry.java:29-31`, captured `static final` in `MarkdownDocument.java:64-65`). The PlantUML
  renderer's parse-time half (`check`, `pageLinks`, placeholder) needs no configuration. The configured half (pool,
  limits, cache, kill switch) lives in the resolve-time service, which the engine builds from properties. The
  builder decides how the two meet.

**Caching.**
- An in-memory LRU of deterministic outcomes: "drawn" (both variants), syntax errors, `too-complex` and `too-slow`
  as above, plus `render-crashed` (PlantUML's own crash report, deterministic for its source), which is retried once
  after the `too-slow` TTL like a timeout. The LRU is bounded by entries and by approximate bytes
  (`cacheMaxBytes`, 64 MiB). The key is a hash of the source plus the PlantUML version.
- Page caches hold placeholders only (see above), so they never hold a PlantUML outcome of any kind.

**Contract change.** `RenderedFenceRenderer.renderDrawing(String) → String`, which throws on failure and which the
node renderer reduces to `render-failed` (`RenderedFenceRenderer.java:79-82`, `RenderedFenceNodeRenderer.java:150`),
does not fit. The server kind needs:
- a placeholder at render time;
- a resolve step that returns a structured result: two variants, or a reason code plus an optional message and
  line number.

Change the interface and its Javadoc, and keep the test-only server renderer working through the new shape.
Mermaid is unaffected.

**Editor-preview endpoint.**
- A new REST endpoint, suggested `POST /api/fences/render` with `{language, source, page}`. It returns
  `{lightSvg, darkSvg}` or `{error, reason, line?}`. The response is the same scrubbed SVG, and the endpoint runs
  the same `check()`, cache and limits.
- Auth: the caller must be authenticated, the same bar as the existing `POST /api/convert/markdown-to-html`
  (`ConvertResource.java:68-73`). That endpoint already renders arbitrary markdown for any authenticated user and
  would reach PlantUML once it is registered, so a stricter gate here would protect nothing. Both endpoints resolve
  placeholders through the same pool, cache and per-response budget, and those limits are the real DoS bound.
  It is CSRF protected like the other mutating-shape endpoints. *(My call.)*
- Registration: in `web.xml`, plus the CLAUDE.md `/api/*` servlet count.
- The preview never uses the browser's own PlantUML (there is none). For server-kind fences the frontend registry
  entry's "draw" step is a fetch.
- **No JS port of the policy is needed for PlantUML**, because the server is the only policy. The fetched SVG is the
  same server-scrubbed output that page HTML carries, so it has the same trust. *(My call: no DOMPurify step.
  DOMPurify is only a transitive dependency through Mermaid today, and adding it directly falls under the npm
  supply-chain posture for no added safety.)*

**Frontend.**
- `drawFence` currently skips server-kind figures entirely. It must now give them the shared toolbar (Show source,
  Copy, Expand) and the Expand overflow detection.
- Server-drawn figures are emitted with `data-fence-state="drawn"`, so the existing `fences.css:24` rule hides
  their source without JavaScript.
- **Theme.** The light and dark variants are two sibling drawing hosts. Today the only theme signal is
  `html[data-theme="dark"]`, which `useDarkMode.js` sets from JavaScript (`globals.css:98`), and no CSS in the
  frontend uses `prefers-color-scheme`.
  - The dark variant shows under `[data-theme="dark"]`.
  - In every other case, including no JavaScript, the light variant shows.
  - *(My call: a no-JS reader with a dark OS theme gets the light drawing. A `prefers-color-scheme` rule would
    contradict the app's own theme toggle.)*
- `rehypeRenderedFences` and `WikiFence` (pane preview) and `livePreview/widgets.js` (in-editor preview) learn the
  server kind: debounce, fetch, keep the last good drawing, and show the error with its line.
- Each drawing is labelled `role="img"` with an `aria-label`, using the diagram's `title` when it has one.

**Third-party notice.** Add PlantUML (MIT, plus the leftover-GPL-header explanation) and Smetana (EPL-1.0, with
the source pointer) to the project's notice (`NOTICE` at the repo root, or wherever the WAR's notices are
assembled). State that Ditaa is stripped.

## Rollout

- `EditPageHelp` (a system page, pushed with `bin/remote.sh pages-push`) gains PlantUML under its Diagrams
  section, showing a fork/join activity example, a class-hierarchy example and the link syntax.
- The `RenderedFencesDesign` wiki page and the `docs/wikantik-pages` mirror are updated to cover the server path,
  through MCP.
- The content agent (`~/source/wiki-content-agent`) guidance says to use PlantUML for class and activity diagrams
  and Mermaid for everything else. Its linter's parse check covers PlantUML through a small jspwiki-side checker,
  analogous to `check-mermaid.mjs`, built on the new module's API. *(My call: a `bin/` script that runs the module
  jar, so the agent repo gains no Java dependency.)*
- Release, then deploy. No migration, because there is no schema change. The existing reference-manager rescan is
  not needed, because no stored pages contain ` ```plantuml ` yet. The builder should verify that on prod with
  `list_pages_by_filter` / search first.

## What is left to the builder

- Module name and API shape, reason-code names, exact limit values, cache size.
- Where exactly the resolve step sits, and how it reaches every HTML-serving path (including embeds, SSR and the
  convert endpoint).
- The SVG scrub's exact allow-list, derived from PlantUML's real output across all UML diagram types (one sample
  of each type).
- How the Ditaa strip is done, and the check that proves it.
- Test layout. TDD as usual. At minimum it must cover:
  - each policy refusal;
  - the sandbox, with the policy bypassed;
  - the timeout, busy and stack-overflow paths;
  - joining an in-flight render, and the per-page budget;
  - page caches holding placeholders only;
  - a forged placeholder resolving to nothing;
  - a diagram drawn inside an `![[embed]]` with `allowHTML` on;
  - id prefixing;
  - links reaching `pageLinks()` and the reference manager;
  - endpoint auth;
  - hand-written `<svg>` still being stripped;
  - a Selenide IT that draws one class and one activity diagram in the reader and the preview.
