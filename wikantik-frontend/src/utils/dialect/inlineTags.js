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

/** Whether `at` lies in one of the sorted, disjoint `spans` (binary search: a run may hold tens of thousands). */
const inSpan = (spans, at) => {
  let lo = 0;
  let hi = spans.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (at < spans[mid][0]) hi = mid - 1;
    else if (at >= spans[mid][1]) lo = mid + 1;
    else return true;
  }
  return false;
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
    if (end < 0 || inSpan(pluginSpansIn(cx), at)) return -1;
    return cx.addElement(cx.elt('InlineTag', pos, end + cx.offset));
  },
};

export const inlineTagExtension = {
  defineNodes: ['InlineTag'],
  parseInline: [InlineTagParser],
};

/** CommonMark label matching: whitespace collapsed, case-insensitive. */
export const normalizeLabel = (s) => s.trim().replace(/\s+/g, ' ').toLowerCase();
/** The text of a `[label]` with its brackets off. */
export const bracketless = (raw) => raw.replace(/^\[/, '').replace(/\]$/, '');

/** The normalised label of a LinkReference node (`[r]: url`), or '' when it has none or is a footnote definition (`^…`). */
export function definitionLabel(node, slice) {
  const l = node.node.getChild('LinkLabel');
  const raw = l ? bracketless(slice(l.from, l.to)) : '';
  return raw && !raw.startsWith('^') ? normalizeLabel(raw) : '';
}

/** The normalised labels of every link reference definition in the tree (`[r]: url`). */
export function definedLabels(tree, slice) {
  const out = new Set();
  tree.iterate({
    enter: (n) => {
      if (n.name !== 'LinkReference') return undefined;
      const label = definitionLabel(n, slice);
      if (label) out.add(label);
      return false;
    },
  });
  return out;
}

/** Whether a Link or Image node is a link the reader renders: inline (a URL) or a reference whose label is defined. */
function isLink(n, slice, labels) {
  if (n.node.getChild('URL')) return true;
  const marks = n.node.getChildren('LinkMark');
  if (marks.length < 2) return false;
  const given = n.node.getChild('LinkLabel');
  const label = given ? bracketless(slice(given.from, given.to)) : '';
  return labels().has(normalizeLabel(label || slice(marks[0].to, marks[1].from)));
}

/**
 * `[{ from, to }]` of each tag (from the `#`) in the window, in order; the caller removes those inside comments. An
 * ATX heading carries no tags and a rendered link's text is link text. Brackets that link nowhere (no URL, label
 * undefined) are plain text to the reader, so a tag in them is a tag. `slice(from, to)` reads the document; `labels`
 * (default: read off `tree`) is the set of defined reference labels, normalised with `normalizeLabel`.
 */
export function inlineTagRanges(tree, { slice, labels, from = 0, to = tree.length } = {}) {
  const out = [];
  let defined = labels;
  const getLabels = () => { defined ??= definedLabels(tree, slice); return defined; };
  tree.iterate({
    from,
    to,
    enter: (n) => {
      if (/^(?:ATXHeading[1-6]|Autolink)$/.test(n.name)) return false;
      if ((n.name === 'Link' || n.name === 'Image') && isLink(n, slice, getLabels)) return false;
      if (n.name === 'InlineTag') out.push({ from: n.from, to: n.to });
      return undefined;
    },
  });
  return out;
}
