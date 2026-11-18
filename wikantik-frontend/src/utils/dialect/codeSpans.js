const BACKTICK = 96;
const BACKSLASH = 92;

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
 * asks for each run of a paragraph with the same text), so finding a run's closer is a binary search, not a scan to
 * the end of the block: unclosed runs of many different lengths stay linear.
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
 * Every inline code span, in place of `@lezer/markdown`'s own `InlineCode` parser, for two reasons.
 *
 * - A span whose opening run directly follows a backslash-escaped backtick: `` \```x``` `` is an escaped backtick and
 *   then a three-backtick span, to the reader. Lezer's parser opens no span when the character before the run is a
 *   backtick, escaped or not, and reads the whole as text.
 * - Cost: Lezer's parser looks for a run's closer by reading to the end of the block, for every run that has none, so a
 *   paragraph of unclosed runs is quadratic. Here the closer is a lookup in the block's runs (`runsOf`).
 *
 * The rule is otherwise Lezer's and commonmark's: a run of backticks opens a span when a later whole run of the block
 * has the same length; a run with no closer is text (all of it: the parser steps over it, so no shorter span opens
 * inside it).
 */
const CodeSpanParser = {
  name: 'CodeSpan',
  parse(cx, next, pos) {
    if (next !== BACKTICK) return -1;
    const text = cx.text;
    const at = pos - cx.offset;
    if (text.charCodeAt(at - 1) === BACKTICK) {
      // mid-run, unless the backtick before is an escaped one: then the run starts here
      let slashes = 0;
      while (at - 2 - slashes >= 0 && text.charCodeAt(at - 2 - slashes) === BACKSLASH) slashes += 1;
      if (slashes % 2 === 0) return -1;
    }
    const n = runLength(text, at);
    const closer = closerOf(text, at + n, n);
    if (closer < 0) return pos + n;
    const o = cx.offset;
    return cx.addElement(cx.elt('InlineCode', pos, closer + n + o, [
      cx.elt('CodeMark', pos, pos + n),
      cx.elt('CodeMark', closer + o, closer + n + o),
    ]));
  },
  before: 'InlineCode',
};

export const codeSpanExtension = { parseInline: [CodeSpanParser] };
