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

function leadingWidth(raw) {
  let width = 0;
  for (const ch of raw) {
    if (ch === ' ') width += 1;
    else if (ch === '\t') width += TAB_WIDTH - (width % TAB_WIDTH);
    else break;
  }
  return width;
}

/**
 * Whether a closing `$$` line follows while every line stays in the opener's container. The reader's pre-pass closes
 * at the next `$$`-only line, blank lines allowed, and is container-blind; the editor deliberately differs: when the
 * container (a list item, whose lines must stay indented to its content column) ends first, there is no math, because
 * a Lezer node must nest properly. A block parser cannot rewind, so this peeks at the input. Only a document or
 * list-item container can hold an opener (a `> ` line is never an opener), so the check is an indent comparison.
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
    for (const raw of chunk.split('\n')) {
      if (raw.trim() === '') continue;
      if (leadingWidth(raw) < line.baseIndent) return false; // the container has ended
      if (raw.trim() === '$$') return true;
    }
    from = to;
    window *= 2;
  }
  return false;
}

/** `$$` alone on a line to the next such line (blank lines inside allowed); an unterminated `$$` is left to the paragraph. */
const DisplayMathBlock = {
  name: 'DisplayMath',
  before: 'FencedCode',
  parse(cx, line) {
    if (line.next !== DOLLAR || !isDelimiterLine(line) || !closesAhead(cx, line)) return false;
    const from = cx.lineStart + line.pos;
    let to = cx.lineStart + line.text.length;
    let closed = false;
    while (cx.nextLine() && line.depth >= cx.depth) {
      to = cx.lineStart + line.text.length;
      if (isDelimiterLine(line)) { closed = true; cx.nextLine(); break; }
    }
    if (closed) cx.addElement(cx.elt('DisplayMath', from, to));
    return true;
  },
  endLeaf: (cx, line) => line.next === DOLLAR && isDelimiterLine(line) && closesAhead(cx, line),
};

/** `$…$` (the shared Pandoc rule) and `$$…$$` within a paragraph. Escapes are Lezer's own (`\$` never reaches here). */
const InlineMathParser = {
  name: 'InlineMath',
  before: 'InlineCode',
  parse(cx, next, pos) {
    if (next !== DOLLAR) return -1;
    const text = cx.slice(pos, cx.end);
    if (text[1] === '$') {
      for (let j = 2; j < text.length - 1; j += 1) {
        if (text[j] === '\\') j += 1;
        else if (text[j] === '$' && text[j + 1] === '$') return cx.addElement(cx.elt('InlineDisplayMath', pos, pos + j + 2));
      }
      return -1;
    }
    const end = matchInlineMath(text, 0);
    return end < 0 ? -1 : cx.addElement(cx.elt('InlineMath', pos, pos + end));
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
