import { matchInlineMath } from '../inlineMath';

const DOLLAR = 36;
const LOOKAHEAD_CHARS = 64 * 1024;
const TAB_WIDTH = 4;

/**
 * Opener and closer test: the FULL line is `$$` with optional surrounding whitespace, exactly the reader's
 * `^[ \t]*\$\$[ \t]*$` (DisplayMathPreProcessor). So `> $$` and `- $$` never open or close display math, an indented
 * `  $$` inside a list item does, and an indent of four or more stays an indented code block (code wins in the editor;
 * the reader's pre-pass is code-blind, and the comment masks on both sides already treat such a line as code).
 */
const isDelimiterLine = (line) => line.text.trim() === '$$' && line.indent - line.baseIndent < TAB_WIDTH;

/**
 * Whether a `$$`-only line follows anywhere below. The reader's pre-pass (`DisplayMathPreProcessor`) pairs the opener
 * with the next such line, blank lines allowed and containers ignored, so a closer outside the opener's list item still
 * makes the opener a block (it then ends where the item does, see `DisplayMathBlock`). A block parser cannot rewind,
 * so this peeks at the input, in windows that double, up to LOOKAHEAD_CHARS.
 */
function closesAhead(cx, line) {
  const input = cx.input;
  if (!input || typeof input.read !== 'function') return false;
  const start = cx.lineStart + line.text.length + 1;
  if (start >= input.length) return false;
  const end = Math.min(input.length, start + LOOKAHEAD_CHARS);
  // Whole lines from a window that doubles: a closer one line away costs a line, not the 64 KB cap (which made a page
  // of `$$` lines quadratic).
  let from = start;
  let window = 512;
  while (from < end) {
    let to = Math.min(end, from + window);
    let chunk = input.read(from, to);
    if (to < end) {
      const nl = chunk.lastIndexOf('\n');
      if (nl < 0) { window *= 2; continue; } // one line longer than the window
      chunk = chunk.slice(0, nl);
      to = from + nl + 1;
    }
    for (const raw of chunk.split('\n')) if (raw.trim() === '$$') return true;
    from = to;
    window *= 2;
  }
  return false;
}

/**
 * A line that closes the generated ```` ```math ```` fence early: only backticks (three or more) and trailing
 * whitespace, indented less than four columns past the opener. The reader writes the block's content between a
 * ```` ```math ```` fence and a ```` ``` ```` fence, so a content line of that shape ends the math there (an info string
 * such as ```` ```js ````, or `~~~`, does not).
 */
const isFenceCloser = (line, openerIndent) => /^`{3,}$/.test(line.text.trim()) && line.indent - openerIndent < TAB_WIDTH;

/**
 * `$$` alone on a line to the next such line (blank lines inside allowed), as the render draws it: the block also ends
 * at a content line that is a bare ```` ``` ```` (the generated math fence closes there) and where the opener's
 * container ends (a dedented line, the fence cannot outlive its list item), whichever comes first. An unterminated `$$`
 * is left to the paragraph.
 */
const DisplayMathBlock = {
  name: 'DisplayMath',
  before: 'FencedCode',
  parse(cx, line) {
    if (line.next !== DOLLAR || !isDelimiterLine(line) || !closesAhead(cx, line)) return false;
    const from = cx.lineStart + line.pos;
    const openerIndent = line.indent;
    let to = cx.lineStart + line.text.length;
    let ended = false;
    let closed = false;
    while (cx.nextLine()) {
      if (line.depth < cx.depth) { ended = true; break; }
      if (line.text.trim() !== '') to = cx.lineStart + line.text.length;
      if (isDelimiterLine(line) || isFenceCloser(line, openerIndent)) { closed = true; cx.nextLine(); break; }
    }
    if (closed || ended) cx.addElement(cx.elt('DisplayMath', from, to));
    return true;
  },
  endLeaf: (cx, line) => line.next === DOLLAR && isDelimiterLine(line) && closesAhead(cx, line),
};

/**
 * Exclusive end of the inline math opening at the `$` at `at` of `text`, or -1: `$…$` (the shared Pandoc rule) and
 * `$$…$$`. Escapes are Lezer's own for the parser (`\$` never reaches here); callers that scan raw text skip them.
 */
export function inlineMathEnd(text, at) {
  if (text[at] !== '$') return -1;
  if (text[at + 1] === '$') {
    for (let j = at + 2; j < text.length - 1; j += 1) {
      if (text[j] === '\\') j += 1;
      else if (text[j] === '$' && text[j + 1] === '$') return j + 2;
    }
    return -1;
  }
  return matchInlineMath(text, at);
}

/** `$…$` and `$$…$$` within a paragraph. */
const InlineMathParser = {
  name: 'InlineMath',
  before: 'InlineCode',
  parse(cx, next, pos) {
    if (next !== DOLLAR) return -1;
    const text = cx.slice(pos, cx.end);
    const end = inlineMathEnd(text, 0);
    if (end < 0) return -1;
    return cx.addElement(cx.elt(text[1] === '$' ? 'InlineDisplayMath' : 'InlineMath', pos, pos + end));
  },
};

/** The wiki's math constructs as @lezer/markdown nodes: `DisplayMath`, `InlineMath`, `InlineDisplayMath`. */
export const mathExtension = {
  defineNodes: [{ name: 'DisplayMath', block: true }, 'InlineMath', 'InlineDisplayMath'],
  parseBlock: [DisplayMathBlock],
  parseInline: [InlineMathParser],
};

const OPAQUE = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'DisplayMath', 'InlineMath', 'InlineDisplayMath']);

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
