// Pure helpers for editor→preview scroll synchronization.
//
// The editor source has a leading YAML frontmatter block that is NOT rendered
// in the preview body (it's surfaced as a compact card pinned at the top). So
// the sync is "region-aware": a caret anywhere in the frontmatter pins the
// preview to the top; once in the body, the caret's position within the body
// maps proportionally onto the preview's scrollable range.
//
// Side-effect free (no DOM, no rAF) so it's trivially testable; the caller
// reads the editor/preview geometry and applies the returned scrollTop.

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
 * Fraction [0,1] of the preview's scroll range the caret should map to.
 * - caret within the frontmatter → 0 (preview pinned to the top card)
 * - caret in the body → proportional position within the body lines
 *
 * @param {number} caretLine        1-based line of the caret
 * @param {number} totalLines       total source lines
 * @param {number} frontmatterLines lines occupied by the frontmatter block
 */
export function caretToPreviewFraction(caretLine, totalLines, frontmatterLines) {
  const bodyLines = Math.max(1, totalLines - frontmatterLines);
  if (caretLine <= frontmatterLines) return 0;
  const bodyPos = caretLine - frontmatterLines;
  return clamp01((bodyPos - 1) / bodyLines);
}

/**
 * Target preview scrollTop for a given fraction and preview geometry.
 */
export function previewScrollTopFor(fraction, scrollHeight, clientHeight) {
  const range = Math.max(0, scrollHeight - clientHeight);
  return Math.round(clamp01(fraction) * range);
}

function clamp01(n) {
  if (Number.isNaN(n)) return 0;
  return n < 0 ? 0 : n > 1 ? 1 : n;
}
