// Pure helpers for client-side faceting of search results. The search API
// returns up to ~50 results with cluster/author/tags/lastModified already
// attached, so facets are derived and applied entirely in the browser — no
// extra round-trip. Kept side-effect free (no Date.now) so it is trivially
// testable; the caller supplies the `since` cutoff as an epoch-ms number.

export const EMPTY_SELECTION = { clusters: [], authors: [], tags: [], since: null };

/**
 * Build facet groups from a result list. Each group is an array of
 * { value, count } sorted by descending count, then alphabetically.
 */
export function deriveFacets(results) {
  const clusters = new Map();
  const authors = new Map();
  const tags = new Map();

  const bump = (map, key) => {
    if (key == null || key === '') return;
    map.set(key, (map.get(key) || 0) + 1);
  };

  (results || []).forEach(r => {
    bump(clusters, r.cluster);
    bump(authors, r.author);
    const rTags = Array.isArray(r.tags) ? r.tags : r.tags ? [r.tags] : [];
    rTags.forEach(t => bump(tags, t));
  });

  return {
    clusters: toSortedEntries(clusters),
    authors: toSortedEntries(authors),
    tags: toSortedEntries(tags),
  };
}

function toSortedEntries(map) {
  return [...map.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => b.count - a.count || a.value.localeCompare(b.value));
}

/**
 * Filter results by the current selection. Multiple values within one facet
 * are OR'd; different facets are AND'd (standard faceted-search semantics).
 * `since` (epoch ms) keeps results modified at or after the cutoff.
 */
export function applyFacets(results, selection = EMPTY_SELECTION) {
  const { clusters = [], authors = [], tags = [], since = null } = selection || {};
  return (results || []).filter(r => {
    if (clusters.length && !clusters.includes(r.cluster)) return false;
    if (authors.length && !authors.includes(r.author)) return false;
    if (tags.length) {
      const rTags = Array.isArray(r.tags) ? r.tags : r.tags ? [r.tags] : [];
      if (!rTags.some(t => tags.includes(t))) return false;
    }
    if (since != null) {
      if (!r.lastModified) return false;
      if (new Date(r.lastModified).getTime() < since) return false;
    }
    return true;
  });
}

/** True when any facet constraint is active. */
export function hasActiveFacets(selection = EMPTY_SELECTION) {
  const { clusters = [], authors = [], tags = [], since = null } = selection || {};
  return clusters.length > 0 || authors.length > 0 || tags.length > 0 || since != null;
}

/** Toggle a value within a facet group, returning a new selection object. */
export function toggleValue(selection, group, value) {
  const current = selection[group] || [];
  const next = current.includes(value)
    ? current.filter(v => v !== value)
    : [...current, value];
  return { ...selection, [group]: next };
}
