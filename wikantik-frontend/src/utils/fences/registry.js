import { checkMermaid } from './mermaidPolicy';

/**
 * Browser renderers for rendered fences, keyed by the fence language. The server emits the envelope
 * (`figure.wk-fence[data-fence][data-fence-kind]`, see wikantik-main RenderedFenceNodeRenderer) for every
 * registered, non-`example` fence; this registry draws the `data-fence-kind="browser"` ones via renderFences.js.
 *
 * How to add a browser renderer (e.g. Vega-Lite as ```vega-lite):
 *
 *   1. Server registration. Implement RenderedFenceRenderer (wikantik-main,
 *      com.wikantik.markdown.extensions.fences) with kind BROWSER, its check() policy and pageLinks(), and add it to
 *      RenderedFenceRegistry.defaults(). Without this the fence stays an ordinary code block and the browser never
 *      sees an envelope. An envelope whose language has no entry here is shown as a failure with its source.
 *
 *   2. Policy. Port the server's check() to JS as `check(source) → { allowed, reason, pageLinks }` (fail closed:
 *      a thrown error refuses with `render-failed`), and add its cases — allowed, every refusal reason, the
 *      link-extraction cases — to the shared fixture src/utils/__fixtures__/renderedFences.json so the Java test and
 *      a vitest both iterate them (mermaidPolicy.test.js is the pattern). The editor previews never reach the
 *      server, so this is what makes a preview refuse exactly what the saved page refuses; the reader view re-runs
 *      it too, before every draw and re-draw — and for a hand-written envelope (allowHTML on) that never met the
 *      server policy it is the ONLY gate, so it must never be dropped.
 *
 *   3. Lazy load. `load: () => import('./<name>Renderer.js')`, a module exporting
 *      `draw(host, source, { dark }) → Promise<void>` that writes the drawing into `host` only, throws on a parse
 *      error (drawFence shows the message's first and last lines), never runs diagram-supplied script, configures the
 *      library's own security settings as strictly as it allows, and labels the drawing (role="img" + aria-label).
 *      Import the library ONLY from that module, so a page without such a fence never fetches it.
 *
 *   4. Chunking (see the codeSplitting comment in vite.config.js). A library that ships its own lazy split (as
 *      Mermaid does: a core plus one chunk per diagram type) gets NO chunk group — a group would weld its pieces
 *      into one huge chunk. Instead add every package only it pulls in (diff the lockfile) to the family excluded
 *      from the catch-all `vendor` group (MERMAID_FAMILY today; generalise the name when a second library joins),
 *      or those packages land in the eager vendor chunk. A single-file library may get its own lazy group with
 *      `includeDependenciesRecursively: false`. Either way `npm run build` must stay pristine: its post-build guard
 *      (scripts/check-dist.js) fails if dist/index.html modulepreloads the library.
 *
 * How to add a SERVER renderer (PlantUML today, ```plantuml). The server draws the diagram itself and emits the
 * finished envelope (`data-fence-kind="server"`, a `.wk-fence-drawing` holding a light and a dark SVG variant, or
 * `data-fence-error` when it refused); the browser only decorates it (renderFences.js -> decorateServerFence: the
 * toolbar and the refusal box) and CSS picks the variant for the theme, so there is no policy to port and no
 * re-draw on a theme change. The entry is `{ kind: 'server', label, check: () => ({ allowed: true }), load }`
 * — the server is the policy — and `load()` returns serverRenderer.js, whose `draw(host, source, { language,
 * isStale })` calls POST /api/fences/render for the editor previews only (the reader pass leaves a hand-written browser-kind
 * envelope for a server language as source).
 * Register the language server-side too (RenderedFenceRegistry). Server SVG is scrubbed there and trusted here.
 *
 * Entries are plain mutable objects on purpose: tests spy on `load` and `check`.
 */
export const FENCE_RENDERERS = {
  mermaid: { kind: 'browser', label: 'Mermaid', check: checkMermaid, load: () => import('./mermaidRenderer.js') },
  // The server is the policy for PlantUML (see "How to add a SERVER renderer"); never gated client-side.
  plantuml: { kind: 'server', label: 'PlantUML', check: () => ({ allowed: true }), load: () => import('./serverRenderer.js') },
};

/** The registry entry for a language, or null (own properties only, so `constructor` etc. never match). */
export function rendererForLanguage(language) {
  return typeof language === 'string' && Object.hasOwn(FENCE_RENDERERS, language) ? FENCE_RENDERERS[language] : null;
}

/**
 * The renderer for a parsed info string (`parseFenceInfo` result): null for an `example` fence or an
 * unregistered language — both render as an ordinary code block.
 */
export function rendererFor(fenceInfo) {
  if (!fenceInfo || fenceInfo.example) return null;
  return rendererForLanguage(fenceInfo.language);
}
