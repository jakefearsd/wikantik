/**
 * Where the `==highlight==` spans of ONE line of text are, for the live preview (the preview pane uses the
 * micromark construct in `remarkHighlight.js`, the reader the server's `HighlightDelimiterProcessor`). The same
 * rule: a delimiter run of EXACTLY two `=` (`===x===` and `=x=` are text), flanked as CommonMark emphasis is, so
 * `a == b` and `== x ==` are text and `==**x**==` is a highlight. A backslash before a run escapes it.
 *
 * `blocked(from, to)` lets the caller refuse a delimiter run (inside code, math or a link), which then takes no
 * part in pairing; the text between a pair may hold anything.
 */
const PUNCTUATION = /[\p{P}\p{S}]/u;
const WHITESPACE = /[\p{Zs}\t\n\f\r]/u;

/** micromark's character classes: 1 whitespace (or no character), 2 punctuation, 0 otherwise. */
function classify(ch) {
  if (ch === undefined || WHITESPACE.test(ch)) return 1;
  return PUNCTUATION.test(ch) ? 2 : 0;
}

/** `[{ from, to, openEnd, closeStart }]` (offsets in `text`): `from..openEnd` and `closeStart..to` are the delimiters. */
export function findHighlights(text, blocked = () => false) {
  if (!text.includes('==')) return [];
  const runs = [];
  for (let i = 0; i < text.length; i += 1) {
    if (text[i] !== '=') continue;
    let j = i;
    while (text[j] === '=') j += 1;
    const escaped = i > 0 && text[i - 1] === '\\';
    if (j - i === 2 && !escaped && !blocked(i, j)) {
      const before = classify(text[i - 1]);
      const after = classify(text[j]);
      runs.push({ from: i, to: j, open: !after || (after === 2 && !!before), close: !before || (before === 2 && !!after) });
    }
    i = j - 1;
  }
  const spans = [];
  const openers = [];
  for (const run of runs) {
    if (run.close && openers.length) {
      const opener = openers.pop();
      spans.push({ from: opener.from, to: run.to, openEnd: opener.to, closeStart: run.from });
    } else if (run.open) {
      openers.push(run);
    }
  }
  return spans.sort((a, b) => a.from - b.from);
}
