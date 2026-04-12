import { PRESETS } from './filter-state.js';

const CLUSTER_PALETTE = [
  '#2563eb', '#dc2626', '#059669', '#d97706', '#7c3aed',
  '#db2777', '#0891b2', '#65a30d', '#ea580c', '#4f46e5',
];

function stableHash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

function colorForCluster(cluster) {
  if (!cluster) return '#94a3b8';
  return CLUSTER_PALETTE[stableHash(cluster) % CLUSTER_PALETTE.length];
}

function isHub(node) {
  return node.type === 'hub' || node.role === 'hub';
}

function nodeTags(node) {
  return Array.isArray(node.tags) ? node.tags : [];
}

function matchesTagsFacet(node, state) {
  if (state.tags.size === 0) return true;
  const t = nodeTags(node);
  for (const tag of t) if (state.tags.has(tag)) return true;
  return false;
}

function matchesTypeFacet(node, state) {
  if (state.types.size === 0) return true;
  return state.types.has(node.type);
}

function matchesStatusFacet(node, state) {
  if (state.statuses.size === 0) return true;
  return node.status && state.statuses.has(node.status);
}

function matchesSearch(node, state) {
  if (!state.searchText) return true;
  const name = (node.name || '').toLowerCase();
  return name.includes(state.searchText.toLowerCase());
}

function matchesAllTagFacets(node, state) {
  return matchesTagsFacet(node, state)
      && matchesTypeFacet(node, state)
      && matchesStatusFacet(node, state)
      && matchesSearch(node, state);
}

function computeBackboneVisible(snapshot, state) {
  const visible = new Set();
  for (const n of snapshot.nodes) if (isHub(n)) visible.add(n.id);
  if (state.includeHubNeighbors) {
    for (const e of snapshot.edges) {
      if (visible.has(e.source)) visible.add(e.target);
      if (visible.has(e.target)) visible.add(e.source);
    }
  }
  return visible;
}

function computeCommunitiesVisible(snapshot, state) {
  if (state.clusters.size === 0) {
    return new Set(snapshot.nodes.map(n => n.id));
  }
  const visible = new Set();
  for (const n of snapshot.nodes) {
    if (n.cluster && state.clusters.has(n.cluster)) visible.add(n.id);
    else if (!n.cluster && state.showUnclustered) visible.add(n.id);
  }
  return visible;
}

export function applyFilters(snapshot, state, focusNodeId = null) {
  const allIds = new Set(snapshot.nodes.map(n => n.id));
  let visibleNodeIds;
  const fadedNodeIds = new Set();
  const nodeColor = new Map();

  // 1) Preset-level visibility
  switch (state.preset) {
    case PRESETS.BACKBONE:
      visibleNodeIds = computeBackboneVisible(snapshot, state);
      break;
    case PRESETS.COMMUNITIES:
      visibleNodeIds = computeCommunitiesVisible(snapshot, state);
      break;
    case PRESETS.TAGS:
    case PRESETS.FULL:
    default:
      visibleNodeIds = new Set(allIds);
      break;
  }

  // 2) Orthogonal filter: orphan/stub toggle
  if (!state.showOrphansStubs) {
    for (const n of snapshot.nodes) {
      if (n.role === 'orphan' || n.role === 'stub') visibleNodeIds.delete(n.id);
    }
  }

  // 3) Focus pin — always include focus node
  if (focusNodeId && allIds.has(focusNodeId)) {
    visibleNodeIds.add(focusNodeId);
  }

  // 4) Fade pass — only in fade mode (Tags preset)
  if (state.visualMode === 'fade') {
    for (const n of snapshot.nodes) {
      if (!visibleNodeIds.has(n.id)) continue;
      if (!matchesAllTagFacets(n, state)) fadedNodeIds.add(n.id);
    }
  }

  // 5) Cluster coloring — always populate when communities preset active or clusters selected
  if (state.preset === PRESETS.COMMUNITIES || state.clusters.size > 0) {
    for (const n of snapshot.nodes) {
      if (visibleNodeIds.has(n.id)) nodeColor.set(n.id, colorForCluster(n.cluster));
    }
  }

  // 6) Edges
  const hiddenEdgeTypes = state.hiddenEdgeTypes;
  const visibleEdgeIds = new Set();
  const fadedEdgeIds = new Set();
  for (const e of snapshot.edges) {
    if (hiddenEdgeTypes.has(e.relationshipType)) continue;
    const srcVis = visibleNodeIds.has(e.source);
    const tgtVis = visibleNodeIds.has(e.target);
    if (!srcVis || !tgtVis) continue;
    visibleEdgeIds.add(e.id);
    if (fadedNodeIds.has(e.source) || fadedNodeIds.has(e.target)) fadedEdgeIds.add(e.id);
  }

  return { visibleNodeIds, fadedNodeIds, visibleEdgeIds, fadedEdgeIds, nodeColor };
}

export { colorForCluster };
