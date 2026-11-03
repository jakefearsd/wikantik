const EQUALS = 61;
const PUNCTUATION = /[\p{P}\p{S}]/u;
const WHITESPACE = /^$|[\p{Zs}\t\n\f\r]/u;

/**
 * Inline runs (one per block) that have seen a delimiter able to open. Lezer resolves each closer by scanning back
 * through every pending delimiter, so a long run of closers nothing can open (`a== a== a== ...`) is quadratic; a closer
 * is only worth recording once an opener exists, so it is skipped before that. Never decremented (a closer inside a
 * link's text resolves apart from the opener outside it), so this only ever errs towards keeping a delimiter.
 */
const sawOpener = new WeakSet();

const HighlightDelim = { resolve: 'Highlight', mark: 'HighlightMark' };

/**
 * `==highlight==` as an inline delimiter construct, modelled on @lezer/markdown's own Strikethrough. The rule is the
 * reader's (server `HighlightDelimiterProcessor`) and the preview pane's (`remarkHighlight.js`): a delimiter run of
 * EXACTLY two `=` (`===x===` and `=x=` are text), flanked as CommonMark emphasis is, so `a == b` and `== x ==` are
 * text and `==**x**==` is a highlight. A backslash escapes the first `=` (Lezer's Escape consumes it, leaving a run
 * of one). Delimiters inside code and math never reach this parser, because those nodes consume their text first; a
 * pair may cross a soft line break within its paragraph because the whole paragraph is one inline run.
 */
const HighlightParser = {
  name: 'Highlight',
  parse(cx, next, pos) {
    if (next !== EQUALS || cx.char(pos + 1) !== EQUALS || cx.char(pos + 2) === EQUALS || cx.char(pos - 1) === EQUALS) return -1;
    const before = cx.slice(pos - 1, pos);
    const after = cx.slice(pos + 2, pos + 3);
    const spaceBefore = WHITESPACE.test(before);
    const spaceAfter = WHITESPACE.test(after);
    const punctBefore = PUNCTUATION.test(before);
    const punctAfter = PUNCTUATION.test(after);
    const open = !spaceAfter && (!punctAfter || spaceBefore || punctBefore);
    const close = !spaceBefore && (!punctBefore || spaceAfter || punctAfter) && sawOpener.has(cx);
    if (open) sawOpener.add(cx);
    return cx.addDelimiter(HighlightDelim, pos, pos + 2, open, close);
  },
  after: 'Emphasis',
};

/** The wiki's `==highlight==` construct as @lezer/markdown nodes: `Highlight` wrapping two `HighlightMark`s. */
export const highlightExtension = {
  defineNodes: ['Highlight', 'HighlightMark'],
  parseInline: [HighlightParser],
};
