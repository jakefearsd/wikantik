import { parseWikiLink } from '../wikiLinkSyntax';

const BANG = 33;
const OPEN = 91;
const CLOSE = 93;

/** Delimiter types of an open `[[` and an open `![[`: plain markers, resolved by `closeWikiLink` alone. */
const WikiOpen = {};
const EmbedOpen = {};

/**
 * The one open `[[` of an inline block, by its context: `{ delim, index, images }`. At most one is open at a time (a
 * second `[[` ends the first, as any bracket does), so every bracket of a block is answered without a scan of the parts.
 */
const openByContext = new WeakMap();

/** The open `[[` of `cx`, or null when there is none or an enclosing construct has taken it. */
function openOf(cx) {
  const rec = openByContext.get(cx);
  if (!rec) return null;
  if (cx.parts[rec.index] === rec.delim) return rec;
  openByContext.delete(cx);
  return null;
}

/** Ends the open `[[` of `cx` as text. */
function abandon(cx) {
  const rec = openOf(cx);
  if (!rec) return;
  // dropped when it is the last part, so a run of abandoned openers leaves nothing for the bracket parsers to walk
  if (rec.index === cx.parts.length - 1) cx.parts.pop();
  else cx.parts[rec.index] = null;
  openByContext.delete(cx);
}

/** The children of a wikilink that IS a link: `[[`, target, alias pipe, alias, `]]`. */
function linkChildren(cx, from, innerFrom, close, inner) {
  const pipe = inner.indexOf('|');
  const kids = [cx.elt('WikiLinkMark', from, innerFrom)];
  const targetEnd = pipe < 0 ? close : innerFrom + pipe - (pipe > 0 && inner[pipe - 1] === '\\' ? 1 : 0);
  if (targetEnd > innerFrom) kids.push(cx.elt('WikiLinkTarget', innerFrom, targetEnd));
  if (pipe >= 0) {
    kids.push(cx.elt('WikiLinkMark', innerFrom + pipe, innerFrom + pipe + 1));
    if (close > innerFrom + pipe + 1) kids.push(cx.elt('WikiLinkAlias', innerFrom + pipe + 1, close));
  }
  kids.push(cx.elt('WikiLinkMark', close, close + 2));
  return kids;
}

/** At `]`: closes the open `[[` when this is its `]]`, else ends it as text (a lone bracket inside is no wikilink). */
function closeWikiLink(cx, pos) {
  const rec = openOf(cx);
  if (!rec) return -1;
  const closes = cx.char(pos + 1) === CLOSE;
  if (rec.images > 0) {
    // the bracket closes an image opened inside the link text; `]]` there closes the image and then is a lone bracket
    rec.images -= 1;
    if (closes) abandon(cx);
    return -1;
  }
  if (!closes) { abandon(cx); return -1; }
  const { delim, index } = rec;
  const embed = delim.type === EmbedOpen;
  const from = delim.from;
  const innerFrom = delim.to;
  const inner = cx.slice(innerFrom, pos);
  // what the inline parsers made of the text between is dropped: the reader draws the pair from its source text
  cx.takeContent(index);
  openByContext.delete(cx);
  if (!parseWikiLink(cx.slice(from, pos + 2))) return cx.addElement(cx.elt('WikiLinkText', from, pos + 2));
  return cx.addElement(cx.elt(embed ? 'WikiEmbed' : 'WikiLink', from, pos + 2, linkChildren(cx, from, innerFrom, pos, inner)));
}

/**
 * `[[Page]]`, `[[Page|Alias]]`, `[[Page#Heading]]`, `[[#Heading]]` as a `WikiLink` node, and `![[file.png|300]]` as a
 * `WikiEmbed` (a distinct node rather than a flag: Lezer nodes carry no per-instance data, and a reader of the tree
 * then tells the two apart by name). Children are `WikiLinkMark` for `[[`, `]]` and the alias `|`, `WikiLinkTarget`
 * for the text before the pipe (page and heading) and `WikiLinkAlias` for the text after it.
 *
 * The brackets are matched as the reader's parser matches them, not by a scan of the text: `[[` opens, the inline
 * parsers read what follows (so a code span, inline math or an HTML tag that holds a bracket or runs past the `]]`
 * keeps it), and the next `]]` closes. Any other bracket in between that those parsers left as a character ends the
 * link as text: `[[a [b] c]]` and `[[a] b]]` are no wikilinks, and a second `[[` starts anew. An image inside
 * (`![x](u)`) is the one bracket pair the link survives. The match may span line breaks of the paragraph.
 *
 * What the pair is then follows the reader too. Whether the text IS a wikilink is `parseWikiLink`'s answer, the grammar
 * shared with the reader; a pair that is not (`[[ x ]]` with a leading space, an empty `[[]]`, one that spans lines)
 * is a `WikiLinkText`, which the reader draws as its characters. Either way the reader draws the pair from its source
 * text, so nothing inside one is a node: no code span, math, emphasis, tag or link, and a `%%` in it is an ordinary
 * comment marker (the server reads comments the same way: `ReaderStructure.renderedCode`).
 *
 * Registered before Link, so `[[x]]` never becomes a link. A backslash before the first `[` is consumed by Lezer's
 * Escape first, so `\[[x]]` is text; `\![[x]]` leaves a plain `[[x]]` link, as the reader does.
 */
const WikiLinkParser = {
  name: 'WikiLink',
  parse(cx, next, pos) {
    if (next === CLOSE) return closeWikiLink(cx, pos);
    const embed = next === BANG;
    if (!embed && next !== OPEN) return -1;
    const bracket = pos + (embed ? 1 : 0);
    if (cx.char(bracket) !== OPEN) return -1;
    if (cx.char(bracket + 1) !== OPEN || cx.char(bracket + 2) === OPEN) {
      // a lone `[` ends an open wikilink; `![` opens an image, whose own `]` the open link must let pass
      const rec = openOf(cx);
      if (rec && embed) rec.images += 1;
      else if (rec) abandon(cx);
      return -1;
    }
    abandon(cx);
    const index = cx.parts.length;
    const end = cx.addDelimiter(embed ? EmbedOpen : WikiOpen, pos, bracket + 2, true, false);
    openByContext.set(cx, { delim: cx.parts[index], index, images: 0 });
    return end;
  },
  before: 'Link',
};

/**
 * GFM's bare-URL autolink, stopped at the bracket that closes an open `[[`. Lezer's own parser ends a URL at an
 * unbalanced bracket only inside one of ITS links; without this, `[[https://example.com/a]]` would read its `]]` as
 * part of the address and never close. The reader links bare URLs after the wikilinks are found, so there the wikilink
 * always wins.
 *
 * @param autolink the `Autolink` extension of `@lezer/markdown`
 * @returns that extension with its URL parser wrapped
 */
export function wikiLinkAwareAutolink(autolink) {
  return {
    ...autolink,
    parseInline: autolink.parseInline.map((base) => ({
      ...base,
      parse(cx, next, pos) {
        const end = base.parse(cx, next, pos);
        if (end < 0 || !openOf(cx)) return end;
        const keep = /^(?:[^[\]]|\[[^\]]*\])*/.exec(cx.slice(pos, end))[0].length;
        if (pos + keep === end) return end;
        cx.parts.pop();
        return keep > 0 ? cx.addElement(cx.elt('URL', pos, pos + keep)) : -1;
      },
    })),
  };
}

export const wikiLinkExtension = {
  defineNodes: ['WikiLink', 'WikiEmbed', 'WikiLinkText', 'WikiLinkMark', 'WikiLinkTarget', 'WikiLinkAlias'],
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
