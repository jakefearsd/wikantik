# Rendered Fences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A general "rendered fence" hook — server registry + browser registry — with Mermaid as the first (browser-kind) renderer, so the 63 prod diagrams draw for readers while every machine consumer keeps the source text.

**Architecture:** Server (Flexmark, wikantik-main) wraps registered, non-`example` fences in one fixed `<figure class="wk-fence">` envelope that always carries the escaped source, enforces a per-renderer safety policy, and reports diagram page links into the ReferenceManager's regex scan. Browser (wikantik-frontend) runs one lazy, idempotent DOM pass that draws envelopes; the editor preview pane and live-preview widgets use the same drawing core. One shared JSON case table pins the fence rule and the Mermaid policy across Java and JS.

**Tech Stack:** Java 25, Flexmark, OWASP HTML sanitizer, JUnit 5; React 19, react-markdown 10 / unified, CodeMirror 6, Vite 8 (Rolldown), vitest 4 + happy-dom; Mermaid **12.1.0**; Selenide.

**Spec:** `docs/superpowers/specs/2026-10-24-rendered-fences-design.md` — read it first; this plan argues from it.

## Global Constraints

- Mermaid pinned **exactly** `"mermaid": "12.1.0"` (published 2026-10-02, >14 days old; 12.2.0 is too new). Install with `npm install --ignore-scripts --save-exact mermaid@12.1.0`; CI path stays `npm ci --ignore-scripts`.
- `GitLabExtension.RENDER_BLOCK_MERMAID` stays `false` (`MarkdownDocument.java:121`).
- CSP (`CSPFilter`) is **not** changed: `script-src 'self'`, no `unsafe-eval`.
- Unregistered fence languages (` ```java `, ` ```math `, …) and `example` fences render **byte-identically** to today.
- The envelope's source text equals the stored fence content exactly (escaped, never rewritten).
- Never an empty catch: every caught exception logs at least `LOG.warn()` with context (Java) / `console.warn('[fences] …', …)` (JS).
- New Java files carry the ASF license header like their neighbours. TDD: each test is seen failing before its code exists.
- Shared fixture lives at `wikantik-frontend/src/utils/__fixtures__/renderedFences.json`; Java tests read it via `Path.of("..", "wikantik-frontend", "src", "utils", "__fixtures__", "renderedFences.json")` (precedent: `CalloutExtensionTest`, `WikiLinkParityTest`).
- Commits: 1–3 line messages ending with the two attribution lines from the session; stage files by name.

## Review Focus

1. **Fences nested in list items / blockquotes, `~~~` fences, 4+-backtick fences, CRLF line endings** — Flexmark, the reference scanner and react-markdown must all find the same rendered fences with the same source. Pinned by the `documents` fixture section (Tasks 3, 4, 6).
2. **An unclosed fence at end of document** — CommonMark runs it to EOF; scanner and Flexmark agree (fixture case in Task 3, asserted by Task 4).
3. **One broken diagram among several on a page** — the others still draw; the broken one shows source + error (Task 5 test `isolatesFailures`).
4. **HTML re-injection / quick navigation** (auth refetch resets `dangerouslySetInnerHTML`; page changes mid-load) — pass is idempotent and writes nothing once stale (Task 5 tests `idempotent`, `staleWritesNothing`).
5. **Source text that looks like markup** (`</code></pre><script>alert(1)</script>`, `&amp;`, `<br/>`) — served escaped as text, Mermaid still receives the original characters (Task 3 test `escapesSourceAsText`, Task 5 test `passesDecodedSourceToRenderer`).

---

## File Structure

**wikantik-main** `src/main/java/com/wikantik/markdown/extensions/fences/` (new package, with `package.html` like siblings):
| File | Responsibility |
|---|---|
| `FenceInfo.java` | info-string grammar (language + modifiers + `example`) |
| `FenceKind.java` | `BROWSER` / `SERVER` |
| `FenceVerdict.java` | allowed / refused-with-reason |
| `RenderedFenceRenderer.java` | the extension contract (**the guide for future renderers lives in its Javadoc**) |
| `RenderedFenceRegistry.java` | language → renderer; `defaults()` = Mermaid |
| `MermaidFenceRenderer.java` | Mermaid policy + `click` link extraction |
| `RenderedFenceExtension.java` / `RenderedFenceNodeRenderer.java` | Flexmark hook + envelope |
| `RenderedFenceScanner.java` | raw-text fence scan (no Flexmark) for the reference path |

Modified: `parser/markdown/MarkdownDocument.java`, `parser/markdown/WikantikHtmlSanitizer.java`, `pagegraph/references/DefaultReferenceManager.java`, `parser/markdown/MarkdownParser.java`.

