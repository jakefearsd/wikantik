/**
 * Obsidian `%%comments%%`, the editor's copy of `ObsidianComments` (wikantik-api): the same contract and the same
 * shared fixture (`__fixtures__/comments.json`, run by both sides). A comment (inline, or spanning lines) is removed
 * and replaced by the newlines it held, so line numbers and preview scroll sync stay stable.
 *
 * - The syntax tree decides what is code and what is math, never a hand scan: a `%%` inside a fenced or indented code
 *   block, an inline code span, a display math block or inline math (`$…$`, `$$…$$`) opens and closes nothing. The
 *   editor already holds that tree; text-only callers get one from `dialectParser` (commonmark + GFM + the dialect).
 *   So a Mermaid fence's own `%% comment` lines survive, and a `%%` before such a fence never closes on one of them.
 * - An unterminated `%%` stays literal. The closing `%%` is the next one after the opener outside code and math; code
 *   wins, so a backtick in a comment that pairs with a later one can leave the comment unterminated.
 * - A position past the end of a partial tree is plain text; the live preview rebuilds when the parse completes.
 *
 * Comments are hidden, not secret: this is a presentation rule, applied to the editor preview and to live preview's
 * dimming; never to what is saved.
 */
import { dialectParser, opaqueRanges } from './dialect';

/**
 * `[{ from, to }]` of each comment of `text` (delimiters included, in order; an unterminated `%%` is none), reading
 * code and math off `tree`, the syntax tree of that same text. Offsets are in `text`.
 */
export function commentRangesInTree(text, tree) {
  if (!text || !text.includes('%%')) return [];
  // One walk of the tree, then a binary search per marker: asking the tree for each position costs time in the number
  // of siblings, which made a paragraph of 40000 math spans quadratic.
  const opaque = opaqueRanges(tree);
  const inside = (pos) => {
    let lo = 0;
    let hi = opaque.length - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (pos < opaque[mid][0]) hi = mid - 1;
      else if (pos >= opaque[mid][1]) lo = mid + 1;
      else return true;
    }
    return false;
  };
  const free = (at) => !inside(at) && !inside(at + 1);
  const next = (from) => {
    let at = text.indexOf('%%', from);
    while (at >= 0 && !free(at)) at = text.indexOf('%%', at + 1);
    return at;
  };
  const out = [];
  for (let at = next(0); at >= 0;) {
    const close = next(at + 2);
    if (close < 0) break;
    out.push({ from: at, to: close + 2 });
    at = next(close + 2);
  }
  return out;
}

/** `[{ from, to }]` of each comment of `text`, parsed with the dialect parser (offsets are in `text`, `\r\n` included). */
export function commentRanges(text) {
  if (!text || !text.includes('%%')) return [];
  return commentRangesInTree(text, dialectParser.parse(text));
}

/** The text without its comments, each replaced by the newlines it held; the same string when it holds none. */
export function stripComments(text) {
  const ranges = commentRanges(text);
  if (ranges.length === 0) return text;
  let out = '';
  let copied = 0;
  for (const { from, to } of ranges) {
    out += text.slice(copied, from);
    for (let p = from; p < to; p += 1) if (text[p] === '\n') out += '\n';
    copied = to;
  }
  return out + text.slice(copied);
}

export default stripComments;
