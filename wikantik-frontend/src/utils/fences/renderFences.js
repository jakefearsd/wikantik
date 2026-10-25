// drawFence.js (the pass, the policy and the registry) is loaded on first use, so it stays off the entry chunk for
// pages without diagrams.
const loadDrawing = () => import('./drawFence.js');

const PENDING = 'figure.wk-fence[data-fence-kind="browser"]:not([data-fence-state])';
const SERVER = 'figure.wk-fence[data-fence-kind="server"]';
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
    const drawn = [...document.querySelectorAll(DRAWN)];
    if (drawn.length === 0) return;
    loadDrawing()
      .then(({ redrawFence }) => drawn.forEach((figure) => { redrawFence(figure); }))
      .catch((e) => console.warn('[fences] could not load the fence pass to re-draw for the new theme', e));
  });
  themeObserver.observe(html, { attributes: true, attributeFilter: ['data-theme'] });
}

/**
 * The shared DOM pass over rendered-fence envelopes (reader view and editor previews). Picks up every
 * `figure.wk-fence[data-fence-kind="browser"]` without a `data-fence-state` and draws each independently — one
 * failure never stops the others. Idempotent: drawn/refused/failed figures are skipped. Lazy: with no such
 * figures nothing is imported. Server-kind figures are only decorated (toolbar / refusal box), never drawn here, and a browser-kind envelope for a server
 * language (hand-written) is left as source. `isStale` (optional) stops all DOM writes
 * once the view has moved on. The first call also starts re-drawing drawn figures when the theme changes.
 *
 * @param {Element|null|undefined} root
 * @param {() => boolean} [isStale]
 */
export async function renderFences(root, isStale = () => false) {
  if (!root) return;
  watchTheme();
  const figures = [...root.querySelectorAll(PENDING)];
  const serverFigures = [...root.querySelectorAll(SERVER)];
  if (figures.length === 0 && serverFigures.length === 0) return;
  let drawFence;
  let decorateServerFence;
  let drawsInReader;
  try {
    ({ drawFence, decorateServerFence, drawsInReader } = await loadDrawing());
  } catch (e) {
    console.warn('[fences] could not load the fence pass; diagrams stay as source', e);
    return;
  }
  // Server-drawn figures arrive finished; this only adds the toolbar or the refusal box. No renderer is loaded.
  serverFigures.forEach((figure) => {
    try {
      decorateServerFence(figure);
    } catch (e) {
      console.warn('[fences] could not decorate a server-drawn fence', e);
    }
  });
  // drawFence never rejects (each figure reports its own failure), so one bad diagram cannot stop the rest. A
  // browser-kind envelope for a server language stays as source (see drawsInReader).
  await Promise.all(figures.filter(drawsInReader).map((figure) => drawFence(figure, isStale)));
}
