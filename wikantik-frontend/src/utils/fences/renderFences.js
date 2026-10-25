import { drawFence, redrawFence } from './drawFence';

const PENDING = 'figure.wk-fence[data-fence-kind="browser"]:not([data-fence-state])';
const DRAWN = 'figure.wk-fence[data-fence-kind="browser"][data-fence-state="drawn"]';

let themeObserver = null;
let lastTheme = null;

/** One observer for the app's lifetime: hooks/useDarkMode.js flips `data-theme` on <html>. */
function watchTheme() {
  if (themeObserver || typeof MutationObserver === 'undefined') return;
  const html = document.documentElement;
  lastTheme = html.getAttribute('data-theme');
  themeObserver = new MutationObserver(() => {
    const theme = html.getAttribute('data-theme');
    if (theme === lastTheme) return;
    lastTheme = theme;
    document.querySelectorAll(DRAWN).forEach((figure) => { redrawFence(figure); });
  });
  themeObserver.observe(html, { attributes: true, attributeFilter: ['data-theme'] });
}

/**
 * The shared DOM pass over rendered-fence envelopes (reader view and editor previews). Picks up every
 * `figure.wk-fence[data-fence-kind="browser"]` without a `data-fence-state` and draws each independently — one
 * failure never stops the others. Idempotent: drawn/refused/failed figures are skipped. Lazy: with no such
 * figures nothing is imported. Server-kind figures are never touched. `isStale` (optional) stops all DOM writes
 * once the view has moved on. The first call also starts re-drawing drawn figures when the theme changes.
 *
 * @param {Element|null|undefined} root
 * @param {() => boolean} [isStale]
 */
export async function renderFences(root, isStale = () => false) {
  if (!root) return;
  watchTheme();
  const figures = [...root.querySelectorAll(PENDING)];
  if (figures.length === 0) return;
  // drawFence never rejects (each figure reports its own failure), so one bad diagram cannot stop the rest.
  await Promise.all(figures.map((figure) => drawFence(figure, isStale)));
}