**wikantik-frontend** `src/utils/fences/` (new): `fenceInfo.js`, `mermaidPolicy.js`, `registry.js`, `mermaidRenderer.js`, `drawFence.js`, `renderFences.js`, `rehypeRenderedFences.js`; `src/components/WikiFence.jsx`; `src/styles/fences.css` (imported by `article.css`'s importer — follow how `article.css` is loaded); `scripts/check-mermaid.mjs`. Modified: `package.json`, `vite.config.js`, `components/PageView.jsx`, `components/PageEditor.jsx`, `utils/livePreview/ranges.js`, `utils/livePreview/widgets.js`, `utils/codeHighlight.js`.

**Fixed envelope** (Java emits it; JS reads and also emits it in the editor preview):
```html
<figure class="wk-fence" data-fence="mermaid" data-fence-kind="browser">
<pre class="wk-fence-source"><code class="language-mermaid">…escaped source…</code></pre>
</figure>
```
- Refused: add `data-fence-error="<reason>"`. Reason codes: `callback`, `unsafe-link`, `unsafe-config`, `render-failed`.
- SERVER kind: a `<div class="wk-fence-drawing">…</div>` before the `<pre>`. The browser pass never touches `data-fence-kind="server"`.

---

### Task 1: Info-string grammar + shared fixture (Java)

**Files:**
- Create: `wikantik-frontend/src/utils/__fixtures__/renderedFences.json`
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/fences/FenceInfo.java`, `package.html`
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/fences/FenceInfoTest.java`

**Interfaces:**
- Produces: `public record FenceInfo( String language, boolean example, List<String> modifiers )` with `public static FenceInfo parse( String info )`. `language` is the first whitespace-delimited word, lowercased (`Locale.ROOT`), `""` for null/blank. `modifiers` = remaining words lowercased, in order. `example` = `modifiers.contains("example")`.
- Produces: fixture top-level object `{ "infoStrings": [...], "documents": [...], "mermaidPolicy": [...] }`; this task fills `infoStrings` and leaves the other two `[]`.

- [ ] **Step 1: Write the fixture's `infoStrings` cases.** Each `{ "info": <string|null>, "language": "...", "example": bool, "modifiers": [...] }`. Must include: `"mermaid"`, `"mermaid example"`, `"Mermaid EXAMPLE"`, `"  mermaid\texample  "`, `"mermaid example title=x"` (modifiers `["example","title=x"]`), `"mermaid exampled"` (example false), `"mermaid future-flag"`, `""`, `null`, `"java"`, `"math"`, `"{.mermaid}"` (language `{.mermaid}` — not mermaid).
- [ ] **Step 2: Write `FenceInfoTest.matchesSharedFixture`** — Gson-load `infoStrings`, assert `parse(info)` equals each expected record; plus `nullAndBlankYieldEmptyLanguage`.
- [ ] **Step 3: Run** `mvn test -pl wikantik-main -Dtest=FenceInfoTest -q` → FAIL (class missing).
- [ ] **Step 4: Implement `FenceInfo`** (split on `\s+` after `strip()`).
- [ ] **Step 5: Run** the same command → PASS.
- [ ] **Step 6: Commit** `feat(fences): info-string grammar + shared case table`.

### Task 2: Renderer contract, registry, Mermaid policy + links (Java)

**Files:**
- Create: `FenceKind.java`, `FenceVerdict.java`, `RenderedFenceRenderer.java`, `RenderedFenceRegistry.java`, `MermaidFenceRenderer.java` (package above)
- Modify: fixture `mermaidPolicy` section
- Test: `RenderedFenceRegistryTest.java`, `MermaidFenceRendererTest.java`

**Interfaces:**
- Consumes: `FenceInfo` (Task 1).
- Produces:
  ```java
  public enum FenceKind { BROWSER, SERVER }
  public record FenceVerdict( boolean allowed, String reason ) {
      public static final FenceVerdict ALLOWED;           // (true, null)
      public static FenceVerdict refused( String reason );
  }
  public interface RenderedFenceRenderer {
      String language();                       // lowercase registry key
      FenceKind kind();
      FenceVerdict check( String source );     // safety policy; must not throw
      Set< String > pageLinks( String source ); // local page names, '#section' stripped; empty when refused
      default String renderDrawing( String source ) { throw new UnsupportedOperationException( language() + " is a browser renderer" ); }
  }
  public final class RenderedFenceRegistry {
      public static RenderedFenceRegistry defaults();                       // Mermaid only; cached singleton
      public static RenderedFenceRegistry of( RenderedFenceRenderer... r ); // duplicate language -> IllegalArgumentException
      public Optional< RenderedFenceRenderer > forLanguage( String language );
      public Optional< RenderedFenceRenderer > forInfo( FenceInfo info );   // empty when info.example() or unregistered
  }
  public final class MermaidFenceRenderer implements RenderedFenceRenderer  // language "mermaid", BROWSER
  ```
- **Mermaid policy rules** (apply to each line, `strip()`ped):
  - `click` lines: allowed only as `click <id> "<url>" ["<tooltip>"] [_self|_blank|_parent|_top]` or `click <id> href "<url>" …`. Anything else (`call …`, a bare callback name) → `refused("callback")`.
  - URLs in `click` lines and in sequence-diagram `link <actor>: <label> @ <url>` / `links <actor>: {json}` lines: allowed when they start with `/`, `#`, `http://` or `https://` (case-insensitive), or contain no `:` before the first `/`. Otherwise → `refused("unsafe-link")`. This catches `javascript:`, `data:` and `vbscript:` including mixed case and leading whitespace/entities.
  - `%%{init:` / `%%{initialize:` directives, and a leading `---` YAML front-matter `config:` block, naming any of `secure`, `securityLevel`, `startOnLoad`, `maxTextSize`, `maxEdges`, `suppressErrorRendering`, `dompurifyConfig` → `refused("unsafe-config")`. Other directive keys (theme, flowchart options) are allowed.
- **Links:** `/wiki/<Name>` and `/wiki/<Name>#sec` (URL-decoded) → `<Name>`; everything else isn't tracked.
- **The Javadoc on `RenderedFenceRenderer`** is the future-renderer guide. It must state:
  - **Browser kind:** register the same language in `wikantik-frontend/src/utils/fences/registry.js`, and add policy cases to the shared fixture.
  - **Server kind:** `renderDrawing` output must pass `WikantikHtmlSanitizer`, so SVG needs a narrow allowlist added there. The renderer runs in its sandbox mode (PlantUML: `PLANTUML_SECURITY_PROFILE=SANDBOX`, no `!include` file/URL access) under a size and time cap. A failure throws, and the node renderer turns it into `render-failed`. Output is cached by the existing render caches.
  - **Rename (deferred):** a future rewrite must touch only that renderer's link syntax, only inside non-`example` rendered fences.

- [ ] **Step 1: Fill the fixture's `mermaidPolicy`.** Cases are `{ "source", "allowed", "reason", "pageLinks" }` and must include:
  - plain flowchart (allowed, no links)
  - `click A "/wiki/Foo"` → `["Foo"]`
  - `click A href "/wiki/Foo#Bar" "tip" _blank` → `["Foo"]`
  - `click A "https://example.com"` → allowed, `[]`
  - `click A call doThing()` → `callback`
  - `click A doThing` → `callback`
  - `click A "javascript:alert(1)"` → `unsafe-link`
  - `click A " JavaScript:alert(1)"` → `unsafe-link`
  - `click A "data:text/html,x"` → `unsafe-link`
  - sequence `link Alice: Docs @ javascript:x` → `unsafe-link`
  - `%%{init: {"securityLevel":"loose"}}%%` → `unsafe-config`
  - `%%{init: {"theme":"dark"}}%%` → allowed
  - YAML front matter `config:\n  securityLevel: loose` → `unsafe-config`
  - `/wiki/Some%20Page` → `["Some Page"]`
- [ ] **Step 2: Write `MermaidFenceRendererTest.matchesSharedPolicyFixture`** (asserts verdict + pageLinks per case), `refusedSourceReportsNoLinks`, and `kindIsBrowserAndRenderDrawingThrows`. Write `RenderedFenceRegistryTest`:
  - `defaultsResolveMermaidCaseInsensitively`
  - `exampleInfoResolvesToNothing`
  - `unregisteredLanguageResolvesToNothing`
  - `duplicateLanguageRejected`
- [ ] **Step 3: Run** `mvn test -pl wikantik-main -Dtest='MermaidFenceRendererTest,RenderedFenceRegistryTest' -q` → FAIL.
- [ ] **Step 4: Implement** the five types.
- [ ] **Step 5: Run** → PASS.
- [ ] **Step 6: Commit** `feat(fences): renderer contract, registry, Mermaid policy`.

### Task 3: Flexmark envelope, sanitizer allowlist, server-kind proof (Java)

**Files:**
- Create: `RenderedFenceExtension.java`, `RenderedFenceNodeRenderer.java`
- Modify: `parser/markdown/MarkdownDocument.java`: add `RENDERED_FENCE_EXT = RenderedFenceExtension.create( RenderedFenceRegistry.defaults() )` to the `Parser.EXTENSIONS` list **after** `GITLAB_EXT`. Also `parser/markdown/WikantikHtmlSanitizer.java`, and the fixture's `documents` section.
- Test: `RenderedFenceRenderingTest.java` (package `fences`), `WikantikHtmlSanitizerTest.java` (add cases)

**Interfaces:**
- Consumes: `RenderedFenceRegistry.forInfo`, `RenderedFenceRenderer.check/kind/renderDrawing`.
- Produces:
  - `public static RenderedFenceExtension create( RenderedFenceRegistry registry )`, implementing `Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension`.
  - Node renderer: `FencedCodeBlock` → envelope when `forInfo(FenceInfo.parse(node.getInfo()))` is present, else `context.delegateRender()` (which is how ` ```math ` still reaches GitLab's renderer).
  - SERVER kind: on success, the `wk-fence-drawing` div with `renderDrawing(source)`. If it throws, `LOG.warn` with page/language and emit `data-fence-error="render-failed"`.
  - Refusal: `data-fence-error=<reason>`, no drawing.
- Sanitizer: on `figure`, allow `data-fence` matching `[a-z0-9][a-z0-9+-]*`, `data-fence-kind` matching `browser|server` and `data-fence-error` matching `[a-z-]+`. Nothing else is added.

- [ ] **Step 1: Fill the fixture's `documents` section.** Each case is `{ "name", "markdown", "fences": [ { "language", "source" } ] }`, listing the rendered (registered, non-example) fences in order, with `source` the exact content Flexmark yields. Cases:
  - top-level fence
  - `~~~mermaid`
  - ```` ````mermaid ```` with an inner ```` ``` ```` line
  - inside a `- ` list item (indented 2)
  - inside a `> ` blockquote
  - indented 3 spaces (still a fence)
  - indented 4 spaces (a code block, not a fence → `[]`)
  - `mermaid example` → `[]`
  - two fences, one example
  - unclosed fence at EOF
  - CRLF line endings
  - empty body
  - ` ```java ` → `[]`
  - ` ```math ` → `[]`
- [ ] **Step 2: Write `RenderedFenceRenderingTest`.** Render through the real pipeline (use `TestEngine` + `RenderingManager.textToHTML`, as `CalloutExtensionTest` does):
  - `documentsFixtureProducesEnvelopes` — the envelope sources equal the fixture. jsoup is not on wikantik-main's classpath: extract envelopes with a DOTALL regex and unescape Flexmark's entities (`&lt; &gt; &amp; &quot; &#39;`) in a small test helper
  - `unregisteredAndExampleFencesUnchanged` — HTML identical to the same doc rendered with a registry of no renderers (`RenderedFenceRegistry.of()`)
  - `mathFenceStillRendersAsMath` — `math-display` present
  - `escapesSourceAsText` — the source `</code></pre><script>alert(1)</script> &amp; <br/>` yields no `<script>` element, and the `code` text equals the source exactly
  - `refusedFenceCarriesReason` — `data-fence-error="callback"`
  - `serverKindEmitsDrawingAndSource` and `serverKindFailureEmitsRenderFailed`, via a test-only `FakeServerRenderer` (language `fake`, SERVER) in the test package
  - `envelopeSurvivesSanitizer` — `allowHTML=true` engine property; the envelope attributes and the drawing div survive
- [ ] **Step 3: Add `WikantikHtmlSanitizerTest` cases:**
  - `keepsFenceEnvelopeAttributes`
  - `dropsFenceAttributesWithBadValues` (`data-fence-kind="evil"`, `data-fence="<x>"`)
- [ ] **Step 4: Run** `mvn test -pl wikantik-main -Dtest='RenderedFenceRenderingTest,WikantikHtmlSanitizerTest' -q` → FAIL.
- [ ] **Step 5: Implement** the extension, node renderer and sanitizer change. Register the extension in `MarkdownDocument`.
- [ ] **Step 6: Run** the same tests plus the regression neighbours: `mvn test -pl wikantik-main -Dtest='RenderedFence*,WikantikHtmlSanitizerTest,MarkdownRendererTest,CalloutExtensionTest,*Math*Test' -q` → PASS.
- [ ] **Step 7: Commit** `feat(fences): Flexmark envelope + sanitizer allowlist`.

### Task 4: Diagram links into references (Java)

**Files:**
- Create: `RenderedFenceScanner.java`
- Modify:
  - `pagegraph/references/DefaultReferenceManager.java`: in `scanWikiLinks` (~line 659), add the links from `RenderedFenceScanner.pageLinks( parsed.body(), RenderedFenceRegistry.defaults() )`, and bump `serialVersionUID` `4L → 5L` (line 185). The bump forces a one-time full rescan at the first startup after deploy, which is how the 43 prod pages pick up their diagram links. Document that next to the constant.
  - `parser/markdown/MarkdownParser.collectLinks`: feed the same page links to `localLinkMutatorChain`.
- Test: `RenderedFenceScannerTest.java`; add cases to `DefaultReferenceManagerCITest.java`

**Interfaces:**
- Produces:
  ```java
  public record ScannedFence( FenceInfo info, String source ) {}
  public final class RenderedFenceScanner {
      public static List< ScannedFence > scan( String body );   // every fenced block, any info, document order
      public static Set< String > pageLinks( String body, RenderedFenceRegistry registry ); // allowed, non-example, registered fences only
  }
  ```
- **Scanner semantics: CommonMark fences without Flexmark.**
  - Opener: up to 3 spaces of indent after stripping the container prefix, then a run of ≥3 `` ` `` or `~`. A backtick info string can't contain a backtick.
  - Closer: same character, run ≥ the opener's length, only whitespace after it.
  - An unclosed fence runs to EOF.
  - CRLF is normalized.
  - Container prefixes: blockquote `>` markers and list-item content indentation. Track the container's prefix width when a fence opens, and strip that same prefix from its content lines.
  - Linear in body length.

- [ ] **Step 1: Write `RenderedFenceScannerTest`:**
  - `agreesWithFlexmarkOnDocumentsFixture` — for every `documents` case, `scan` filtered to registered non-example fences equals the fixture `fences`
  - `pageLinksFromDiagramClicks`
  - `exampleAndRefusedFencesContributeNoLinks`
  - `linearOnPathologicalInput` — 200k lines of alternating fence markers finish within 1s
- [ ] **Step 2: Add `DefaultReferenceManagerCITest` cases:**
  - `scanWikiLinksIncludesDiagramClickTargets` — body with ` ```mermaid ` + `click A "/wiki/TargetPage"` → result contains `TargetPage`
  - `scanWikiLinksIgnoresExampleFenceClicks`
- [ ] **Step 3: Run** `mvn test -pl wikantik-main -Dtest='RenderedFenceScannerTest,DefaultReferenceManagerCITest' -q` → FAIL.
- [ ] **Step 4: Implement** the scanner, the `scanWikiLinks` addition, the `collectLinks` addition and the `serialVersionUID` bump.
- [ ] **Step 5: Run** that command plus `-Dtest='*ReferenceManager*Test,RenderedFence*'` → PASS.
- [ ] **Step 6: Commit** `feat(fences): diagram click links feed page references`.

### Task 5: Browser rule modules + drawing core + reader view (frontend)

**Files:**
- Modify:
  - `wikantik-frontend/package.json`: `"mermaid": "12.1.0"`, installed with `npm install --ignore-scripts --save-exact mermaid@12.1.0`, so `package-lock.json` updates too
  - `vite.config.js`: a `mermaid` group in `codeSplitting.groups`
  - `components/PageView.jsx`: a new effect next to the `renderMath` one (~line 202)
  - `utils/codeHighlight.js`: skip `.wk-fence-source code`
- Create: `src/utils/fences/{fenceInfo,mermaidPolicy,registry,mermaidRenderer,drawFence,renderFences}.js`, `src/styles/fences.css`
- Test: `src/utils/fences/{fenceInfo,mermaidPolicy,drawFence,renderFences}.test.js`

**Interfaces:**
- Consumes: the fixture `infoStrings` / `mermaidPolicy`; the envelope from Task 3.
- Produces:
  - `parseFenceInfo(info) → { language, example, modifiers }` (same rules as Java)
  - `checkMermaid(source) → { allowed, reason, pageLinks }` (same rules as Java)
  - `registry.js`: `export const FENCE_RENDERERS = { mermaid: { check: checkMermaid, load: () => import('./mermaidRenderer.js') } }` and `export function rendererFor(fenceInfo)`, which takes a `parseFenceInfo` result and returns null when `example` or unregistered
  - `mermaidRenderer.js`: `export async function draw(host, source, { dark }) → void`. It calls `mermaid.initialize` once per theme with `{ startOnLoad: false, securityLevel: 'antiscript', theme: dark ? 'dark' : 'default', secure: [<Mermaid's default secure keys>, 'dompurifyConfig'] }`, then `mermaid.render(uniqueId, source)` → `host.innerHTML = svg`. It **never** calls `bindFunctions`, and it throws on parse error. The SVG gets `role="img"` and an `aria-label` (from the diagram's `accTitle` if present, else "Diagram").
  - `drawFence(figure, isStale = () => false) → Promise<'drawn'|'refused'|'failed'|'stale'>`:
    - reads `data-fence` and `data-fence-error`, and takes the source from `.wk-fence-source code` `textContent`
    - re-runs the JS policy (defense in depth)
    - builds `.wk-fence-toolbar` with "Show source" (toggles `wk-fence-source-open`) and "Expand" (opens a `<dialog class="wk-fence-dialog">` holding a natural-size clone, closed with Esc/backdrop; shown only when the drawing is scaled below ~80% of its natural width)
    - sets `data-fence-state`
    - refused/failed → `.wk-fence-error` with a human message (map reason codes; include Mermaid's first error line) and the source visible
    - **The source `<pre>` stays in the DOM in every state.** Copy comes from the existing `addCopyButtons` on that `<pre>`, visible whenever the source is shown; no second copy button.
  - `renderFences(root, isStale) → Promise<void>`:
    - picks up `figure.wk-fence[data-fence-kind="browser"]:not([data-fence-state])`
    - draws each independently (one failure never stops the others)
    - imports nothing when there are none
    - the first call installs one `MutationObserver` on `document.documentElement` `data-theme` (set by `hooks/useDarkMode.js:26`) that re-draws figures in state `drawn`
- **Mermaid chunk group:** add a `mermaid` group. Its `test` covers `mermaid`, `@mermaid-js`, `d3*`, `dagre-d3-es`, `elkjs`, `chevrotain`, `khroma`, `roughjs`, `stylis` and `@iconify`. Give it a priority above `vendor` and below `react`, with `includeDependenciesRecursively: false` (the `lowlight` reasoning in the existing comment applies). Extend the comment's `dist/index.html` modulepreload rule to name `mermaid`.
- **CSS** (`fences.css`):
  - `.wk-fence` is a block with margin; its drawing fits the column (`svg { max-width: 100%; height: auto }`).
  - `[data-fence-state="drawn"] .wk-fence-source:not(.wk-fence-source-open)` is visually hidden (`display:none`).
  - `.wk-fence-error` uses the existing callout warning palette tokens; only use tokens defined in both themes (see memory: undefined CSS tokens).

- [ ] **Step 1: Write the tests.**
  - `fenceInfo.test.js` and `mermaidPolicy.test.js` import the shared JSON and iterate it, mirroring Tasks 1–2.
  - `drawFence.test.js` mocks `mermaidRenderer.js` with `vi.mock`:
    - `drawsAndKeepsSourceInDom`
    - `passesDecodedSourceToRenderer`: envelope text `&lt;br/&gt;` reaches `draw` as `<br/>`
    - `refusedShowsReasonAndSource`
    - `clientPolicyRefusesEvenWithoutServerFlag`
    - `parseFailureShowsErrorAndSource`
    - `toggleShowsSource`
    - `staleWritesNothing`
  - `renderFences.test.js`:
    - `idempotent`: two runs, `draw` called once per figure
    - `isolatesFailures`
    - `noImportWithoutFences`: the `load` spy is never called
    - `skipsServerKind`
    - `redrawsOnThemeChange`
- [ ] **Step 2: Run** `cd wikantik-frontend && npx vitest run src/utils/fences` → FAIL.
- [ ] **Step 3: Install Mermaid** (exact command above), then confirm `git diff package.json` shows `"mermaid": "12.1.0"` with no caret.
- [ ] **Step 4: Implement** the modules, CSS, chunk group, `PageView` effect (same stale-cleanup shape as `renderMath`), and the `codeHighlight` skip.
- [ ] **Step 5: Run** `npx vitest run src/utils/fences src/components/PageView` → PASS. Then `npm run lint`, then `npm run build`, then `grep -c mermaid dist/index.html` → `0`: Mermaid must not be modulepreloaded.
- [ ] **Step 6: Run** `npx vitest run` (full suite) → PASS, and `npm run test:coverage` → floors hold.
- [ ] **Step 7: Commit** `feat(fences): browser registry + Mermaid drawing in the reader`.

### Task 6: Editor preview pane + live-preview widget (frontend)

**Files:**
- Create: `src/utils/fences/rehypeRenderedFences.js`, `src/components/WikiFence.jsx`
- Modify:
  - `components/PageEditor.jsx`: add `rehypeRenderedFences` to the preview rehype plugins, ordered **before** the highlight plugin, and add `'wiki-fence': WikiFence` to `previewComponents` (line 353)
  - `utils/livePreview/ranges.js` `blockCandidateOf`, `utils/livePreview/widgets.js`: a `FenceWidget` and `widgetFor` case `'fence'`
- Test: `rehypeRenderedFences.test.js`, `WikiFence.test.jsx`, additions to `utils/livePreview/ranges.test.js` and `widgets.test.js`

**Interfaces:**
- Consumes: `rendererFor`, `parseFenceInfo`, `drawFence` (Task 5).
- Produces:
  - `rehypeRenderedFences()`: a hast transform that replaces `pre > code.language-X`, where `rendererFor(parseFenceInfo('X ' + (code.data?.meta ?? '')))` is set, with an element `wiki-fence` that has properties `{ language, source }`. Verify in the test that react-markdown carries the info string's remaining words on `data.meta`.
  - `WikiFence({ language, source })`: memoized on `(language, source)`. It renders a `figure.wk-fence` with **no React children**, and an effect builds the envelope children imperatively and calls `drawFence(ref, () => stale)`.
  - Live preview:
    - `blockCandidateOf` handles a **top-level, closed** `FencedCode` node whose `CodeInfo` resolves through `rendererFor` → `{ kind: 'block', from, to, widget: { type: 'fence', language, source } }`.
    - `FenceWidget.eq` compares `language` and `source`. `toDOM` builds the envelope and calls `drawFence`; `revealOnMouseDown` applies, as it does for `MathWidget`.
    - Nested fences stay code in live preview: that's the same top-level-only rule display math has. Note it in the `blockCandidateOf` docblock.

- [ ] **Step 1: Write the tests.**
  - `rehypeRenderedFences.test.js`:
    - `documentsFixtureYieldsSameFences`: run the fixture `documents` through `unified().use(remarkParse).use(remarkGfm).use(remarkRehype).use(rehypeRenderedFences)` (add `remark-rehype` as a devDependency pinned to the lockfile's `11.1.2`, `--ignore-scripts --save-exact`) and assert the same language/source list as Java
    - `exampleStaysCode`
  - `WikiFence.test.jsx`:
    - `drawsOncePerSource`: a re-render with the same props does not call `draw` again
    - `redrawsWhenSourceChanges`
  - `ranges.test.js`:
    - `fencedMermaidBecomesBlockWidget`
    - `unclosedFenceIsNotAWidget`
    - `exampleFenceIsNotAWidget`
    - `activeLineShowsSource`: the caret inside the fence → no widget
    - `incrementalUpdateMatchesFullScan` for a fence edit, using the existing `updateBlockCandidates` equality harness
  - `widgets.test.js`: `fenceWidgetEqBySource`
- [ ] **Step 2: Run** `npx vitest run src/utils/fences src/components/WikiFence src/utils/livePreview` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command → PASS. Then run the editor cost tests (`npx vitest run src/components/PageEditor`) → PASS, with no regression in `PageEditor.previewCost.realCodeMirror.test.jsx`.
- [ ] **Step 5: Commit** `feat(fences): diagrams in the editor preview and live preview`.

### Task 7: Corpus checker script (frontend tooling)

**Files:**
- Create: `wikantik-frontend/scripts/check-mermaid.mjs`. Add the npm script `"check:mermaid": "node scripts/check-mermaid.mjs"`, and include `scripts/` in the ESLint config if it isn't covered.
- Test: `wikantik-frontend/scripts/check-mermaid.test.js`. Include `scripts/**/*.test.js` in vitest's include if needed.

**Interfaces:**
- Produces: the CLI `node scripts/check-mermaid.mjs [--json] <file|dir|->...`. It finds fences with `unified` + `remark-parse` + `remark-gfm`, the same as Task 6. It parses every non-`example` Mermaid fence with `mermaid.parse` and checks it with `checkMermaid`.
  - Output: one line per diagram, `OK|FAIL <file>:<line> <first error line>`. With `--json`, a JSON array of `{ file, line, ok, error }` instead.
  - Exit codes: `0` = all OK, `1` = any FAIL, `2` = usage error.
  - DOM shim: create a happy-dom `Window` and assign `window`, `document`, `DOMParser`, `Element`, `HTMLElement`, `Node`, `DocumentFragment` and `getComputedStyle` onto `globalThis` **before** `import('mermaid')`. This was proven in a 2026-10-24 spike against mermaid 12.1.0: the 63 prod diagrams gave 60 OK and 3 FAIL.
- [ ] **Step 1: Write the tests:**
  - `reportsOkAndFail`: tmp dir with one good and one broken fence; exit 1; JSON lists both
  - `ignoresExampleFences`
  - `stdinInput`
  - `refusedPolicyCountsAsFail`
- [ ] **Step 2: Run** `npx vitest run scripts` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command → PASS. Then `npm run check:mermaid -- ../docs/wikantik-pages` prints a report; the repo mirror is expected to have FAILs.
- [ ] **Step 5: Commit** `feat(fences): Mermaid corpus checker script`.

### Task 8: Browser integration test

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RenderedFencesIT.java` (pattern: `BacklinksPanelIT`, seeding through `RestSeedHelper.writePage`, cleanup in `finally` with `deletePageQuietly`)

- [ ] **Step 1: Write `RenderedFencesIT`.** It seeds `FenceTarget` and `FenceSource`. `FenceSource` contains:
  - a valid flowchart with `click A "/wiki/FenceTarget"`
  - a ` ```mermaid example ` fence
  - a broken diagram

  Tests:
  - `diagramDrawsAsSvg`: `figure.wk-fence[data-fence-state=drawn] svg` exists, and `figure.wk-fence .wk-fence-source code` text contains `flowchart`
  - `exampleFenceStaysCode`: `pre > code.language-mermaid` outside any `figure.wk-fence`
  - `brokenDiagramShowsError`
  - `diagramLinkNavigates`: clicking the node lands on `/wiki/FenceTarget`
  - `diagramLinkCountsAsBacklink`: `FenceTarget`'s backlinks panel lists `FenceSource`
  - `ssrHtmlCarriesSource`: an HTTP GET of `/wiki/FenceSource` with JS off (plain `HttpClient`) contains `wk-fence` and the escaped source
- [ ] **Step 2: Run** `bin/run-tests.sh --module custom-jdbc` — the IT module whose failsafe scans the selenide-tests jar, where `BacklinksPanelIT` runs (confirm with `bin/run-tests.sh --list`). It needs Tasks 3–6 built, so all six tests PASS. If a test can't be run red first because the feature already exists, say so in the task report rather than claiming red-green.
- [ ] **Step 3: Commit** `test(fences): Selenide coverage for rendered fences`.

### Task 9: Docs + content-agent updates

**Files:**
- Modify:
  - `wikantik-wikipages/en/src/main/resources/EditPageHelp.md`: a "Diagrams" section covering the ` ```mermaid ` fence, the ` ```mermaid example ` modifier, `click Node "/wiki/PageName"` links, that a broken or refused diagram shows its error, and that renames don't rewrite diagram links yet
  - `CLAUDE.md`, Rendering Pipeline section: one bullet naming `com.wikantik.markdown.extensions.fences`, the shared fixture, and "add a renderer: see `RenderedFenceRenderer` Javadoc"
  - `~/source/wiki-content-agent/.agents/AGENTS.md` (line 64 area)
  - `~/source/wiki-content-agent/bin/wikantik`: `has_mermaid` at line 202, and the checks at :374 and :733
- **The content-agent repo already has uncommitted changes in both files.** Edit on top of them. Don't revert or reformat anything else, and **don't commit in that repo**; report the diff instead.

- [ ] **Step 1: Update `EditPageHelp`.** Check that the page renders on the local deploy, after Task 10's build.
- [ ] **Step 2: Update `AGENTS.md`.** Keep the diagram requirement. Add: use `mermaid example` for syntax shown as an example; link nodes to wiki pages with `click Id "/wiki/PageName"`; never use `click … call`.
- [ ] **Step 3: Update `bin/wikantik`.**
  - `has_mermaid` matches only an opening fence line whose info is `mermaid` without `example`, via regex `^\s{0,3}(```|~~~)\s*mermaid(?![^\n]*\bexample\b)`.
  - Add a parse check. It runs `node <jspwiki>/wikantik-frontend/scripts/check-mermaid.mjs --json -`, with the content on stdin. The jspwiki path comes from env `WIKANTIK_REPO`, defaulting to `~/source/jspwiki`.
  - Each failure becomes a `[REQUIRED] Fix Mermaid diagram at line N: <error>` finding and a score deduction. When the script or `node` is missing, print one warning and skip the check.
  - Verify with `bin/wikantik lint` (or its equivalent subcommand) on a temp file with a broken diagram, and again on a good one.
- [ ] **Step 4: Commit** the jspwiki files only: `docs(fences): editing help + CLAUDE.md pointer`.

### Task 10: Full verification gate + local hands-on pass

- [ ] **Step 1: Build.** `mvn clean install -DskipTests -T 1C`, then `bin/redeploy.sh`. Tomcat restarts; the log shows the reference manager rebuilding (serialVersionUID bump), with no stack traces.
- [ ] **Step 2: Hands-on browser pass** on `http://localhost:8080`, logged in as `testbot` from `test.properties`:
  - `WikantikArchitecture`: drawn, fits the column, Expand works, Show source and its copy button work, and it re-draws on the dark/light toggle
  - a sequence-diagram page and the `erDiagram` page
  - a narrow viewport (375px)
  - the editor: preview pane and live preview both draw; typing inside a fence shows the source; a broken edit shows the error
  - the page's network panel shows the Mermaid chunk loading only on pages with diagrams
- [ ] **Step 3: Gate.** Run `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, then poll `bin/agent-build.sh wait gate 540` until it reports SUCCESS. Report the result honestly; a red run gets fixed in-session.
- [ ] **Step 4: Review.** Use superpowers:requesting-code-review for the whole change set.

### Rollout (needs the user's explicit go-ahead: these steps are outward-facing)

- [ ] Cut the release and deploy (`docs/admin/WikantikOperations.md`; memory: `deploy-release.sh`). After that, verify on prod:
  - `curl https://wiki.wikantik.com/wiki/WikantikArchitecture | grep -c 'class="wk-fence"'` ≥ 1
  - the page draws in a browser
  - `get_backlinks` works for a page targeted by a diagram link, if any exist yet
- [ ] **Repair the 3 prod diagrams that fail to parse**, through the admin MCP tools (the wiki-content skill, MCP only): `RecommendationSystems` (fence at line 54), `SimpleAgilityAttributionRFC` (18), `SimpleAgilityCommerceDataFlow` (7). Re-run the checker over a fresh `?format=md` pull of every prod page. **Done = 0 FAIL.** Mirror the repairs into `docs/wikantik-pages/` where those pages exist.
- [ ] Push the system pages (`EditPageHelp`, and `About` if it ever needs a fix) with `bin/remote.sh pages-push`, using the established system-page route.
- [ ] Publish the design to the live wiki under the Wikantik platform hub (project convention).
