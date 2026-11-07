import { matchInlineMath } from '../inlineMath';

const DOLLAR = 36;
const TAB_WIDTH = 4;

/** A line that is `$$` alone, spaces and tabs around it: the reader's test (`DisplayMathPreProcessor.isDollarLine`). */
const DOLLAR_LINE = /^[ \t]*\$\$[ \t]*\r?$/;
const isDollarText = (text) => DOLLAR_LINE.test(text);

/**
 * Opener test: a `$$`-alone line indented less than four columns past its container. So `> $$` and `- $$` never open
 * display math, an indented `  $$` inside a list item does, and an indent of four or more stays code (or text), as in the
 * reader, which finds `$$` blocks with a parse of the same rules (#128).
 */
const isOpenerLine = (line) => isDollarText(line.text) && line.indent - line.baseIndent < TAB_WIDTH;

/**
 * Whether a `$$`-alone line starts anywhere below the line. The reader opens a block only then. A block parser cannot
 * rewind, so this reads the input forward to the first such line: from consecutive openers that is the next line, so a
 * page of `$$` lines costs one pass, not a pass per line.
 */
function closesAhead(cx, line) {
  const input = cx.input;
  if (!input || typeof input.read !== 'function') return false;
  let from = cx.lineStart + line.text.length + 1;
  let window = 512;
  while (from < input.length) {
    const to = Math.min(input.length, from + window);
    let chunk = input.read(from, to);
    let next = to;
    if (to < input.length) {
      const nl = chunk.lastIndexOf('\n');
      if (nl < 0) { window *= 2; continue; } // one line longer than the window
      chunk = chunk.slice(0, nl);
      next = from + nl + 1;
    }
    for (const raw of chunk.split('\n')) if (isDollarText(raw)) return true;
    from = next;
    window *= 2;
  }
  return false;
}

/**
 * `$$` alone on a line to the next such line in its container (blank lines inside allowed, any indent closes), as the
 * reader draws it: everything between is math, a fence line too (the reader's generated fence is longer than any inside
 * it). Where the opener's container ends first (a dedented line, a list item ends) the block ends there, and that later
 * `$$` line is read afresh. A `$$` with no `$$` line below is left to the paragraph.
 */
const DisplayMathBlock = {
  name: 'DisplayMath',
  before: 'FencedCode',
  parse(cx, line) {
    if (line.next !== DOLLAR || !isOpenerLine(line) || !closesAhead(cx, line)) return false;
    const from = cx.lineStart + line.pos;
    let to = cx.lineStart + line.text.length;
    while (cx.nextLine()) {
      if (line.depth < cx.depth) break;
      if (line.text.trim() !== '') to = cx.lineStart + line.text.length;
      if (isDollarText(line.text)) { cx.nextLine(); break; }
    }
    cx.addElement(cx.elt('DisplayMath', from, to));
    return true;
  },
  endLeaf: (cx, line) => line.next === DOLLAR && isOpenerLine(line) && closesAhead(cx, line),
};

/**
 * Exclusive end of the inline math opening at the `$` at `at` of `text`, or -1: the shared Pandoc rule (`$…$`). A `$`
 * followed by `$` is text, as in the reader's `InlineMathParser` (there is no inline `$$…$$`; the second dollar is
 * tried on its own by the caller's next step). Escapes are Lezer's own for the parser (`\$` never reaches here);
 * callers that scan raw text skip them.
 */
export function inlineMathEnd(text, at) {
  return matchInlineMath(text, at);
}

/**
 * `$…$` within a paragraph. The reader has no inline `$$…$$` (`InlineMathParser` declines a `$` followed by `$`, so the
 * first dollar is text and the second is tried on its own): `$$x$$` is `$` + `$x$` + `$`, and `$$ …` opens nothing. The
 * editor used to read `$$…$$` as one span, which hid every `%%` the reader counts.
 */
const InlineMathParser = {
  name: 'InlineMath',
  before: 'InlineCode',
  parse(cx, next, pos) {
    if (next !== DOLLAR) return -1;
    const text = cx.slice(pos, cx.end);
    const end = inlineMathEnd(text, 0); // a `$` before another `$` is text; the next step tries the second
    if (end < 0) return -1;
    return cx.addElement(cx.elt('InlineMath', pos, pos + end));
  },
};

/** The wiki's math constructs as @lezer/markdown nodes: `DisplayMath`, `InlineMath`. */
export const mathExtension = {
  defineNodes: [{ name: 'DisplayMath', block: true }, 'InlineMath'],
  parseBlock: [DisplayMathBlock],
  parseInline: [InlineMathParser],
};

const OPAQUE = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'DisplayMath', 'InlineMath']);

/** `[from, to)` of every outermost code or math node, in document order: one walk, for callers that test many positions. */
export function opaqueRanges(tree) {
  const out = [];
  tree.iterate({
    enter: (node) => {
      if (!OPAQUE.has(node.name)) return undefined;
      out.push([node.from, node.to]);
      return false;
    },
  });
  return out;
}
