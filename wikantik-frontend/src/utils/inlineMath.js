/**
 * The one inline-math rule shared by the editor preview (remarkInlineMath), live preview (ranges.js) and the
 * server: Pandoc `tex_math_dollars`, which Obsidian follows.
 *
 * At an UNESCAPED `$`: `$$` never opens inline math; the char after the opener must exist and not be whitespace;
 * a backslash consumes itself and the next char; whitespace inside is allowed unless it spans a blank line or
 * directly precedes the closing `$`; the first unescaped `$` closes, and a closer followed by an ASCII digit
 * means NO MATCH at this opener (no search for a later closer). On NO MATCH the `$` is literal text.
 */
const isWs = (c) => c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\v';
const isDigit = (c) => c >= '0' && c <= '9';

/** Exclusive end index of the inline math opening at the unescaped `$` at `i`, or -1. */
export function matchInlineMath(text, i) {
  if (text[i] !== '$') return -1;
  const first = text[i + 1];
  if (first === undefined || first === '$' || isWs(first)) return -1;
  let j = i + 1;
  let newlines = 0;
  let prevWs = false;
  while (j < text.length) {
    const c = text[j];
    if (c === '\\') {
      if (j + 1 >= text.length) return -1;
      j += 2;
      prevWs = false;
      newlines = 0;
    } else if (c === '$') {
      if (prevWs) return -1;
      return isDigit(text[j + 1] ?? '') ? -1 : j + 1;
    } else {
      if (isWs(c)) {
        if (c === '\n') newlines += 1;
        if (newlines > 1) return -1;
        prevWs = true;
      } else {
        prevWs = false;
        newlines = 0;
      }
      j += 1;
    }
  }
  return -1;
}

/** Every inline math span in `text`, left to right: `{ from, to, tex }` (`tex` excludes the delimiters). */
export function findInlineMath(text) {
  const out = [];
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (c === '\\') { i += 1; continue; }
    if (c !== '$') continue;
    if (text[i + 1] === '$') { i += 1; continue; }
    const end = matchInlineMath(text, i);
    if (end < 0) continue;
    out.push({ from: i, to: end, tex: text.slice(i + 1, end - 1) });
    i = end - 1;
  }
  return out;
}
