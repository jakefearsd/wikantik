// Obsidian-style wikilinks: [[Page]], [[Page|Alias]], [[Page#Heading]], [[#Heading]], ![[Page]],
// ![[Owner/file.png|300]]. Mirrors com.wikantik.api.parser.WikiLinkSyntax (Java) rule for rule.
// findWikiLinks does no code masking: callers pass prose (remark text nodes are never code).
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import { visit } from 'unist-util-visit';
import { slugify } from './headings';

export const WIKILINK_TOKEN = /(!?)\[\[([^[\]\n]+?)\]\]/g;
export const isImageFileName = /\.(png|jpe?g|gif|svg|webp|bmp|avif)$/i;
const SIZE = /^(\d{1,5})(?:x(\d{1,5}))?$/;
const parser = unified().use(remarkParse).use(remarkGfm);

const blankToNull = (s) => (s === '' ? null : s);

function sizeOf(alias) {
  const m = alias == null ? null : SIZE.exec(alias);
  return m ? [parseInt(m[1], 10), m[2] == null ? -1 : parseInt(m[2], 10)] : null;
}

function build(bang, inner, from, to, raw, bangEscaped) {
  if (inner === '' || /^\s/.test(inner)) return null;
  const pipe = inner.indexOf('|');
  const escapedPipe = pipe > 0 && inner[pipe - 1] === '\\';
  const targetPart = pipe < 0 ? inner : inner.slice(0, escapedPipe ? pipe - 1 : pipe);
  const alias = pipe < 0 ? null : blankToNull(inner.slice(pipe + 1).trim());
  const hash = targetPart.indexOf('#');
  const target = (hash < 0 ? targetPart : targetPart.slice(0, hash)).trimEnd();
  const heading = hash < 0 ? null : blankToNull(targetPart.slice(hash + 1).trim());
  if (target === '' && heading === null) return null;
  const slash = target.indexOf('/');
  return {
    from: from + (bangEscaped ? 1 : 0),
    to,
    raw: bangEscaped ? raw.slice(1) : raw,
    embed: !bangEscaped && bang !== '',
    target,
    heading,
    alias,
    size: sizeOf(alias),
    isSamePage: target === '',
    isAttachment: slash > 0,
    pageName: slash > 0 ? target.slice(0, slash) : target,
    fileName: slash > 0 ? target.slice(slash + 1) : target,
  };
}

/** Parses one token such as `[[Page|Alias]]`; null when it is not a wikilink. */
export function parseWikiLink(token) {
  const m = /^(!?)\[\[([^[\]\n]+?)\]\]$/.exec(token ?? '');
  return m ? build(m[1], m[2], 0, token.length, token, false) : null;
}

function oddBackslashesBefore(s, pos) {
  let n = 0;
  while (pos - n - 1 >= 0 && s[pos - n - 1] === '\\') n++;
  return n % 2 === 1;
}

/** Every wikilink in `text` (document order), skipping backslash-escaped ones. */
export function findWikiLinks(text) {
  const out = [];
  if (!text) return out;
  for (const m of text.matchAll(WIKILINK_TOKEN)) {
    const escaped = oddBackslashesBefore(text, m.index);
    if (escaped && m[1] === '') continue;
    const ref = build(m[1], m[2], m.index, m.index + m[0].length, m[0], escaped);
    if (ref) out.push(ref);
  }
  return out;
}

/** Alias, else heading-only text, else "Target > Heading", else the target. */
export function wikiLinkDisplayText(ref) {
  if (ref.alias != null) return ref.alias;
  if (ref.heading == null) return ref.target;
  return ref.target === '' ? ref.heading : `${ref.target} > ${ref.heading}`;
}

/** View href: '#slug' for a same-page link, else the (resolved) page name plus an optional '#slug'. */
export function wikiLinkHref(ref, resolvedName) {
  const slug = ref.heading ? slugify(ref.heading) : '';
  if (ref.isSamePage) return `#${slug}`;
  return encodeURIComponent(resolvedName || ref.target) + (slug ? `#${slug}` : '');
}

/**
 * The wikilinks of a remark text node, with the markdown source consulted so that tokens produced by
 * backslash escapes (`\[[x]]`) are dropped; `\![[x]]` becomes a plain link starting at `[[`.
 * Without source/position information every token counts.
 */
export function wikiLinksInTextNode(node, source) {
  const refs = findWikiLinks(node.value);
  const start = node.position?.start?.offset;
  const end = node.position?.end?.offset;
  if (!refs.length || typeof source !== 'string' || start == null || end == null) return refs;
  const slice = source.slice(start, end).replace(/\\\|/g, '|');
  let cursor = 0;
  const out = [];
  for (const ref of refs) {
    const idx = slice.indexOf(ref.raw, cursor);
    if (idx < 0) { out.push(ref); continue; }
    cursor = idx + ref.raw.length;
    if (!oddBackslashesBefore(slice, idx)) { out.push(ref); continue; }
    if (ref.embed) out.push({ ...ref, embed: false, from: ref.from + 1, raw: ref.raw.slice(1) });
  }
  return out;
}

/** Distinct page targets (case-sensitive, sorted) of non-code wikilinks and page embeds in `md`. */
export function collectNativeWikiLinkTargets(md) {
  const out = new Set();
  if (!md || !md.includes('[[')) return [];
  visit(parser.parse(md), 'text', (node, _i, parent) => {
    if (parent && (parent.type === 'link' || parent.type === 'linkReference')) return;
    for (const ref of wikiLinksInTextNode(node, md)) {
      if (!ref.isSamePage && !ref.isAttachment && !ref.target.includes(',')) out.add(ref.target);
    }
  });
  return [...out].sort();
}
