const MATH_SELECTOR = '.math-inline, .math-display';

// KaTeX (~77 kB gzip JS + CSS + fonts) is only fetched for a page that actually contains math. The promise is
// cached so concurrent and later calls share one load; a failed load is dropped so the next page can retry.
let katexPromise = null;
function loadKatex() {
  if (!katexPromise) {
    katexPromise = Promise.all([import('katex'), import('katex/dist/katex.min.css')])
      .then(([mod]) => mod.default ?? mod)
      .catch((e) => {
        katexPromise = null;
        throw e;
      });
  }
  return katexPromise;
}

/**
 * Scans a DOM container for math elements rendered by the server (Flexmark GitLab extension) and renders them
 * with KaTeX, loading KaTeX on first use. Returns a promise that resolves once the pass is done; it resolves
 * immediately (loading nothing) when the container has no math.
 *
 * Inline math: <span class="math-inline">...</span>
 * Display math: <div class="math-display">...</div>
 *
 * `isStale` (optional) is consulted after the async load: when it returns true the container's content was
 * replaced or the view went away, so nothing is touched. Elements are re-queried after the load for the same
 * reason, and `math-rendered` keeps the pass idempotent.
 */
export async function renderMath(container, isStale) {
  if (!container || !container.querySelector(MATH_SELECTOR)) return;

  let katex;
  try {
    katex = await loadKatex();
  } catch (e) {
    console.warn('Failed to load KaTeX; math left as source', e);
    return;
  }
  if (isStale?.()) return;

  container.querySelectorAll(MATH_SELECTOR).forEach((el) => {
    if (el.classList.contains('math-rendered')) return;
    const displayMode = el.classList.contains('math-display');
    try {
      katex.render(el.textContent, el, { displayMode, throwOnError: false });
      el.classList.add('math-rendered');
    } catch (e) {
      console.warn('KaTeX failed to render an expression', e);
      el.classList.add('math-error');
    }
  });
}
