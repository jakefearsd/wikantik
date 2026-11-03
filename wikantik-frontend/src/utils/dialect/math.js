import { matchInlineMath } from '../inlineMath';

const DOLLAR = 36;
const LOOKAHEAD_CHARS = 64 * 1024;
const CONTAINER_PREFIX = /^[ \t>]*/;
const isDelimiterLine = (line) => line.text.slice(line.pos).trim() === '$$' && line.indent - line.baseIndent < 4;

/**
 * Whether a closing `$$` line follows. The reader's pre-pass (DisplayMathPreProcessor) closes at the next line that
 * is only `$$`, blank lines allowed. A block parser cannot rewind, so this peeks at the input; container prefixes
 * (`>`) are stripped approximately.
 */
function closesAhead(cx, line) {
  const input = cx.input;
  if (!input || typeof input.read !== 'function') return false;
  const start = cx.lineStart + line.text.length + 1;
  if (start >= input.length) return false;
  return input.read(start, Math.min(input.length, start + LOOKAHEAD_CHARS)).split('\n')
    .some((raw) => raw.replace(CONTAINER_PREFIX, '').trim() === '$$');
}

/** `$$` alone on a line to the next such line (blank lines inside allowed); an unterminated `$$` is left to the paragraph. */
const DisplayMathBlock = {
  name: 'DisplayMath',
  before: 'FencedCode',
  parse(cx, line) {
    if (line.next !== DOLLAR || !isDelimiterLine(line) || !closesAhead(cx, line)) return false;
    const from = cx.lineStart + line.pos;
    let to = cx.lineStart + line.text.length;
    while (cx.nextLine() && line.depth >= cx.depth) {
      to = cx.lineStart + line.text.length;
      if (isDelimiterLine(line)) { cx.nextLine(); break; }
    }
    cx.addElement(cx.elt('DisplayMath', from, to));
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

/** Whether `pos` lies inside code or math, per the tree. */
export function opaqueAt(tree, pos) {
  for (let n = tree.resolveInner(pos, 1); n; n = n.parent) {
    if (OPAQUE.has(n.name) && pos >= n.from && pos < n.to) return true;
  }
  return false;
}
