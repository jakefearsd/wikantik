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
function slugify(text) {
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
