/**
 * Obsidian inline `#tags`, the editor's mirror of the server's `InlineTagSyntax` (wikantik-api). A tag's identity is its
 * normalised form: leading `#`s dropped, `/` flattened to `-`, lowercased. The reader and the indexers apply the same
 * function, so `#Foo`, `#foo` and a frontmatter `Foo` are one tag and a tag chip links to one place.
 */

/** `#Team/Core` -> `team-core`. */
export function normaliseTag(raw) {
  let t = String(raw ?? '').trim();
  while (t.startsWith('#')) t = t.slice(1);
  return t.replace(/\//g, '-').toLowerCase();
}

/** Where a tag chip leads: the tag view, which lists every page carrying the (normalised) tag. */
export function tagViewHref(tag) {
  return `/search?tag=${encodeURIComponent(normaliseTag(tag))}`;
}

const TAG = /(?<![^\s])#([\p{L}\p{N}_/-]+)/gu;
const TAG_IN_ROW = /(?<![^\s|])#([\p{L}\p{N}_/-]+)/gu;
const HAS_NON_DIGIT = /[\p{L}_-]/u;
/** An ATX heading line, also inside blockquote and list containers (the server's InlineTagSyntax.HEADING). */
const ATX_HEADING = /^(?:\s{0,3}(?:>|[-*+]|\d{1,9}[.)]))*\s{0,3}#{1,6}\s/;
/** Inline constructs whose text is never prose, for a table row (which the tree walk skips whole). */
const ROW_OPAQUE = [/(`+)[^`]+?\1/g, /!?\[(?:[^[\]\\]|\\.)*\]\((?:[^()]|\([^()]*\))*\)/g, /\[\[[^[\]]*\]\]/g, /(?<!\S)\S+:\/\/\S+/g, /\$[^$\s][^$]*\$/g];

/**
 * The inline tags of ONE line of text, for the live preview: `[{ from, to, written, tag }]` (offsets in `text`,
 * `from..to` spanning the `#`; `tag` is normalised). The server's rule (`InlineTagSyntax`): the `#` follows the start of
 * the line or whitespace, the tag holds a character that is not a digit, an ATX heading line holds none.
 * `blocked(from, to)` lets the caller refuse a tag (inside code, math, a link or a wikilink). `row` marks a GFM table
 * row: a cell starts a fresh run, so an unescaped `|` counts like whitespace, and the constructs the tree walk would
 * have refused (code span, link, wikilink, URL, math) are refused here.
 */
export function findInlineTags(text, blocked = () => false, row = false) {
  if (!text.includes('#') || ATX_HEADING.test(text)) return [];
  const opaque = [];
  if (row) {
    for (const re of ROW_OPAQUE) for (const m of text.matchAll(re)) opaque.push([m.index, m.index + m[0].length]);
  }
  const found = [];
  const source = row ? TAG_IN_ROW : TAG;
  for (const m of text.matchAll(source)) {
    if (row && m.index > 0 && text[m.index - 1] === '|' && text[m.index - 2] === '\\') continue;
    if (row && opaque.some(([a, b]) => m.index < b && m.index + m[0].length > a)) continue;
    if (!HAS_NON_DIGIT.test(m[1]) || blocked(m.index, m.index + m[0].length)) continue;
    found.push({ from: m.index, to: m.index + m[0].length, written: m[1], tag: normaliseTag(m[1]) });
  }
  return found;
}
