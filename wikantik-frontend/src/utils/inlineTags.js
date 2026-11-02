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
const HAS_NON_DIGIT = /[\p{L}_-]/u;
const ATX_HEADING = /^\s{0,3}#{1,6}\s/;

/**
 * The inline tags of ONE line of text, for the live preview: `[{ from, to, written, tag }]` (offsets in `text`,
 * `from..to` spanning the `#`; `tag` is normalised). The server's rule (`InlineTagSyntax`): the `#` follows the start of
 * the line or whitespace, the tag holds a character that is not a digit, an ATX heading line holds none.
 * `blocked(from, to)` lets the caller refuse a tag (inside code, math, a link or a wikilink).
 */
export function findInlineTags(text, blocked = () => false) {
  if (!text.includes('#') || ATX_HEADING.test(text)) return [];
  const found = [];
  for (const m of text.matchAll(TAG)) {
    if (!HAS_NON_DIGIT.test(m[1]) || blocked(m.index, m.index + m[0].length)) continue;
    found.push({ from: m.index, to: m.index + m[0].length, written: m[1], tag: normaliseTag(m[1]) });
  }
  return found;
}
