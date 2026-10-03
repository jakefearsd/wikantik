import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import remarkInlineMath from './remarkInlineMath';
import { toString } from 'mdast-util-to-string';
import { visit } from 'unist-util-visit';

/**
 * Extracts h2/h3 headings from an HTML string.
 * Returns an array of { id, text, level } objects.
 *
 * Ids are lowercase slugs (spaces → hyphens, non-alphanumeric stripped).
 * Duplicate headings get -2, -3, … suffixes to ensure uniqueness.
 */
export function extractHeadings(html) {
  if (!html) return [];

  // Parse the HTML in the test/browser environment using DOMParser
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');

  const nodes = doc.body.querySelectorAll('h2, h3');
  const seenIds = {};
  const headings = [];

  nodes.forEach((node) => {
    const level = parseInt(node.tagName[1], 10);
    const text = node.textContent.trim();
    const baseId = slugify(text);
    const id = uniqueId(baseId, seenIds);
    seenIds[baseId] = (seenIds[baseId] || 0) + 1;
    headings.push({ id, text, level });
  });

  return headings;
}

/** Slugify: lowercase, spaces→hyphens, strip non-alphanumeric (keeping hyphens). */
export function slugify(text) {
  return text
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
}

/** Return a unique id for baseId, suffixing -2, -3, … on collision. */
function uniqueId(baseId, seen) {
  const count = seen[baseId] || 0;
  if (count === 0) return baseId;
  return `${baseId}-${count + 1}`;
}

// Same syntax extensions as the editor preview, so heading text is extracted the way it renders.
const markdownParser = unified().use(remarkParse).use(remarkGfm, { singleTilde: false }).use(remarkMath, { singleDollarTextMath: false }).use(remarkInlineMath);

/**
 * Headings of a markdown body in document order: { level, text, line, id }.
 * `id` is the anchor the page view assigns (h2/h3 only — see extractHeadings — same slugify and
 * duplicate numbering); null for other levels. `line` is the 1-based source line.
 */
export function headingsFromMarkdown(md) {
  if (!md) return [];
  const tree = markdownParser.parse(md);
  const seen = {};
  const out = [];
  visit(tree, 'heading', (node) => {
    const text = toString(node).trim();
    let id = null;
    if (node.depth === 2 || node.depth === 3) {
      const base = slugify(text);
      id = uniqueId(base, seen);
      seen[base] = (seen[base] || 0) + 1;
    }
    out.push({ level: node.depth, text, line: node.position.start.line, id });
  });
  return out;
}
