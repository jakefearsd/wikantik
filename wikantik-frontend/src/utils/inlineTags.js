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

const TAG_CHAR = /[\p{L}\p{N}_/-]/u;
const HAS_NON_DIGIT = /[\p{L}_-]/u;

/**
 * THE inline tag rule (the server's `InlineTagSyntax`), shared by the editor's parser and the preview pane's remark
 * plugin: for a `#` at `text[i]`, the index just past its tag, or -1 when it starts none. The `#` must start the text or
 * follow whitespace, the body is `[\p{L}\p{N}_/-]+`, and the body needs a character that is not a digit (`#123` is
 * text, `#fff` is a tag). Everything else a tag must avoid (headings, code, links, plugin text) is the caller's context.
 */
export function tagEndAt(text, i) {
  if (text[i] !== '#' || (i > 0 && !/\s/.test(text[i - 1]))) return -1;
  let end = i + 1;
  while (end < text.length && TAG_CHAR.test(text[end])) end += 1;
  return end > i + 1 && HAS_NON_DIGIT.test(text.slice(i + 1, end)) ? end : -1;
}

/**
 * The `[{…}]` spans of a text (a plugin, an access rule, a variable): the reader makes each a link whose text is never a
 * tag. The server's reading (the reader's parse, `ReaderTags`): the leftmost opener to the first `}` after it, when `]` follows.
 */
export function pluginSpans(value) {
  const spans = [];
  let from = 0;
  for (;;) {
    const opener = value.indexOf('[{', from);
    if (opener < 0) return spans;
    const brace = value.indexOf('}', opener + 2);
    if (brace < 0) return spans;
    if (brace === opener + 2 || value[brace + 1] !== ']') {
      from = brace + 1;
    } else {
      spans.push([opener, brace + 2]);
      from = brace + 2;
    }
  }
}
