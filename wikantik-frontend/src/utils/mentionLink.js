import { escapeLinkText } from './wikiLinkComplete';

const WORD = /[\p{L}\p{N}]/u;
function wholeWordAt(text, from, len) {
  const before = from === 0 || !WORD.test(text[from - 1]);
  const after = from + len >= text.length || !WORD.test(text[from + len]);
  return before && after;
}

// Spans on a line that must never be linked again: inline code, and link text `[…]` with its optional `(url)`.
const PROTECTED = /(`+)[^\n]*?\1|\[[^\]\n]*\](?:\([^)\n]*\))?/g;
function insideCodeOrLink(text, from, to) {
  const lineStart = text.lastIndexOf('\n', from - 1) + 1;
  const nl = text.indexOf('\n', to);
  const line = text.slice(lineStart, nl === -1 ? text.length : nl);
  for (const m of line.matchAll(PROTECTED)) {
    const a = lineStart + m.index;
    if (from < a + m[0].length && to > a) return true;
  }
  return false;
}

export function locatePhrase(text, mention) {
  const { phrase, from, to } = mention;
  if (text.slice(from, to) === phrase && !insideCodeOrLink(text, from, to)) return { from, to };
  const hay = text.toLowerCase();
  const needle = phrase.toLowerCase();
  let best = null;
  for (let i = hay.indexOf(needle); i !== -1; i = hay.indexOf(needle, i + 1)) {
    if (!wholeWordAt(text, i, needle.length) || insideCodeOrLink(text, i, i + needle.length)) continue;
    if (best === null || Math.abs(i - from) < Math.abs(best - from)) best = i;
  }
  return best === null ? null : { from: best, to: best + phrase.length };
}

export function linkMarkup(phrase, target) {
  return `[${escapeLinkText(phrase)}](${target})`;
}
