// The preview's blocks as scroll-sync anchors: each rendered block carries the 1-based body line it came from
// (rehypeSourceLine). Only blocks are anchors (the article's own children, list items and table rows): an inline
// element shares its paragraph's line and would add nothing.

/** The class that marks the preview block holding the editor's caret. */
export const PREVIEW_CARET_CLASS = 'editor-preview-caret-block';

const BLOCKS = ':scope > [data-line], li[data-line], tr[data-line]';

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
    if (!Number.isNaN(line)) anchors.push({ line, top: el.getBoundingClientRect().top - origin, el });
  }
  return anchors;
}

/**
 * The block holding `line`: the one starting nearest above or at it, and of several starting there the innermost
 * (the list item, not its list; the row, not its table). Null above the first block.
 */
export function caretBlock(anchors, line) {
  let best = null;
  for (const a of anchors) {
    if (a.line <= line && (!best || a.line >= best.line)) best = a;
  }
  return best ? best.el : null;
}
