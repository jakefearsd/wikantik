// CodeMirror 6 completion source for internal links. Triggers:
//   [[frag           → live page search; inserts [Name](Name)
//   [[Page#frag      → the target page's h2/h3 headings; inserts [Heading](Page#anchor)
//   ](frag           → pages + this page's attachments; replaces only the link target
//   ](Page#frag      → headings of Page; ](#frag → headings of the page being edited
// Anchors come from headingsFromMarkdown (the page view's ids). Results are ranked server-side, so
// CodeMirror's own filtering is disabled (filter: false keeps our order).
import { titleToSlug, isValidSlug } from './slugUtils';

const MAX_OPTIONS = 20;
const SEARCH_DEBOUNCE_MS = 150;
const WIKI_TRIGGER = /\[\[([^\]\n#]*)(?:#([^\]\n]*))?$/;
const TARGET_TRIGGER = /\]\(([^)\s#]*)(?:#([^)\s]*))?$/;
const HAS_SCHEME_OR_ROOT = /^(?:[a-z][a-z0-9+.-]*:|\/)/i;

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

async function safely(label, promiseFn) {
  try {
    return await promiseFn();
  } catch (err) {
    console.warn(`[link-complete] ${label} failed`, err?.message || err);
    return null;
  }
}

function newPageOption(fragment, names, applyFor) {
  const text = (fragment || '').trim();
  if (!text) return null;
  const slug = titleToSlug(text);
  if (!isValidSlug(slug)) return null;
  const taken = new Set(names.map((n) => n.toLowerCase()));
  if (taken.has(slug.toLowerCase()) || taken.has(text.toLowerCase())) return null;
  return { label: `Link to new page: ${slug}`, type: 'newpage', apply: applyFor(slug, text) };
}

/**
 * @param {object} deps
 * @param {(q: string) => Promise<string[]>} deps.searchPages  ranked, ACL-filtered page names
 * @param {(page: string|null) => Promise<Array<{text: string, id: string|null}>>} deps.getHeadings
 *        headings of `page`, or of the page being edited when null
 * @param {() => string[]} [deps.getAttachmentNames]  attachment file names of the page being edited
 */
export function createWikiLinkSource({ searchPages, getHeadings, getAttachmentNames = () => [] }) {
  async function headingOptions(page, fragment, applyFor) {
    const headings = await safely('heading lookup', () => getHeadings(page || null));
    if (!headings) return null;
    const frag = fragment.toLowerCase();
    return headings
      .filter((h) => h.id && h.text.toLowerCase().includes(frag))
      .slice(0, MAX_OPTIONS)
      .map((h) => ({ label: h.text, detail: `#${h.id}`, type: 'heading', apply: applyFor(h) }));
  }

  async function pageNames(fragment, context) {
    await sleep(SEARCH_DEBOUNCE_MS);
    if (context.aborted) return null;
    const names = await safely('page search', () => searchPages(fragment));
    return context.aborted ? null : names;
  }

  const result = (from, options) => (options && options.length ? { from, options, filter: false } : null);

  async function completeWiki(match, context) {
    const [, page, heading] = WIKI_TRIGGER.exec(match.text);
    if (heading !== undefined) {
      const options = await headingOptions(page, heading, (h) => `[${h.text}](${page}#${h.id})`);
      return context.aborted ? null : result(match.from, options);
    }
    const names = await pageNames(page, context);
    if (!names) return null;
    const options = names.slice(0, MAX_OPTIONS).map((name) => ({ label: name, type: 'wikilink', apply: `[${name}](${name})` }));
    const created = newPageOption(page, names, (slug, text) => `[${text}](${slug})`);
    return result(match.from, created ? [...options, created] : options);
  }

  async function completeTarget(match, context) {
    const [, target, heading] = TARGET_TRIGGER.exec(match.text);
    if (HAS_SCHEME_OR_ROOT.test(target)) return null;
    const from = match.from + 2; // replace the link target only, never the "]("
    if (heading !== undefined) {
      const options = await headingOptions(target, heading, (h) => (target ? `${target}#${h.id}` : h.id));
      return context.aborted ? null : result(target ? from : from + 1, options);
    }
    const names = await pageNames(target, context);
    if (!names) return null;
    const frag = target.toLowerCase();
    const pages = names.slice(0, MAX_OPTIONS).map((name) => ({ label: name, type: 'wikilink', apply: name }));
    const attachments = getAttachmentNames()
      .filter((n) => n.toLowerCase().includes(frag))
      .map((n) => ({ label: n, type: 'attachment', apply: n }));
    const created = newPageOption(target, names, (slug) => slug);
    return result(from, [...pages, ...attachments, ...(created ? [created] : [])]);
  }

  return async (context) => {
    const wiki = context.matchBefore(WIKI_TRIGGER);
    if (wiki) return completeWiki(wiki, context);
    const target = context.matchBefore(TARGET_TRIGGER);
    if (target) return completeTarget(target, context);
    return null;
  };
}
