import * as textQuote from 'dom-anchor-text-quote';

const MATH_SELECTOR = '.katex, .katex-display, .math-rendered';

/** True if either endpoint of the range sits inside KaTeX-rendered math. */
export function selectionTouchesMath(range) {
  const ends = [range.startContainer, range.endContainer];
  return ends.some((node) => {
    const el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
    return !!(el && el.closest && el.closest(MATH_SELECTOR));
  });
}

/**
 * Build a W3C TextQuoteSelector {exact, prefix, suffix} from a DOM Range
 * relative to `root`. Returns null if the range is empty or outside root.
 */
export function selectorFromRange(root, range) {
  if (!range || range.collapsed) return null;
  if (!root.contains(range.commonAncestorContainer)) return null;
  const selector = textQuote.fromRange(root, range);
  if (!selector || !selector.exact || !selector.exact.trim()) return null;
  return { exact: selector.exact, prefix: selector.prefix || '', suffix: selector.suffix || '' };
}

/** Capture from the live window selection within `root`. Returns
 *  {selector, rect} | {error:'math'} | null. */
export function captureSelection(root) {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
  const range = sel.getRangeAt(0);
  if (!root.contains(range.commonAncestorContainer)) return null;
  if (selectionTouchesMath(range)) return { error: 'math' };
  const selector = selectorFromRange(root, range);
  if (!selector) return null;
  return { selector, rect: range.getBoundingClientRect() };
}
