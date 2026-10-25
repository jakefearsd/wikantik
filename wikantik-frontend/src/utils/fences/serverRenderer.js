// The server-drawn fence client (PlantUML). Imported only through registry.js's dynamic `load()`. The server draws
// reader-view fences itself and the page HTML arrives finished (drawFence.decorateServerFence only decorates it);
// this module is for the figures that are NOT in the page HTML: the editor previews (WikiFence, the live-preview
// FenceWidget). The reader pass never calls it: a hand-written browser-kind envelope for a server language stays as
// source (drawFence.drawsInReader). It asks POST /api/fences/render and fills the host with exactly the markup the server
// emits for a drawn fence, so one stylesheet and one toolbar serve both. The returned SVG is scrubbed by the
// server and trusted here (no client-side sanitizer, by design).
import { api } from '../../api/client';

/** Typing in the editor fires a draw per keystroke; only the one that survives this wait reaches the server. */
export const DEBOUNCE_MS = 500;

const LABELS = { plantuml: 'PlantUML' };

function variant(name, svg, hidden) {
  const el = document.createElement('div');
  el.className = 'wk-fence-variant';
  el.dataset.variant = name;
  if (hidden) el.hidden = true;
  el.innerHTML = svg;
  return el;
}

/**
 * Draws `source` into `host` (replacing its content) through the server. Resolves without touching `host` when
 * `isStale()` turned true during the wait or the request. Throws on a server refusal (`PlantUML line N: message`
 * or `PlantUML: reason`) and on any HTTP failure, including 401 for an anonymous reader.
 *
 * @param {Element} host
 * @param {string} source
 * @param {{ language?: string, isStale?: () => boolean }} options
 */
export async function draw(host, source, { language = 'plantuml', isStale = () => false } = {}) {
  await new Promise((resolve) => { setTimeout(resolve, DEBOUNCE_MS); });
  if (isStale()) return;
  const label = LABELS[language] ?? language;
  const result = await api.renderFence(language, source);
  if (isStale()) return;
  if (!result || result.error || typeof result.lightSvg !== 'string' || typeof result.darkSvg !== 'string') {
    const reason = result?.error || 'no drawing returned';
    const where = result?.line ? ` line ${result.line}` : '';
    const failure = new Error(result?.message ? `${label}${where}: ${result.message}` : `${label}${where}: ${reason}`);
    // drawFence turns these into the friendly refusal box (the reader view's wording).
    failure.fenceReason = result?.error;
    failure.fenceLine = result?.line;
    failure.fenceMessage = result?.message;
    throw failure;
  }
  host.setAttribute('role', 'img');
  host.setAttribute('aria-label', `${label} diagram`);
  host.replaceChildren(variant('light', result.lightSvg, false), variant('dark', result.darkSvg, true));
}
