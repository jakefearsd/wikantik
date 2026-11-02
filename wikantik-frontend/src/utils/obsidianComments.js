/**
 * Obsidian `%%comments%%`, the editor's copy of `ObsidianComments` (wikantik-api): the same contract and the same
 * shared fixture (`__fixtures__/comments.json`, run by both sides). A comment (inline, or spanning lines) is removed
 * and replaced by the newlines it held, so line numbers and preview scroll sync stay stable.
 *
 * - Code is never read, neither for an opening nor for a closing `%%`: fenced and indented code and inline code spans
 *   (a port of `CodeMask`) and fences inside block quotes and callouts, so a Mermaid fence's own `%% comment` lines
 *   survive and a `%%` before such a fence never closes on one of them.
 * - Math is never read: inline `$…$` (`inlineMath.js`), inline `$$…$$` within a paragraph, and `$$ … $$` blocks, where
 *   `%` is a LaTeX comment.
 * - An unterminated `%%` stays literal. The closing `%%` is the next one after the opener outside code and math; code
 *   wins, so a backtick in a comment that pairs with a later one can leave the comment unterminated.
 *
 * Comments are hidden, not secret: this is a presentation rule, applied to the editor preview and to live preview's
 * dimming; never to what is saved.
 */
import { matchInlineMath } from './inlineMath';

const LIST_ITEM = /^\s*([-*+]|\d{1,9}[.)])\s.*/;

function indentOf(line) {
  let w = 0;
  for (const c of line) {
    if (c === ' ') w += 1;
    else if (c === '\t') w += 4;
    else break;
  }
  return w;
}
function leadingSpaces(line) {
  let i = 0;
  while (i < line.length && line[i] === ' ') i += 1;
  return i;
}
function runLength(line, from, ch) {
  let k = from;
  while (k < line.length && line[k] === ch) k += 1;
  return k - from;
}
function maskRange(mask, from, to) {
  for (let p = from; p < to && p < mask.length; p += 1) mask[p] = 1;
}

/** Paired backtick runs (N backticks closed by the next run of exactly N), partners found right to left. */
function maskInlineCode(mask, line, base) {
  const runs = [];
  for (let i = line.indexOf('`'); i >= 0;) {
    const n = runLength(line, i, '`');
    runs.push([i, n]);
    i = line.indexOf('`', i + n);
  }
  const partner = new Array(runs.length);
  const nextOfLength = new Map();
  for (let r = runs.length - 1; r >= 0; r -= 1) {
    partner[r] = nextOfLength.has(runs[r][1]) ? nextOfLength.get(runs[r][1]) : -1;
    nextOfLength.set(runs[r][1], r);
  }
  for (let r = 0; r < runs.length;) {
    const close = partner[r];
    if (close < 0) { r += 1; continue; }
    maskRange(mask, base + runs[r][0], base + runs[close][0] + runs[close][1]);
    r = close + 1;
  }
}

/** A port of `CodeMask.of`: 1 at every offset inside fenced code, indented code or an inline code span. */
function codeMask(text) {
  const mask = new Uint8Array(text.length);
  let fenceChar = '';
  let fenceLen = 0;
  let inIndented = false;
  let sawBlank = false;
  let lastNonBlank = null;
  let listContentIndent = 0;
  let fenceBase = 0;

  const isIndentedCode = (line) => {
    if (indentOf(line) < 4) return false;
    if (inIndented) return true;
    if (lastNonBlank === null) return true;
    return sawBlank && !LIST_ITEM.test(lastNonBlank) && indentOf(lastNonBlank) < 2;
  };
  const opensFence = (line) => {
    const ind = leadingSpaces(line);
    const base = ind > 3 && listContentIndent > 0 && ind >= listContentIndent ? listContentIndent : 0;
    if (ind - base > 3 || ind >= line.length) return false;
    const c = line[ind];
    if (c !== '`' && c !== '~') return false;
    const len = runLength(line, ind, c);
    if (len < 3 || (c === '`' && line.indexOf('`', ind + len) >= 0)) return false;
    fenceChar = c;
    fenceLen = len;
    fenceBase = base;
    return true;
  };
  const closesFence = (line) => {
    const ind = leadingSpaces(line);
    if (ind - fenceBase > 3 || ind >= line.length || line[ind] !== fenceChar) return false;
    const len = runLength(line, ind, fenceChar);
    return len >= fenceLen && line.slice(ind + len).trim() === '';
  };

  let offset = 0;
  for (const line of text.split('\n')) {
    if (fenceLen > 0) {
      maskRange(mask, offset, offset + line.length);
      if (closesFence(line)) fenceLen = 0;
    } else if (line.trim() === '') {
      sawBlank = true;
    } else if (isIndentedCode(line)) {
      inIndented = true;
      sawBlank = false;
      maskRange(mask, offset, offset + line.length);
    } else {
      inIndented = false;
      sawBlank = false;
      lastNonBlank = line;
      if (LIST_ITEM.test(line)) listContentIndent = LIST_ITEM.exec(line)[1].length + 1 + (line.length - line.trimStart().length);
      else if (indentOf(line) < listContentIndent) listContentIndent = 0;
      if (opensFence(line)) maskRange(mask, offset, offset + line.length);
      else maskInlineCode(mask, line, offset);
    }
    offset += line.length + 1;
  }
  return mask;
}

