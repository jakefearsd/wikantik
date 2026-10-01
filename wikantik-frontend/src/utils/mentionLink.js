import { escapeLinkText } from './wikiLinkComplete';

const WORD = /[\p{L}\p{N}]/u;
function wholeWordAt(text, from, len) {
  const before = from === 0 || !WORD.test(text[from - 1]);
  const after = from + len >= text.length || !WORD.test(text[from + len]);
  return before && after;
}

export function locatePhrase(text, mention) {
  const { phrase, from, to } = mention;
  if (text.slice(from, to) === phrase) return { from, to };
  const hay = text.toLowerCase();
  const needle = phrase.toLowerCase();
  let best = null;
  for (let i = hay.indexOf(needle); i !== -1; i = hay.indexOf(needle, i + 1)) {
    if (wholeWordAt(text, i, needle.length) && (best === null || Math.abs(i - from) < Math.abs(best - from))) best = i;
  }
  return best === null ? null : { from: best, to: best + phrase.length };
}

export function linkMarkup(phrase, target) {
  return `[${escapeLinkText(phrase)}](${target})`;
}
