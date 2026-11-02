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