/** How many quote levels (`>` after at most three spaces, each) a line opens with. */
function quoteDepth(line) {
  let levels = 0;
  let p = 0;
  for (;;) {
    const at = skipSpaces(line, p, 3);
    if (at < 0 || at >= line.length || line[at] !== '>') return levels;
    levels += 1;
    p = line[at + 1] === ' ' ? at + 2 : at + 1;
  }
}
/** Where the content after `levels` quote markers starts, or -1 when the line has fewer. */
function quoteContent(line, levels) {
  let p = 0;
  for (let level = 0; level < levels; level += 1) {
    const at = skipSpaces(line, p, 3);
    if (at < 0 || at >= line.length || line[at] !== '>') return -1;
    p = line[at + 1] === ' ' ? at + 2 : at + 1;
  }
  return p;
}
function skipSpaces(line, from, max) {
  let p = from;
  while (p < line.length && line[p] === ' ') p += 1;
  return p - from > max ? -1 : p;
}

/**
 * Fences opened inside a block quote (a callout is one), which `codeMask` reads as text: from the opening line to the
 * closing one at the same quote depth, or to the first line that leaves that depth. Lines already code are left alone.
 */
function maskQuotedFences(text, mask) {
  if (!text.includes('>')) return;
  let fenceChar = '';
  let fenceLen = 0;
  let depth = 0;
  let offset = 0;
  for (const line of text.split('\n')) {
    let inFence = false;
    if (fenceLen > 0) {
      const content = quoteContent(line, depth);
      if (content >= 0) {
        inFence = true;
        const at = skipSpaces(line, content, 3);
        if (at >= 0 && at < line.length && line[at] === fenceChar) {
          const len = runLength(line, at, fenceChar);
          if (len >= fenceLen && line.slice(at + len).trim() === '') fenceLen = 0;
        }
      } else fenceLen = 0; // the quote ended, and the fence with it
    }
    if (!inFence && line.length > 0 && mask[offset] !== 1) {
      const levels = quoteDepth(line);
      if (levels > 0) {
        const at = skipSpaces(line, quoteContent(line, levels), 3);
        const c = at >= 0 ? line[at] : undefined;
        if (c === '`' || c === '~') {
          const len = runLength(line, at, c);
          if (len >= 3 && !(c === '`' && line.indexOf('`', at + len) >= 0)) {
            fenceChar = c;
            fenceLen = len;
            depth = levels;
            inFence = true;
          }
        }
      }
    }
    if (inFence) maskRange(mask, offset, offset + line.length);
    offset += line.length + 1;
  }
}

/** A line of only `$$` opens a display block the next such line closes (code lines excepted). */
function maskDisplayMath(text, mask) {
  let open = false;
  let offset = 0;
  while (offset <= text.length) {
    let end = text.indexOf('\n', offset);
    if (end < 0) end = text.length;
    const fence = !open && mask[Math.min(offset, mask.length - 1)] === 1 && end > offset;
    const delimiter = !fence && text.slice(offset, end).trim() === '$$';
    if (open || delimiter) maskRange(mask, offset, end);
    if (delimiter) open = !open;
    offset = end + 1;
  }
}

/** The exclusive end of `$$…$$` opening at `i` inside a paragraph: the next `$$` outside code, before any blank line. */
function inlineDisplayEnd(text, mask, i) {
  let lineBlank = false;
  for (let j = i + 2; j < text.length; j += 1) {
    const c = text[j];
    if (c === '\n') {
      if (lineBlank) return -1;
      lineBlank = true;
    } else if (c === '\\') {
      j += 1;
      lineBlank = false;
    } else if (c === '$' && text[j + 1] === '$' && mask[j] !== 1) {
      return j + 2;
    } else if (c !== ' ' && c !== '\t' && c !== '\r') {
      lineBlank = false;
    }
  }
  return -1;
}

/** Inline `$…$` and `$$…$$` outside code and display math. */
function maskInlineMath(text, mask) {
  const n = text.length;
  for (let i = 0; i < n;) {
    const c = text[i];
    if (c === '\\') i += 2;
    else if (c !== '$' || mask[i] === 1) i += 1;
    else {
      const twice = text[i + 1] === '$';
      const end = twice ? inlineDisplayEnd(text, mask, i) : matchInlineMath(text, i);
      if (end > 0) {
        maskRange(mask, i, end);
        i = end;
      } else i += twice ? 2 : 1;
    }
  }
}

/** The next `%%` at or after `from` with neither of its characters in code or math, or -1. */
function nextMarker(text, mask, from) {
  let at = text.indexOf('%%', from);
  while (at >= 0 && (mask[at] === 1 || mask[at + 1] === 1)) at = text.indexOf('%%', at + 1);
  return at;
}

/** `[{ from, to }]` of each comment, delimiters included, in order (an unterminated `%%` is none). */
export function commentRanges(text) {
  if (!text || !text.includes('%%')) return [];
  const mask = codeMask(text);
  maskQuotedFences(text, mask);
  maskDisplayMath(text, mask);
  maskInlineMath(text, mask);
  const out = [];
  let at = nextMarker(text, mask, 0);
  while (at >= 0) {
    const close = nextMarker(text, mask, at + 2);
    if (close < 0) break;
    out.push({ from: at, to: close + 2 });
    at = nextMarker(text, mask, close + 2);
  }
  return out;
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
