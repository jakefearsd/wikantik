import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import { visit } from 'unist-util-visit';
import { citeParts } from './remarkWikiMarkup';

const SCHEME = /^[a-z][a-z0-9+.-]*:/i;
const parser = unified().use(remarkParse).use(remarkGfm);

/** The wiki page a link URL points at, or null for external/anchor/cite/attachment links. */
export function wikiLinkTarget(url) {
  if (!url || url.startsWith('#') || url.startsWith('/') || SCHEME.test(url)) return null;
  let page = url.split('#')[0];
  try { page = decodeURIComponent(page); } catch (err) { console.warn('[link-targets] undecodable link target', url, err?.message); }
  // A '.' or '/' means an attachment reference (same rule as remarkAttachments).
  if (!page || page.includes('/') || page.includes('.')) return null;
  return page;
}

/** The page a cite:// link grounds in, or null. */
export function citeTarget(url) {
  return url && url.startsWith('cite://') ? (citeParts(url).target || null) : null;
}

/**
 * Distinct page names linked from `md` (wiki links and cite:// targets), sorted. Names containing a
 * comma are skipped: they cannot be sent through the comma-separated `names=` check.
 */
export function collectWikiLinkTargets(md) {
  const out = new Set();
  visit(parser.parse(md || ''), 'link', (node) => {
    const t = wikiLinkTarget(node.url) || citeTarget(node.url);
    if (t && !t.includes(',')) out.add(t);
  });
  return [...out].sort();
}

/** remark plugin: give links to missing pages the server's create-link class. `missing` holds lowercased names. */
export function remarkMissingLinks({ missing } = {}) {
  return (tree) => {
    if (!missing || missing.size === 0) return;
    visit(tree, 'link', (node) => {
      const target = wikiLinkTarget(node.url) || citeTarget(node.url);
      if (!target || !missing.has(target.toLowerCase())) return;
      node.data = node.data || {};
      node.data.hProperties = {
        ...(node.data.hProperties || {}),
        className: ['createpage'],
        'data-missing-page': target,
        title: `${target} does not exist yet`,
      };
    });
  };
}
