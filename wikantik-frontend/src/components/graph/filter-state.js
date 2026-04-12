export const PRESETS = Object.freeze({
  FULL: 'full',
  BACKBONE: 'backbone',
  COMMUNITIES: 'communities',
  TAGS: 'tags',
});

export const INITIAL_FILTER_STATE = Object.freeze({
  preset: PRESETS.FULL,
  hubsOnly: false,
  includeHubNeighbors: false,
  clusters: new Set(),
  showUnclustered: true,
  tags: new Set(),
  types: new Set(),
  statuses: new Set(),
  searchText: '',
  hiddenEdgeTypes: new Set(),
  showOrphansStubs: true,
  visualMode: 'hide',
});

function clonePresetSlots(state) {
  return {
    hubsOnly: false,
    includeHubNeighbors: false,
    clusters: new Set(),
    showUnclustered: true,
    tags: new Set(),
    types: new Set(),
    statuses: new Set(),
    searchText: '',
    hiddenEdgeTypes: new Set(state.hiddenEdgeTypes),
    showOrphansStubs: state.showOrphansStubs,
  };
}

export function applyPreset(state, preset) {
  const base = { ...state, ...clonePresetSlots(state), preset };
  switch (preset) {
    case PRESETS.BACKBONE:
      return { ...base, hubsOnly: true, visualMode: 'hide' };
    case PRESETS.COMMUNITIES:
      return { ...base, visualMode: 'hide' };
    case PRESETS.TAGS:
      return { ...base, visualMode: 'fade' };
    case PRESETS.FULL:
    default:
      return { ...base, visualMode: 'hide' };
  }
}

export function toggleCluster(state, cluster) {
  const next = new Set(state.clusters);
  if (next.has(cluster)) next.delete(cluster);
  else next.add(cluster);
  return { ...state, clusters: next };
}

export function setClusters(state, clusters) {
  return { ...state, clusters: new Set(clusters) };
}

export function setTags(state, tags) {
  return { ...state, tags: new Set(tags) };
}

export function setTypes(state, types) {
  return { ...state, types: new Set(types) };
}

export function setStatuses(state, statuses) {
  return { ...state, statuses: new Set(statuses) };
}

export function setSearchText(state, text) {
  return { ...state, searchText: text };
}

export function setIncludeHubNeighbors(state, include) {
  return { ...state, includeHubNeighbors: include };
}

export function setShowUnclustered(state, show) {
  return { ...state, showUnclustered: show };
}

export function setShowOrphansStubs(state, show) {
  return { ...state, showOrphansStubs: show };
}

export function setEdgeTypeHidden(state, type, hidden) {
  const next = new Set(state.hiddenEdgeTypes);
  if (hidden) next.add(type);
  else next.delete(type);
  return { ...state, hiddenEdgeTypes: next };
}
