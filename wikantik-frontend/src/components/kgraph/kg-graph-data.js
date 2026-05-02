import { toCytoscapeElements as baseToCy } from '../pagegraph/graph-data.js';

const TYPE_PALETTE = [
  '#6e8efb', '#e35d6a', '#5cb85c', '#f0ad4e',
  '#9b59b6', '#1abc9c', '#34495e', '#e67e22',
  '#16a085', '#c0392b',
];

function hash(str) {
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = ((h << 5) - h) + str.charCodeAt(i);
    h |= 0;
  }
  return Math.abs(h);
}

export function colourForNodeType(nodeType) {
  if (!nodeType) return '#888888';
  return TYPE_PALETTE[hash(nodeType) % TYPE_PALETTE.length];
}

export function toKgCytoscapeElements(snapshot, filter) {
  const base = baseToCy(snapshot, filter);
  const nodeIndex = new Map(snapshot.nodes.map(n => [n.id, n]));

  const nodes = base.nodes.map(el => {
    const src = nodeIndex.get(el.data.id);
    if (!src) return el;
    return {
      ...el,
      data: {
        ...el.data,
        type:           src.type,
        provenance:     src.provenance,
        status:         src.status,
        tier:           src.tier,
        nodeTypeColor:  colourForNodeType(src.type),
      },
    };
  });

  return { nodes, edges: base.edges };
}
