const KNOWN_PALETTE = {
  links_to:   '#94a3b8',
  related_to: '#2563eb',
  part_of:    '#7c3aed',
};

const FALLBACK_PALETTE = [
  '#06b6d4', '#ec4899', '#14b8a6', '#f97316',
  '#84cc16', '#d946ef', '#6366f1',
];

function stableHash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

function colorFor(relationshipType) {
  if (KNOWN_PALETTE[relationshipType]) return KNOWN_PALETTE[relationshipType];
  return FALLBACK_PALETTE[stableHash(relationshipType) % FALLBACK_PALETTE.length];
}

export function mergeBidirectionalEdges(edges) {
  const seen = new Map();
  const result = [];

  for (const edge of edges) {
    const key = [edge.source, edge.target, edge.relationshipType].sort().join('|') +
                '|' + edge.relationshipType;
    const reverseKey = [edge.target, edge.source, edge.relationshipType].sort().join('|') +
                       '|' + edge.relationshipType;

    if (seen.has(reverseKey) || seen.has(key)) {
      const existingIdx = seen.get(key) ?? seen.get(reverseKey);
      if (existingIdx !== undefined && !result[existingIdx].bidirectional) {
        result[existingIdx].bidirectional = true;
        const [a, b] = [result[existingIdx].source, result[existingIdx].target].sort();
        result[existingIdx].source = a;
        result[existingIdx].target = b;
      }
    } else {
      seen.set(key, result.length);
      result.push({ ...edge });
    }
  }

  return result;
}

export function mergeParallelEdges(edges) {
  const groups = new Map();
  for (const edge of edges) {
    const [a, b] = [edge.source, edge.target].sort();
    const key = `${a}|${b}`;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push(edge);
  }

  const result = [];
  for (const group of groups.values()) {
    const types = [...new Set(group.map(e => e.relationshipType))];
    const bidirectional = group.some(e => e.bidirectional);
    const primary = group[0];
    result.push({
      ...primary,
      bidirectional,
      relationshipTypes: types,
    });
  }
  return result;
}

export function toCytoscapeElements(snapshot) {
  const nodes = snapshot.nodes.map(n => ({
    data: {
      id: n.id,
      name: n.name,
      type: n.type,
      role: n.role,
      provenance: n.provenance,
      sourcePage: n.sourcePage,
      degreeIn: n.degreeIn,
      degreeOut: n.degreeOut,
      restricted: n.restricted,
      label: n.restricted ? '\u{1F512}' : (n.name || ''),
    },
    classes: `role-${n.role}`,
  }));

  const bidiMerged = mergeBidirectionalEdges(snapshot.edges);
  const parallelMerged = mergeParallelEdges(bidiMerged);

  const edges = parallelMerged.map(e => ({
    classes: e.relationshipTypes.length > 1 ? 'composite' : '',
    data: {
      id: e.relationshipTypes.length > 1
        ? e.relationshipTypes.join('-') + '-' + e.source + '-' + e.target
        : e.id + (e.bidirectional ? '-bidi' : ''),
      source: e.source,
      target: e.target,
      relationshipType: e.relationshipTypes[0],
      relationshipTypes: e.relationshipTypes,
      edgeLabel: e.relationshipTypes.join(' \u00B7 '),
      provenance: e.provenance,
      edgeColor: colorFor(e.relationshipTypes[0]),
      bidirectional: e.bidirectional || false,
      compositeWidth: e.relationshipTypes.length > 1 ? 2 : 1,
    },
  }));

  return { nodes, edges };
}
