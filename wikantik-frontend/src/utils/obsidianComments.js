/**
 * Obsidian `%%comments%%`, the editor's copy of `ObsidianComments` (wikantik-api): the same contract and the same
 * shared fixture (`__fixtures__/comments.json`, run by both sides). A comment (inline, or spanning lines) is removed
 * and replaced by the newlines it held, so line numbers and preview scroll sync stay stable.
 *
 * - Code is never read: fenced and indented code and inline code spans (a port of `CodeMask`), so a Mermaid
 *   fence's own `%% comment` lines survive.
 * - Math is never read: inline `$…$` (`inlineMath.js`) and `$$ … $$` blocks, where `%` is a LaTeX comment.
 * - An unterminated `%%` stays literal. The closing `%%` is the next one after the opener.
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

function maskInlineMath(text, mask) {
  const n = text.length;
  for (let i = 0; i < n;) {
    const c = text[i];
    if (c === '\\') i += 2;
    else if (c !== '$') i += 1;
    else if (text[i + 1] === '$') i += 2;
    else if (mask[i] === 1) i += 1;
    else {
      const end = matchInlineMath(text, i);
      if (end > 0) {
        maskRange(mask, i, end);
        i = end;
      } else i += 1;
    }
  }
}

/** `[{ from, to }]` of each comment, delimiters included, in order (an unterminated `%%` is none). */
export function commentRanges(text) {
  if (!text || !text.includes('%%')) return [];
  const mask = codeMask(text);
  maskDisplayMath(text, mask);
  maskInlineMath(text, mask);
  const out = [];
  let at = text.indexOf('%%');
  while (at >= 0) {
    if (mask[at] === 1) {
      at = text.indexOf('%%', at + 1);
      continue;
    }
    const close = text.indexOf('%%', at + 2);
    if (close < 0) break;
    out.push({ from: at, to: close + 2 });
    at = text.indexOf('%%', close + 2);
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
