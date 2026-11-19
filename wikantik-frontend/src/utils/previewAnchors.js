// The preview's blocks as scroll-sync anchors: each rendered block carries the 1-based body line it came from
// (rehypeSourceLine). Only blocks are anchors (the article's own children, list items, table rows, and the blocks
// directly inside a blockquote or a callout's body): an inline element shares its paragraph's line and would add
// nothing.

/** The class that marks the preview block holding the editor's caret. */
export const PREVIEW_CARET_CLASS = 'editor-preview-caret-block';

const BLOCKS = ':scope > [data-line], li[data-line], tr[data-line], blockquote > [data-line], .callout-content > [data-line]';

/**
 * The anchors `{ line, top, el }` of the preview `article`, in document order, with `top` in the scroll coordinates
 * of `scroller` (the element that scrolls the preview).
 */
export function collectPreviewAnchors(article, scroller) {
  if (!article || !scroller) return [];
  const origin = scroller.getBoundingClientRect().top - scroller.scrollTop;
  const anchors = [];
  for (const el of article.querySelectorAll(BLOCKS)) {
    const line = parseInt(el.getAttribute('data-line'), 10);
    if (Number.isNaN(line)) continue;
    const rect = el.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) continue; // not laid out (e.g. inside a closed <details>)
    anchors.push({ line, top: rect.top - origin, el });
  }
  return anchors;
}

/**
 * A stable copy of `anchors` sorted by source line (document order kept on ties). Document order is not source
 * order everywhere: footnote definitions render at the end.
 */
export function sortedByLine(anchors) {
  return anchors.map((a, i) => [a, i]).sort((x, y) => x[0].line - y[0].line || x[1] - y[1]).map(([a]) => a);
}

/**
 * The block holding `line`: the one starting nearest above or at it, and of several starting there the innermost
 * (the list item, not its list; the row, not its table). Needs `byLine` in {@link sortedByLine}'s order. Null above
 * the first block.
 */
export function caretBlock(byLine, line) {
  let lo = 0;
  let hi = byLine.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (byLine[mid].line <= line) lo = mid + 1; else hi = mid;
  }
  return lo > 0 ? byLine[lo - 1].el : null;
}
