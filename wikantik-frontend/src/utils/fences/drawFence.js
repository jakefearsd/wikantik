import { rendererForLanguage } from './registry';

// Draws one rendered-fence envelope (see wikantik-main RenderedFenceNodeRenderer):
//   <figure class="wk-fence" data-fence="mermaid" data-fence-kind="browser" [data-fence-error="reason"]>
//     <pre class="wk-fence-source"><code class="language-mermaid">…escaped source…</code></pre>
//   </figure>
// The source <pre> stays in the DOM in every state (text-reading tools and copy rely on it); a drawn figure
// only hides it visually (fences.css). `data-fence-state` records the outcome and keeps the pass idempotent.

const REFUSALS = {
  callback: 'it uses a click callback or an unrecognised click form. Diagram links must be "/wiki/PageName" or an http(s) URL.',
  'unsafe-link': 'it links to a target that is not a wiki page or an http(s) URL.',
  'too-large': 'its source is too large.',
  'unsupported-diagram': 'only PlantUML @startuml diagrams (class, sequence, activity and similar), @startsalt and @startnwdiag are supported.',
  'multiple-diagrams': 'it contains more than one diagram.',
  include: 'it includes another file or URL.',
  'file-access': 'it reads a file.',
  image: 'it embeds an image.',
  'layout-pragma': 'it selects a layout engine, which wiki diagrams may not do.',
  'syntax-error': 'it has a syntax error.',
  'too-complex': 'it is too complex to draw.',
  'too-slow': 'it took too long to draw.',
  'output-too-large': 'the drawing came out too large.',
  'embedded-diagram': 'it embeds another diagram ({{ \u2026 }}), which wiki diagrams may not do.',
  'render-crashed': 'PlantUML failed while drawing it.',
  busy: 'the diagram renderer is busy; reload to try again.',
  // The count / size limit is the same on every view of the page; the time budget may not be.
  'page-limit': 'this page has more diagrams than can be drawn at once.',
  'page-budget': 'this page ran out of time to draw its diagrams; reload to try again.',
  'plantuml-disabled': 'PlantUML drawing is disabled on this wiki.',
  'unsafe-config': 'it sets Mermaid configuration (a %%{\u2026}%% directive or --- front matter), which wiki diagrams may not do.',
};

/** Shown when the drawing is scaled below this share of its natural width. */
const EXPAND_THRESHOLD = 0.8;

const inFlight = new WeakMap();
const redrawGeneration = new WeakMap();

const isDark = () => document.documentElement.getAttribute('data-theme') === 'dark';
const sourceOf = (figure) => figure.querySelector('.wk-fence-source code')?.textContent ?? '';
const sourcePre = (figure) => figure.querySelector('.wk-fence-source');
// A drawing kept visible while its replacement is pending (WikiFence, server kind) is not "the" drawing.
const DRAWING = '.wk-fence-drawing:not([data-fence-pending])';
/** The SVG currently shown: a server figure holds a light and a dark variant, the theme picks one. */
function shownSvg(figure) {
  const drawing = figure.querySelector(DRAWING);
  if (!drawing) return null;
  const variant = drawing.querySelector(`.wk-fence-variant[data-variant="${isDark() ? 'dark' : 'light'}"] svg`);
  return variant ?? drawing.querySelector('svg');
}

function refusalMessage(reason) {
  if (reason === 'render-failed') return 'This diagram could not be drawn.';
  const why = REFUSALS[reason];
  return why ? `This diagram was not drawn: ${why}` : 'This diagram was not drawn.';
}

/**
 * A one-line summary of a Mermaid error: its first non-blank line (where) and, when there are more, its last one
 * (what it expected: "Expecting …, got …"). The source excerpt and caret art in between are dropped.
 */
function errorSummary(error) {
  const lines = String(error?.message ?? error ?? '').split('\n').map((l) => l.trim()).filter(Boolean);
  if (lines.length <= 1) return lines[0] ?? '';
  return `${lines[0]} \u2026 ${lines[lines.length - 1]}`;
}

/** Writes the error box (text only — never markup from the error) before the source, and the state. */
function showProblem(figure, state, message, detail) {
  const box = document.createElement('div');
  box.className = 'wk-fence-error';
  box.setAttribute('role', 'note');
  const strong = document.createElement('strong');
  strong.textContent = message;
  box.append(strong);
  if (detail) {
    const span = document.createElement('span');
    span.className = 'wk-fence-error-detail';
    span.textContent = detail;
    box.append(' ', span);
  }
  figure.insertBefore(box, sourcePre(figure));
  figure.dataset.fenceState = state;
  return state;
}

