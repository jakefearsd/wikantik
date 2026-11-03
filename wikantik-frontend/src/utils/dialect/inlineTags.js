import { tagEndAt, pluginSpans } from '../inlineTags';

const HASH = 35;

/** Per inline run: its `[{…}]` spans, read once (a run is a whole paragraph, so a per-`#` scan would be quadratic). */
const spansOf = new WeakMap();
const pluginSpansIn = (cx) => {
  let spans = spansOf.get(cx);
  if (!spans) {
    spans = cx.text.includes('[{') ? pluginSpans(cx.text) : [];
    spansOf.set(cx, spans);
  }
  return spans;
};

/**
 * An inline `#tag` as a leaf node at its `#`. The character rule is `tagEndAt` (inlineTags.js), the one the preview pane
 * shares; a run starts a line (a paragraph, or a table cell, whose `|` ends the previous cell). Text inside a `[{…}]`
 * is plugin text, which the reader never tags, and Lezer has no node for it, so the run's spans are checked here.
 * Code, math, escapes, raw HTML and URLs never reach this parser; headings and links hold inline runs, so the readers of
 * the tree skip them (`inlineTagRanges`).
 */
const InlineTagParser = {
  name: 'InlineTag',
  parse(cx, next, pos) {
    if (next !== HASH) return -1;
    const at = pos - cx.offset;
    const end = tagEndAt(cx.text, at);
    if (end < 0 || pluginSpansIn(cx).some(([a, b]) => at >= a && at < b)) return -1;
    return cx.addElement(cx.elt('InlineTag', pos, end + cx.offset));
  },
};

export const inlineTagExtension = {
  defineNodes: ['InlineTag'],
  parseInline: [InlineTagParser],
};

/** Constructs that hold inline runs yet are never prose: an ATX heading carries no tags, link text is link text. */
const NO_TAGS = /^(?:ATXHeading[1-6]|Link|Image|Autolink)$/;

/** `[{ from, to }]` of each tag (from the `#`) in the window, in order; the caller removes those inside comments. */
export function inlineTagRanges(tree, from = 0, to = tree.length) {
  const out = [];
  tree.iterate({
    from,
    to,
    enter: (n) => {
      if (NO_TAGS.test(n.name)) return false;
      if (n.name === 'InlineTag') out.push({ from: n.from, to: n.to });
      return undefined;
    },
  });
  return out;
}
