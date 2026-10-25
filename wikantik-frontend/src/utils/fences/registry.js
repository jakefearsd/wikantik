import { checkMermaid } from './mermaidPolicy';

/**
 * Browser renderers for rendered fences, keyed by the fence language. The server (wikantik-main
 * RenderedFenceRegistry) emits the envelope for every registered, non-`example` fence; this registry draws the
 * `data-fence-kind="browser"` ones.
 *
 * Adding a browser renderer (e.g. Vega-Lite):
 *   - `check(source) → { allowed, reason, pageLinks }`: the same safety policy as the server renderer, pinned by
 *     a shared fixture case table, so the editor previews (which never reach the server) refuse exactly what the
 *     saved page refuses. Fail closed.
 *   - `load() → Promise<{ draw(host, source, { dark }) }>`: a dynamic import, so the library is fetched only by
 *     a page that has such a fence. Give the library its own chunk group in vite.config.js and keep it out of
 *     `dist/index.html`'s modulepreloads. `draw` writes the drawing into `host` (never elsewhere), throws on a
 *     parse error, must not run diagram-supplied script, and labels the drawing for assistive technology.
 *   - Register the language on the server too; an envelope the browser has no entry for is shown as a failure
 *     with its source.
 *
 * Entries are plain mutable objects on purpose: tests spy on `load`.
 */
export const FENCE_RENDERERS = {
  mermaid: { check: checkMermaid, load: () => import('./mermaidRenderer.js') },
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
