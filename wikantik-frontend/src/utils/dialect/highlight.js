const EQUALS = 61;
const BACKSLASH = 92;
const PUNCTUATION = /[\p{P}\p{S}]/u;
const WHITESPACE = /^$|[\p{Zs}\t\n\f\r]/u;

/**
 * Inline runs (one per block) that have seen a delimiter able to open. Lezer resolves each closer by scanning back
 * through every pending delimiter, so a long run of closers nothing can open (`a== a== a== ...`) is quadratic; a closer
 * is only worth recording once an opener exists, so it is skipped before that. Never decremented (a closer inside a
 * link's text resolves apart from the opener outside it), so this only ever errs towards keeping a delimiter.
 */
const sawOpener = new WeakSet();

/**
 * Most highlights one run may hold open at once. @lezer/markdown writes its tree by recursion, one frame pair per level
 * of nesting, so a pathological paragraph of unmatched or nested openers overflows the stack (a RangeError out of the
 * parser, which the editor cannot recover from); how deep depends on how warm the JIT is, around 5000 for `==` and
 * 10000 for `**` and `~~`. Real text nests a handful. Past the cap a run is text. The count is a bound, not an exact
 * depth: each recorded closer takes one off, so sequential `==a== ==b==` pairs never reach it.
 */
const MAX_OPEN = 1000;
const openDepth = new WeakMap();

/** Whether the character at `at` is escaped: preceded by an odd number of backslashes. */
const escapedAt = (cx, at) => {
  let slashes = 0;
  while (cx.char(at - 1 - slashes) === BACKSLASH) slashes += 1;
  return slashes % 2 === 1;
};

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
    if (next !== EQUALS || cx.char(pos + 1) !== EQUALS || cx.char(pos + 2) === EQUALS || (cx.char(pos - 1) === EQUALS && !escapedAt(cx, pos - 1))) return -1;
    const before = cx.slice(pos - 1, pos);
    const after = cx.slice(pos + 2, pos + 3);
    const spaceBefore = WHITESPACE.test(before);
    const spaceAfter = WHITESPACE.test(after);
    const punctBefore = PUNCTUATION.test(before);
    const punctAfter = PUNCTUATION.test(after);
    const depth = openDepth.get(cx) ?? 0;
    const close = !spaceBefore && (!punctBefore || spaceAfter || punctAfter) && sawOpener.has(cx);
    const open = !spaceAfter && (!punctAfter || spaceBefore || punctBefore) && (depth < MAX_OPEN || close);
    if (open) sawOpener.add(cx);
    openDepth.set(cx, Math.max(0, depth + (open ? 1 : 0) - (close ? 1 : 0)));
    return cx.addDelimiter(HighlightDelim, pos, pos + 2, open, close);
  },
  after: 'Emphasis',
};

/** The wiki's `==highlight==` construct as @lezer/markdown nodes: `Highlight` wrapping two `HighlightMark`s. */
export const highlightExtension = {
  defineNodes: ['Highlight', 'HighlightMark'],
  parseInline: [HighlightParser],
};
