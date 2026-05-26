import * as textQuote from 'dom-anchor-text-quote';

/** Wrap every text-node segment inside `range` in <mark class="comment-highlight">. */
export function highlightRange(range, threadId) {
  const marks = [];
  const root = range.commonAncestorContainer.nodeType === Node.ELEMENT_NODE
    ? range.commonAncestorContainer
    : range.commonAncestorContainer.parentNode;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const textNodes = [];
  let n;
  while ((n = walker.nextNode())) {
    if (range.intersectsNode(n)) textNodes.push(n);
  }
  for (const textNode of textNodes) {
    let start = 0;
    let end = textNode.data.length;
    if (textNode === range.startContainer) start = range.startOffset;
    if (textNode === range.endContainer) end = range.endOffset;
    if (start >= end) continue;
    const r = document.createRange();
    r.setStart(textNode, start);
    r.setEnd(textNode, end);
    const mark = document.createElement('mark');
    mark.className = 'comment-highlight';
    mark.dataset.threadId = threadId;
    try {
      r.surroundContents(mark);
      marks.push(mark);
    } catch {
      // surroundContents throws if the range partially selects a non-text node;
      // skip that segment rather than corrupting the DOM.
    }
  }
  return marks;
}

/**
 * Re-anchor each OPEN thread into `root`. Resolved threads are skipped (no
 * highlight). Returns { detached: [threadId,...] } for threads whose text
 * could not be located.
 */
export function anchorThreads(root, threads) {
  const detached = [];
  for (const t of threads) {
    if (t.status !== 'open') continue;
    const range = textQuote.toRange(root, {
      exact: t.anchor.exact,
      prefix: t.anchor.prefix || '',
      suffix: t.anchor.suffix || '',
    });
    if (!range) { detached.push(t.id); continue; }
    const marks = highlightRange(range, t.id);
    if (marks.length === 0) detached.push(t.id);
  }
  return { detached };
}

/** Remove all existing comment highlights (call before re-anchoring). */
export function clearHighlights(root) {
  root.querySelectorAll('mark.comment-highlight').forEach((mark) => {
    const parent = mark.parentNode;
    while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
    parent.removeChild(mark);
    parent.normalize();
  });
}
