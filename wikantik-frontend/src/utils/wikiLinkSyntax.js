// Obsidian-style wikilinks: [[Page]], [[Page|Alias]], [[Page#Heading]], [[#Heading]], ![[Page]],
// ![[Owner/file.png|300]]. Mirrors com.wikantik.api.parser.WikiLinkSyntax (Java) rule for rule.
// findWikiLinks does no code masking: callers pass prose (remark text nodes are never code).
import { visit } from 'unist-util-visit';
import { slugify } from './headings';
import { parseGfm } from './gfmParse';

export const WIKILINK_TOKEN = /(!?)\[\[([^[\]\n]+?)\]\]/g;
export const isImageFileName = /\.(png|jpe?g|gif|svg|webp|bmp|avif)$/i;
const SIZE = /^(\d{1,5})(?:x(\d{1,5}))?$/;

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
 * The wikilinks of a remark text node. Escaping is decided from the markdown SOURCE (the backslash
 * run before each `[[`), because `node.value` is already unescaped (`\\[[x]]` has value `\[[x]]`).
 * `\![[x]]` becomes a plain link starting at `[[`. Source tokens pair with value tokens in order;
 * without position/source info, or when the counts disagree, every value token counts (fail open).
 * Known divergence: emphasis inside a token (`[[a *b* c]]`) splits the text node, so the preview
 * shows it literally while the server renders a link.
 */
export function wikiLinksInTextNode(node, source) {
  const start = node.position?.start?.offset;
  const end = node.position?.end?.offset;
  if (typeof source !== 'string' || start == null || end == null) return findWikiLinks(node.value);
  const slice = source.slice(start, end);
  const valueTokens = [...(node.value || '').matchAll(WIKILINK_TOKEN)];
  const sourceTokens = [...slice.matchAll(WIKILINK_TOKEN)];
  if (valueTokens.length !== sourceTokens.length) return findWikiLinks(node.value);
  const out = [];
  valueTokens.forEach((m, i) => {
    const escaped = oddBackslashesBefore(slice, sourceTokens[i].index);
    if (escaped && m[1] === '') return;
    const ref = build(m[1], m[2], m.index, m.index + m[0].length, m[0], escaped);
    if (ref) out.push(ref);
  });
  return out;
}

/** Distinct page targets (case-sensitive, sorted) of non-code wikilinks and page embeds in `md`. */
export function collectNativeWikiLinkTargets(md) {
  const out = new Set();
  if (!md || !md.includes('[[')) return [];
  visit(parseGfm(md), 'text', (node, _i, parent) => {
    if (parent && (parent.type === 'link' || parent.type === 'linkReference')) return;
    for (const ref of wikiLinksInTextNode(node, md)) {
      if (!ref.isSamePage && !ref.isAttachment && !ref.target.includes(',')) out.add(ref.target);
    }
  });
  return [...out].sort();
}
