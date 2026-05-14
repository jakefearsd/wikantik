import { INITIAL_FILTER_STATE, ENDPOINT_CLASSES, applyPreset, PRESETS } from './filter-state.js';

// `tier` is preserved (not managed) — KnowledgeGraphView owns it as a separate
// server-fetch parameter, but routing it through the same params helper keeps
// URL serialization in one place. PageGraphView, which has no tier concept,
// is unaffected because the URL key is only ever read from `existing`.
const PRESERVED_KEYS = ['focus', 'tier'];
const MANAGED_KEYS = ['preset', 'hop', 'cluster', 'unclustered', 'tags', 'type', 'status', 'search', 'endpoints'];

function asCsv(set) {
  return [...set].join(',');
}
function fromCsv(str) {
  return str ? str.split(',').filter(Boolean) : [];
}

export function filterStateToParams(state, existing = new URLSearchParams()) {
  const params = new URLSearchParams();
  for (const key of PRESERVED_KEYS) {
    const v = existing.get(key);
    if (v != null) params.set(key, v);
  }
  if (state.preset !== PRESETS.FULL) params.set('preset', state.preset);
  if (state.includeHubNeighbors) params.set('hop', '1');
  if (state.clusters.size > 0) params.set('cluster', asCsv(state.clusters));
  if (!state.showUnclustered) params.set('unclustered', '0');
  if (state.tags.size > 0) params.set('tags', asCsv(state.tags));
  if (state.types.size > 0) params.set('type', asCsv(state.types));
  if (state.statuses.size > 0) params.set('status', asCsv(state.statuses));
  if (state.searchText) params.set('search', state.searchText);
  if (state.endpointClass && state.endpointClass !== ENDPOINT_CLASSES.ALL) {
    params.set('endpoints', state.endpointClass);
  }
  return params;
}

export function paramsToFilterState(params) {
  const presetParam = params.get('preset');
  const preset = Object.values(PRESETS).includes(presetParam) ? presetParam : PRESETS.FULL;
  let s = applyPreset(INITIAL_FILTER_STATE, preset);
  if (params.get('hop') === '1') s = { ...s, includeHubNeighbors: true };
  const clusters = fromCsv(params.get('cluster'));
  if (clusters.length) s = { ...s, clusters: new Set(clusters) };
  if (params.get('unclustered') === '0') s = { ...s, showUnclustered: false };
  const tags = fromCsv(params.get('tags'));
  if (tags.length) s = { ...s, tags: new Set(tags) };
  const types = fromCsv(params.get('type'));
  if (types.length) s = { ...s, types: new Set(types) };
  const statuses = fromCsv(params.get('status'));
  if (statuses.length) s = { ...s, statuses: new Set(statuses) };
  const search = params.get('search');
  if (search) s = { ...s, searchText: search };
  const endpoints = params.get('endpoints');
  if (endpoints && Object.values(ENDPOINT_CLASSES).includes(endpoints)) {
    s = { ...s, endpointClass: endpoints };
  }
  return s;
}

export { MANAGED_KEYS, PRESERVED_KEYS };
