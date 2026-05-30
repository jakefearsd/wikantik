/**
 * Pure helper for graph accessibility summary text.
 * elements shape: { nodes: [{data: {cluster, ...}}], edges: [...] }
 */
export function graphSummary(elements) {
  const nodes = elements?.nodes ?? [];
  const edges = elements?.edges ?? [];
  const nodeCount = nodes.length;
  const edgeCount = edges.length;
  const clusterSet = new Set();
  for (const n of nodes) {
    if (n.data?.cluster) clusterSet.add(n.data.cluster);
  }
  const clusterCount = clusterSet.size;
  return `Page graph: ${nodeCount} ${nodeCount === 1 ? 'page' : 'pages'}, `
    + `${edgeCount} ${edgeCount === 1 ? 'link' : 'links'}`
    + (clusterCount > 0 ? ` across ${clusterCount} ${clusterCount === 1 ? 'cluster' : 'clusters'}` : '')
    + '.';
}