function newHost() {
  const host = document.createElement('div');
  host.className = 'wk-fence-drawing';
  return host;
}

/** The SVG's natural width from its viewBox (Mermaid's own sizing), else a `max-width: Npx` style; else 0. */
function naturalWidth(svg) {
  const box = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/);
  const fromBox = box.length === 4 ? Number(box[2]) : NaN;
  if (fromBox > 0) return fromBox;
  const fromStyle = /^(\d+(?:\.\d+)?)px$/.exec(svg.style?.maxWidth || '');
  return fromStyle ? Number(fromStyle[1]) : 0;
}

/** Called only once the toolbar exists. */
function updateExpand(figure) {
  const button = figure.querySelector('.wk-fence-toolbar [data-action="expand"]');
  const svg = shownSvg(figure);
  const natural = svg ? naturalWidth(svg) : 0;
  const shown = svg ? svg.getBoundingClientRect().width : 0;
  button.hidden = !(natural > 0 && shown < natural * EXPAND_THRESHOLD);
}

const ID_SUFFIX = '-x';

/**
 * Gives a cloned SVG's ids a suffix and rewrites every reference to them (`#id` in href / xlink:href, `url(#id)`
 * in any attribute, inline style or <style> text), so the Expand dialog never duplicates ids already in the page.
 */
