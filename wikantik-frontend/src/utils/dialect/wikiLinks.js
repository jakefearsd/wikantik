import { parseWikiLink } from '../wikiLinkSyntax';
import { matchInlineMath } from '../inlineMath';

const BANG = 33;
const OPEN = 91;
const CLOSE = 93;
const NEWLINE = 10;

/**
 * Where the `]]` closing a wikilink opened at `at` (the first `[` of `[[`) starts, or -1. The inner text is one or more
 * characters that are none of `[`, `]` or a line break (the grammar of `WIKILINK_TOKEN`), so the scan stops at the
 * first such character and costs no more than the link itself: a run of unclosed `[[a` is linear.
 */
function closeAt(text, at) {
  let i = at + 2;
  while (i < text.length) {
    const c = text.charCodeAt(i);
    if (c === OPEN || c === NEWLINE) return -1;
    if (c === CLOSE) return i > at + 2 && text.charCodeAt(i + 1) === CLOSE ? i : -1;
    i += 1;
  }
  return -1;
}

const BACKTICK = 96;
const BACKSLASH = 92;
const DOLLAR = 36;

/** Length of the backtick run starting at `at`. */
function runLength(text, at) {
  let n = 0;
  while (text.charCodeAt(at + n) === BACKTICK) n += 1;
  return n;
}

let runsText = null;
let runsByLength = null;

/**
 * The start of every backtick run of `text`, grouped by run length, ascending. Built once per inline block (the parser
 * asks for each link of a paragraph with the same text), so finding a run's closer is a binary search, not a scan to
 * the end of the block: links with unclosed runs of many different lengths stay linear.
 */
function runsOf(text) {
  if (runsText === text) return runsByLength;
  const byLength = new Map();
  for (let j = 0; j < text.length;) {
    if (text.charCodeAt(j) !== BACKTICK) { j += 1; continue; }
    const m = runLength(text, j);
    if (!byLength.has(m)) byLength.set(m, []);
    byLength.get(m).push(j);
    j += m;
  }
  runsText = text;
  runsByLength = byLength;
  return byLength;
}

/** Where the next backtick run of exactly `n` characters at or after `at` starts, or -1. */
function closerOf(text, at, n) {
  const starts = runsOf(text).get(n);
  if (!starts) return -1;
  let lo = 0;
  let hi = starts.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (starts[mid] < at) lo = mid + 1;
    else hi = mid;
  }
  return lo < starts.length ? starts[lo] : -1;
}

/**
 * Whether a code span or inline math opened inside the link text (between `from` and `close`) ends after the link's
 * `]]`. The reader lets the span win there: a backtick run (not backslash-escaped) opens a code span when a run of the
 * same length follows, and a `$` opens inline math by the shared Pandoc rule (`matchInlineMath`), so a link whose text
 * holds the opener and whose closer lies beyond it is not a link at all, and what the span holds is code or math. A
 * span that opens and closes inside the link text leaves the link alone. The math check costs no more than the text up
 * to the next unescaped `$`, so the scans of all links of a block are disjoint and the whole stays linear.
 */
function opaqueCrossesClose(text, from, close) {
  for (let i = from; i < close; i += 1) {
    const c = text.charCodeAt(i);
    if (c === BACKSLASH) { i += 1; continue; }
    if (c === DOLLAR) {
      const end = matchInlineMath(text, i);
      if (end - 1 >= close) return true;
      if (end > 0) i = end - 1;
      continue;
    }
    if (c !== BACKTICK) continue;
    const n = runLength(text, i);
    const closer = closerOf(text, i + n, n);
    if (closer >= close) return true;
    i = (closer < 0 ? i : closer) + n - 1;
  }
  return false;
}

/**
 * `[[Page]]`, `[[Page|Alias]]`, `[[Page#Heading]]`, `[[#Heading]]` as a `WikiLink` node, and `![[file.png|300]]` as a
 * `WikiEmbed` (a distinct node rather than a flag: Lezer nodes carry no per-instance data, and a reader of the tree
 * then tells the two apart by name). Children are `WikiLinkMark` for `[[`, `]]` and the alias `|`, `WikiLinkTarget`
 * for the text before the pipe (page and heading) and `WikiLinkAlias` for the text after it. Whether the inner text IS
 * a wikilink (`[[ x ]]` with a leading space is not) is `parseWikiLink`'s answer, the grammar shared with the reader.
 * Registered before Link, so `[[x]]` never becomes a link. A backslash before the first `[` is consumed by Lezer's
 * Escape first, so `\[[x]]` is text; `\![[x]]` leaves a plain `[[x]]` link, as the reader does. Code and math never
 * reach this parser because their nodes consume their text first, and a code span that opens inside the link text and
 * closes after it wins over the link, as does inline math (`opaqueCrossesClose`).
 */
const WikiLinkParser = {
  name: 'WikiLink',
  parse(cx, next, pos) {
    const embed = next === BANG;
    if (embed ? cx.char(pos + 1) !== OPEN || cx.char(pos + 2) !== OPEN : next !== OPEN || cx.char(pos + 1) !== OPEN) return -1;
    const at = pos - cx.offset + (embed ? 1 : 0);
    const close = closeAt(cx.text, at);
    if (close < 0 || !parseWikiLink(cx.text.slice(at - (embed ? 1 : 0), close + 2))) return -1;
    if (opaqueCrossesClose(cx.text, at + 2, close)) return -1;
    const o = cx.offset;
    const innerFrom = at + 2;
    const inner = cx.text.slice(innerFrom, close);
    const pipe = inner.indexOf('|');
    const kids = [cx.elt('WikiLinkMark', pos, at + 2 + o)];
    const targetEnd = pipe < 0 ? close : innerFrom + pipe - (pipe > 0 && inner[pipe - 1] === '\\' ? 1 : 0);
    if (targetEnd > innerFrom) kids.push(cx.elt('WikiLinkTarget', innerFrom + o, targetEnd + o));
    if (pipe >= 0) {
      kids.push(cx.elt('WikiLinkMark', innerFrom + pipe + o, innerFrom + pipe + 1 + o));
      if (close > innerFrom + pipe + 1) kids.push(cx.elt('WikiLinkAlias', innerFrom + pipe + 1 + o, close + o));
    }
    kids.push(cx.elt('WikiLinkMark', close + o, close + 2 + o));
    return cx.addElement(cx.elt(embed ? 'WikiEmbed' : 'WikiLink', pos, close + 2 + o, kids));
  },
  before: 'Link',
};

export const wikiLinkExtension = {
  defineNodes: ['WikiLink', 'WikiEmbed', 'WikiLinkMark', 'WikiLinkTarget', 'WikiLinkAlias'],
  parseInline: [WikiLinkParser],
};

/** The WikiLink or WikiEmbed node holding `pos` (inclusive of both ends), or null. */
export function wikiLinkNodeAt(tree, pos) {
  // Both sides: a position at a link's end (the right half of its closing `]`) belongs to the link.
  for (const side of [1, -1]) {
    for (let n = tree.resolveInner(pos, side); n; n = n.parent) {
      if (n.name === 'WikiLink' || n.name === 'WikiEmbed') return n;
    }
  }
  return null;
}

/** `{ from, to }` of each wikilink or embed node in the window, document order. */
export function wikiLinkRanges(tree, { from = 0, to = tree.length } = {}) {
  const out = [];
  tree.iterate({
    from, to,
    enter: (n) => {
      if (n.name !== 'WikiLink' && n.name !== 'WikiEmbed') return undefined;
      out.push({ from: n.from, to: n.to, embed: n.name === 'WikiEmbed' });
      return false;
    },
  });
  return out;
}
