const capitalize = (word) => word.charAt(0).toUpperCase() + word.slice(1);

const segmentLabel = (segment) => segment.split(/[-_\s]+/).filter(Boolean).map(capitalize).join(' ');

const oneLabel = (slug) => String(slug ?? '').split('/').map(segmentLabel).filter(Boolean).join(' › ');

/**
 * Human label for a cluster slug: `index-fund-investing` → "Index Fund Investing",
 * `parent/child` → "Parent › Child". A multi-membership list labels each entry. Blank input → ''.
 */
export function clusterLabel(cluster) {
  if (Array.isArray(cluster)) return cluster.map(oneLabel).filter(Boolean).join(', ');
  return oneLabel(cluster);
}
