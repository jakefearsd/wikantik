import { parseWikiLink } from '../wikiLinkSyntax';

const BANG = 33;
const OPEN = 91;
const CLOSE = 93;
const NEWLINE = 10;

/**
 * Where the `]]` closing a wikilink opened at `at` (the first `[` of `[[`) starts, or -1. The inner text is one or more
 * characters that are none of `[`, `]` or a line break (the grammar of `WIKILINK_TOKEN`), so the scan stops at the
 * first such character and costs no more than the link itself: a run of unclosed `[[a` is linear.
 */
function closeAt(text, at) {
  let i = at + 2;
  while (i < text.length) {
    const c = text.charCodeAt(i);
    if (c === OPEN || c === NEWLINE) return -1;
    if (c === CLOSE) return i > at + 2 && text.charCodeAt(i + 1) === CLOSE ? i : -1;
    i += 1;
  }
  return -1;
}

/**
 * `[[Page]]`, `[[Page|Alias]]`, `[[Page#Heading]]`, `[[#Heading]]` as a `WikiLink` node, and `![[file.png|300]]` as a
 * `WikiEmbed` (a distinct node rather than a flag: Lezer nodes carry no per-instance data, and a reader of the tree
 * then tells the two apart by name). Children are `WikiLinkMark` for `[[`, `]]` and the alias `|`, `WikiLinkTarget`
 * for the text before the pipe (page and heading) and `WikiLinkAlias` for the text after it. Whether the inner text IS
 * a wikilink (`[[ x ]]` with a leading space is not) is `parseWikiLink`'s answer, the grammar shared with the reader.
 * Registered before Link, so `[[x]]` never becomes a link. A backslash before the first `[` is consumed by Lezer's
 * Escape first, so `\[[x]]` is text; `\![[x]]` leaves a plain `[[x]]` link, as the reader does. Code and math never
 * reach this parser because their nodes consume their text first.
 */
const WikiLinkParser = {
  name: 'WikiLink',
  parse(cx, next, pos) {
    const embed = next === BANG;
    if (embed ? cx.char(pos + 1) !== OPEN || cx.char(pos + 2) !== OPEN : next !== OPEN || cx.char(pos + 1) !== OPEN) return -1;
    const at = pos - cx.offset + (embed ? 1 : 0);
    const close = closeAt(cx.text, at);
    if (close < 0 || !parseWikiLink(cx.text.slice(at - (embed ? 1 : 0), close + 2))) return -1;
    const o = cx.offset;
    const innerFrom = at + 2;
    const inner = cx.text.slice(innerFrom, close);
    const pipe = inner.indexOf('|');
    const kids = [cx.elt('WikiLinkMark', pos, at + 2 + o)];
    const targetEnd = pipe < 0 ? close : innerFrom + pipe - (pipe > 0 && inner[pipe - 1] === '\\' ? 1 : 0);
    if (targetEnd > innerFrom) kids.push(cx.elt('WikiLinkTarget', innerFrom + o, targetEnd + o));
    if (pipe >= 0) {
      kids.push(cx.elt('WikiLinkMark', innerFrom + pipe + o, innerFrom + pipe + 1 + o));
      if (close > innerFrom + pipe + 1) kids.push(cx.elt('WikiLinkAlias', innerFrom + pipe + 1 + o, close + o));
    }
    kids.push(cx.elt('WikiLinkMark', close + o, close + 2 + o));
    return cx.addElement(cx.elt(embed ? 'WikiEmbed' : 'WikiLink', pos, close + 2 + o, kids));
  },
  before: 'Link',
};

export const wikiLinkExtension = {
  defineNodes: ['WikiLink', 'WikiEmbed', 'WikiLinkMark', 'WikiLinkTarget', 'WikiLinkAlias'],
  parseInline: [WikiLinkParser],
};

/** The WikiLink or WikiEmbed node holding `pos` (inclusive of both ends), or null. */
export function wikiLinkNodeAt(tree, pos) {
  // Both sides: a position at a link's end (the right half of its closing `]`) belongs to the link.
  for (const side of [1, -1]) {
    for (let n = tree.resolveInner(pos, side); n; n = n.parent) {
      if (n.name === 'WikiLink' || n.name === 'WikiEmbed') return n;
    }
  }
  return null;
}

/** `{ from, to }` of each wikilink or embed node in the window, document order. */
export function wikiLinkRanges(tree, { from = 0, to = tree.length } = {}) {
  const out = [];
  tree.iterate({
    from, to,
    enter: (n) => {
      if (n.name !== 'WikiLink' && n.name !== 'WikiEmbed') return undefined;
      out.push({ from: n.from, to: n.to, embed: n.name === 'WikiEmbed' });
      return false;
    },
  });
  return out;
}