function suffixIds(root) {
  const idElements = [root, ...root.querySelectorAll('*')].filter((el) => el.hasAttribute('id'));
  if (idElements.length === 0) return;
  const ids = new Set(idElements.map((el) => el.getAttribute('id')));
  idElements.forEach((el) => el.setAttribute('id', el.getAttribute('id') + ID_SUFFIX));
  const rewriteUrls = (text) => text.replace(/url\(\s*(['"]?)#([^)'"\s]+)\1\s*\)/g,
    (m, q, id) => (ids.has(id) ? `url(${q}#${id}${ID_SUFFIX}${q})` : m));
  const escaped = [...ids].map((id) => id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  // "#id" followed by a non-identifier character, so "#m1" never matches inside "#m12" or "#m1-x".
  const selector = new RegExp(`#(${escaped.join('|')})(?![\\w-])`, 'g');
  const rewriteSelectors = (text) => text.replace(selector, (m, id) => `#${id}${ID_SUFFIX}`);
  [root, ...root.querySelectorAll('*')].forEach((el) => {
    for (const attr of [...el.attributes]) {
      if (attr.name === 'id') continue;
      let value = attr.value;
      if ((attr.name === 'href' || attr.name === 'xlink:href') && value.startsWith('#') && ids.has(value.slice(1))) {
        value = value + ID_SUFFIX;
      } else {
        value = rewriteUrls(value);
      }
      if (value !== attr.value) el.setAttribute(attr.name, value);
    }
    // Mermaid scopes every rule to its root id ("#mermaid-1 .node {…}"); the selector must follow the renamed id.
    if (el.localName === 'style' && el.textContent) el.textContent = rewriteSelectors(rewriteUrls(el.textContent));
  });
}

function openDialog(figure, opener) {
  const svg = shownSvg(figure);
  if (!svg) return;
  const dialog = document.createElement('dialog');
  dialog.className = 'wk-fence-dialog';
  dialog.setAttribute('aria-label', svg.getAttribute('aria-label') || 'Diagram');
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'wk-fence-btn wk-fence-dialog-close';
  close.textContent = 'Close';
  const body = document.createElement('div');
  body.className = 'wk-fence-dialog-body';
  const clone = svg.cloneNode(true);
  suffixIds(clone);
  const natural = naturalWidth(svg);
  if (natural > 0) clone.setAttribute('width', String(natural));
  clone.style.maxWidth = 'none';
  body.append(clone);
  dialog.append(close, body);

  const dismiss = () => {
    if (dialog.open && typeof dialog.close === 'function') dialog.close();
    dialog.remove();
    opener.focus?.();
  };
  close.addEventListener('click', dismiss);
  // A click on the dialog element itself (not its content) is a click on the backdrop.
  dialog.addEventListener('click', (e) => { if (e.target === dialog) dismiss(); });
  dialog.addEventListener('cancel', (e) => { e.preventDefault(); dismiss(); });

  figure.append(dialog);
  try {
    dialog.showModal();
  } catch (e) {
    console.warn('[fences] dialog.showModal is unavailable; opening the full-size view inline', e);
    dialog.setAttribute('open', '');
  }
}

function button(action, label) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'wk-fence-btn';
  b.dataset.action = action;
  b.textContent = label;
  return b;
}

function buildToolbar(figure, pre) {
  const toolbar = document.createElement('div');
  toolbar.className = 'wk-fence-toolbar';
  const expand = button('expand', 'Expand');
  expand.hidden = true;
  expand.addEventListener('click', () => openDialog(figure, expand));
  toolbar.append(expand);
  if (pre) {
    const toggle = button('source', 'Show source');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.addEventListener('click', () => {
      const open = pre.classList.toggle('wk-fence-source-open');
      toggle.textContent = open ? 'Hide source' : 'Show source';
      toggle.setAttribute('aria-expanded', String(open));
    });
    toolbar.append(toggle);
  }
  return toolbar;
}

async function drawOnce(figure, isStale) {
  const existing = figure.dataset.fenceState;
  if (existing) return existing;
  const language = figure.dataset.fence || '';
  const source = sourceOf(figure);
  const entry = rendererForLanguage(language);
  if (!entry) {
    if (isStale()) return 'stale';
    return showProblem(figure, 'failed', `No renderer for \u201C${language}\u201D diagrams is available here.`);
  }
  const serverReason = figure.getAttribute('data-fence-error');
  // The browser always runs the policy itself, even when the server let the fence through. This is NOT mere
  // defense in depth: with allowHTML on (the default) a hand-written <figure class="wk-fence" …> envelope passes
  // the HTML sanitizer without ever meeting the server policy, so for it this check is the only gate. Never
  // remove it. Pinned by drawFence.test.js 'clientPolicyRefusesEvenWithoutServerFlag'.
  const verdict = serverReason ? { allowed: false, reason: serverReason } : entry.check(source);
  if (!verdict.allowed) {
    if (isStale()) return 'stale';
    return showProblem(figure, 'refused', refusalMessage(verdict.reason));
  }

  let renderer;
  try {
    renderer = await entry.load();
  } catch (e) {
    console.warn(`[fences] could not load the ${language} renderer; showing the source`, e);
    if (isStale()) return 'stale';
    return showProblem(figure, 'failed', 'The diagram library could not be loaded; the source is shown instead.');
  }
  if (isStale()) return 'stale';

  const host = newHost();
  const dark = isDark();
  try {
    // A server renderer also needs the language and the staleness check (it debounces, then fetches).
    await renderer.draw(host, source, entry.kind === 'server' ? { dark, language, isStale } : { dark });
  } catch (e) {
    console.warn(`[fences] ${language} could not draw a diagram`, e);
    if (isStale()) return 'stale';
    if (e?.fenceReason) {
      // The server refused it (editor preview): the same friendly wording the reader view shows.
      const detail = [e.fenceLine ? `line ${e.fenceLine}` : '', e.fenceMessage || ''].filter(Boolean).join(': ');
      return showProblem(figure, 'refused', refusalMessage(e.fenceReason), detail);
    }
    const summary = errorSummary(e);
    // A server renderer's message already leads with its label ("PlantUML line 3: …").
    const label = entry.label ?? language;
    const detail = summary && !summary.startsWith(label) ? `${label}: ${summary}` : summary;
    return showProblem(figure, 'failed', 'This diagram could not be drawn.', detail);
  }
  if (isStale()) return 'stale';

  const pre = sourcePre(figure);
  figure.insertBefore(buildToolbar(figure, pre), figure.firstChild);
  figure.insertBefore(host, pre);
  figure.dataset.fenceState = 'drawn';
  updateExpand(figure);
  // The theme flipped while this was drawing; the theme observer only re-draws figures already drawn.
  if (isDark() !== dark) redrawFence(figure);
  return 'drawn';
}

async function guardedDraw(figure, isStale) {
  try {
    return await drawOnce(figure, isStale);
  } catch (e) {
    console.warn('[fences] drawing a fence failed unexpectedly', e);
    return 'failed';
  }
}

/**
 * Draws one `figure.wk-fence` envelope. Resolves (never rejects) to its outcome:
 *   'drawn'   — the drawing, a toolbar ("Show source", "Expand" when scaled down) and the hidden source;
 *   'refused' — the server flag or the browser policy refused it: the reason and the source;
 *   'failed'  — the library failed to load or could not parse it: the error and the source;
 *   'stale'   — `isStale()` turned true before the DOM was touched: nothing was written.
 * A figure that already has a `data-fence-state` resolves to it untouched. Concurrent calls for one figure
 * run one after another, so a stale pass never leaves a later pass with nothing to do.
 *
 * @param {Element} figure
 * @param {() => boolean} [isStale]
 * @returns {Promise<'drawn'|'refused'|'failed'|'stale'>}
 */
export function drawFence(figure, isStale = () => false) {
  const previous = inFlight.get(figure);
  const run = previous
    ? previous.then(() => guardedDraw(figure, isStale))
    : guardedDraw(figure, isStale);
  inFlight.set(figure, run);
  const settle = () => { if (inFlight.get(figure) === run) inFlight.delete(figure); };
  run.then(settle, settle);
  return run;
}

/**
 * Re-draws a drawn figure (theme change), swapping in the new drawing only when it succeeds and nothing newer
 * superseded it; on failure the old drawing stays. The source is re-checked first and a refused one is not
 * re-drawn. Resolves (never rejects) to 'drawn', 'stale', 'failed', 'refused' or 'skipped' (not a drawn figure).
 *
 * @param {Element} figure
 */
export async function redrawFence(figure) {
  if (figure.dataset.fenceState !== 'drawn') return 'skipped';
  const generation = (redrawGeneration.get(figure) ?? 0) + 1;
  redrawGeneration.set(figure, generation);
  const stale = () => redrawGeneration.get(figure) !== generation || !figure.isConnected
    || figure.dataset.fenceState !== 'drawn';
  const entry = rendererForLanguage(figure.dataset.fence || '');
  if (!entry) return 'skipped';
  // A server-kind drawing carries both theme variants and CSS shows the right one: nothing to re-draw.
  if (entry.kind === 'server') return 'skipped';
  const source = sourceOf(figure);
  try {
    // Every draw re-checks its own input, re-draws included (see drawOnce: for a hand-written envelope the
    // browser check is the only gate).
    if (!entry.check(source).allowed) {
      console.warn('[fences] a drawn diagram\'s source no longer passes the policy; not re-drawing it');
      return 'refused';
    }
    const renderer = await entry.load();
    if (stale()) return 'stale';
    const host = newHost();
    await renderer.draw(host, source, { dark: isDark() });
    if (stale()) return 'stale';
    figure.querySelector(DRAWING)?.replaceWith(host);
    updateExpand(figure);
    return 'drawn';
  } catch (e) {
    console.warn('[fences] re-drawing a diagram for the new theme failed; keeping the previous drawing', e);
    return 'failed';
  }
}

/**
 * Whether the reader pass (renderFences) may draw `figure` in the browser. Server-language figures are drawn by the
 * server; a browser-kind envelope for one (hand-written, allowHTML on) is left as source rather than having every
 * reader's browser POST it to /api/fences/render. Editor previews call drawFence directly and are unaffected.
 *
 * @param {Element} figure
 */
export function drawsInReader(figure) {
  return rendererForLanguage(figure.dataset.fence || '')?.kind !== 'server';
}

/**
 * Decorates a figure the server already drew or refused (`data-fence-kind="server"`), without loading a renderer:
 *   drawn   (`data-fence-state="drawn"`)        — adds the shared toolbar (Show source, Expand when scaled down);
 *   refused (`data-fence-error`, no state)      — shows the reason box, with the line / message the server
 *                                                 reported, then marks the state `refused`.
 * Idempotent; a figure with neither is left untouched. Returns the figure's state, or null.
 *
 * @param {Element} figure
 * @returns {'drawn'|'refused'|null}
 */
export function decorateServerFence(figure) {
  const state = figure.dataset.fenceState;
  if (state === 'drawn') {
    if (!figure.querySelector(':scope > .wk-fence-toolbar')) {
      figure.insertBefore(buildToolbar(figure, sourcePre(figure)), figure.firstChild);
      updateExpand(figure);
    }
    return 'drawn';
  }
  const reason = figure.getAttribute('data-fence-error');
  if (reason && !state) {
    const line = figure.getAttribute('data-fence-error-line');
    const message = figure.getAttribute('data-fence-error-message');
    const detail = [line ? `line ${line}` : '', message || ''].filter(Boolean).join(': ');
    return showProblem(figure, 'refused', refusalMessage(reason), detail);
  }
  return state === 'refused' ? 'refused' : null;
}
