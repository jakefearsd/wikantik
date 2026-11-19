// Pure helpers for the editor <-> preview scroll sync.
//
// Every rendered block carries the source line it came from (rehypeSourceLine), and the editor knows each line's
// pixel top, so the two panes are matched block by block: a block's top in the editor maps to the same block's
// top in the preview, and a position between two blocks moves linearly between them. A tall image, diagram or
// table therefore shifts only its own stretch, not everything below it.
//
// Side-effect free (no DOM, no rAF); the caller measures both panes and applies the result.

/**
 * Number of source lines occupied by a leading `---` … `---` frontmatter block
 * (counting both fence lines). Returns 0 when the text has no frontmatter.
 */
export function frontmatterLineCount(text) {
  if (!text || !text.startsWith('---')) return 0;
  const lines = text.split('\n');
  // First line must be exactly the opening fence.
  if (lines[0].trim() !== '---') return 0;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') return i + 1; // inclusive of the closing fence
  }
  return 0; // unterminated frontmatter (still being typed) — treat as no block
}

/**
 * The anchor points of the scroll map, as [editorY, previewY] pairs in scroll coordinates: the two tops, each
 * block's top in both panes (`pairs`, in document order), and the two document ends. A point is kept only where
 * both coordinates move strictly down from the last one kept, so a hidden line (no height in the editor) or a
 * block the preview lays out above its predecessor cannot fold the map back on itself.
 */
export function scrollAnchors(pairs, editorEnd, previewEnd) {
  const anchors = [[0, 0]];
  for (const point of [...pairs, [editorEnd, previewEnd]]) {
    const [lastE, lastP] = anchors[anchors.length - 1];
    if (point[0] > lastE && point[1] > lastP) anchors.push([point[0], point[1]]);
  }
  return anchors;
}

/**
 * Maps an editor position to the preview through `anchors` (from {@link scrollAnchors}), linearly between the two
 * neighbouring anchors; `reverse` maps a preview position to the editor. Outside the anchors it holds at the end.
 */
export function mapPosition(y, anchors, reverse = false) {
  if (!anchors.length) return 0;
  const from = reverse ? 1 : 0;
  const to = reverse ? 0 : 1;
  if (y <= anchors[0][from]) return anchors[0][to];
  for (let i = 1; i < anchors.length; i++) {
    const a = anchors[i - 1];
    const b = anchors[i];
    if (y < b[from]) return a[to] + ((y - a[from]) / (b[from] - a[from])) * (b[to] - a[to]);
  }
  return anchors[anchors.length - 1][to];
}

/**
 * The preview's scrollTop for the editor's scrollTop `s`. `pane`:
 *   anchors      the scroll map ({@link scrollAnchors});
 *   readingLine  how far down the editor's window the panes are held level (px; 0 = the window's top);
 *   editorMax, editorWindow  the editor's largest scrollTop and its window's height;
 *   previewMax   the preview's largest scrollTop.
 * In the middle the block at the reading line is at the same height in both panes. Over the first `readingLine` px
 * the preview rises linearly from its top into that, so both tops meet at the top; over the editor's last window it
 * blends into the preview's end, so both ends meet at the end. It never decreases as `s` grows, which is what lets
 * {@link editorScrollFor} invert it.
 */
export function previewScrollFor(s, { anchors, readingLine, editorMax, editorWindow, previewMax }) {
  if (editorMax <= 0 || previewMax <= 0) return 0;
  const k = Math.max(0, readingLine);
  const level = (at) => Math.min(previewMax, Math.max(0, mapPosition(at + k, anchors) - k));
  const y = s < k ? (Math.max(0, s) / k) * level(k) : level(s);
  const window = Math.max(1, Math.min(editorWindow, editorMax));
  const w = Math.min(1, Math.max(0, (s - (editorMax - window)) / window));
  return y + w * (previewMax - y);
}

/** The editor's scrollTop that {@link previewScrollFor} sends to the preview's scrollTop `p`: its inverse. */
export function editorScrollFor(p, pane) {
  const { editorMax, previewMax } = pane;
  if (editorMax <= 0 || previewMax <= 0 || p <= 0) return 0;
  if (p >= previewScrollFor(editorMax, pane)) return editorMax;
  let lo = 0;
  let hi = editorMax;
  for (let i = 0; i < 40 && hi - lo > 0.01; i++) {
    const mid = (lo + hi) / 2;
    if (previewScrollFor(mid, pane) < p) lo = mid; else hi = mid;
  }
  return (lo + hi) / 2;
}
