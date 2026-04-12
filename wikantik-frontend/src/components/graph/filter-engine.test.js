import { describe, it, expect } from 'vitest';
import { applyFilters } from './filter-engine.js';
import { INITIAL_FILTER_STATE, applyPreset, PRESETS, toggleCluster, setTags } from './filter-state.js';

function node(id, extra = {}) {
  return {
    id, name: `n${id}`, type: 'article', role: 'normal',
    provenance: 'HUMAN_AUTHORED', sourcePage: `n${id}.md`,
    degreeIn: 0, degreeOut: 0, restricted: false,
    cluster: null, tags: [], status: null, ...extra,
  };
}
function edge(id, source, target, rel = 'links_to') {
  return { id, source, target, relationshipType: rel, provenance: 'HUMAN_AUTHORED' };
}
function snap(nodes, edges = []) {
  return { nodes, edges, hubDegreeThreshold: 10 };
}

describe('applyFilters', () => {
  it('preset=full returns every node as visible, no fades', () => {
    const s = snap([node('a'), node('b')], [edge('e1', 'a', 'b')]);
    const r = applyFilters(s, INITIAL_FILTER_STATE);
    expect(r.visibleNodeIds).toEqual(new Set(['a', 'b']));
    expect(r.fadedNodeIds.size).toBe(0);
    expect(r.visibleEdgeIds).toEqual(new Set(['e1']));
  });

  it('preset=backbone keeps type:hub and role:hub, drops normals', () => {
    const s = snap([
      node('h1', { type: 'hub' }),
      node('h2', { role: 'hub' }),
      node('n1'),
    ]);
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('h1')).toBe(true);
    expect(r.visibleNodeIds.has('h2')).toBe(true);
    expect(r.visibleNodeIds.has('n1')).toBe(false);
  });

  it('backbone +1 hop includes direct neighbors of hubs', () => {
    const s = snap(
      [node('h', { role: 'hub' }), node('n1'), node('n2')],
      [edge('e1', 'h', 'n1')]
    );
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    state = { ...state, includeHubNeighbors: true };
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('h')).toBe(true);
    expect(r.visibleNodeIds.has('n1')).toBe(true);
    expect(r.visibleNodeIds.has('n2')).toBe(false);
  });

  it('edge hidden when either endpoint is not visible', () => {
    const s = snap(
      [node('h', { role: 'hub' }), node('n1')],
      [edge('e1', 'h', 'n1')]
    );
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const r = applyFilters(s, state);
    expect(r.visibleEdgeIds.has('e1')).toBe(false);
  });

  it('communities: no selection = all visible, color map populated by cluster', () => {
    const s = snap([
      node('a', { cluster: 'math' }),
      node('b', { cluster: 'ops' }),
      node('c'),
    ]);
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.size).toBe(3);
    expect(r.nodeColor.get('a')).toBeTruthy();
    expect(r.nodeColor.get('b')).toBeTruthy();
    expect(r.nodeColor.get('a')).not.toBe(r.nodeColor.get('b'));
  });

  it('communities: cluster isolation hides other clusters', () => {
    const s = snap([
      node('a', { cluster: 'math' }),
      node('b', { cluster: 'ops' }),
      node('c'),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    state = toggleCluster(state, 'math');
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('a')).toBe(true);
    expect(r.visibleNodeIds.has('b')).toBe(false);
    expect(r.visibleNodeIds.has('c')).toBe(false);
  });

  it('communities: showUnclustered toggle includes null-cluster nodes when isolating', () => {
    const s = snap([
      node('a', { cluster: 'math' }),
      node('c'),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    state = toggleCluster(state, 'math');
    state = { ...state, showUnclustered: true };
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('c')).toBe(true);

    const state2 = { ...state, showUnclustered: false };
    const r2 = applyFilters(s, state2);
    expect(r2.visibleNodeIds.has('c')).toBe(false);
  });

  it('tags: OR within tag facet, fade non-matching', () => {
    const s = snap([
      node('a', { tags: ['optimization'] }),
      node('b', { tags: ['vectors'] }),
      node('c', { tags: ['optimization', 'simplex'] }),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = setTags(state, ['optimization']);
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.size).toBe(3); // fade mode — all still visible
    expect(r.fadedNodeIds.has('b')).toBe(true);
    expect(r.fadedNodeIds.has('a')).toBe(false);
  });

  it('tags: AND across facets (tags AND type)', () => {
    const s = snap([
      node('a', { tags: ['opt'], type: 'hub' }),
      node('b', { tags: ['opt'], type: 'article' }),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = setTags(state, ['opt']);
    state = { ...state, types: new Set(['hub']) };
    const r = applyFilters(s, state);
    expect(r.fadedNodeIds.has('a')).toBe(false);
    expect(r.fadedNodeIds.has('b')).toBe(true);
  });

  it('tags: search text fades non-matching names (case-insensitive substring)', () => {
    const s = snap([node('a', { name: 'Warehouse' }), node('b', { name: 'Logistics' })]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = { ...state, searchText: 'ware' };
    const r = applyFilters(s, state);
    expect(r.fadedNodeIds.has('a')).toBe(false);
    expect(r.fadedNodeIds.has('b')).toBe(true);
  });

  it('edges fade when either endpoint is faded (tags preset)', () => {
    const s = snap(
      [node('a', { tags: ['x'] }), node('b', { tags: ['y'] })],
      [edge('e1', 'a', 'b')]
    );
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = setTags(state, ['x']);
    const r = applyFilters(s, state);
    expect(r.fadedEdgeIds.has('e1')).toBe(true);
  });

  it('hidden edge types remove edges even when endpoints visible', () => {
    const s = snap(
      [node('a'), node('b')],
      [edge('e1', 'a', 'b', 'links_to')]
    );
    const state = { ...INITIAL_FILTER_STATE, hiddenEdgeTypes: new Set(['links_to']) };
    const r = applyFilters(s, state);
    expect(r.visibleEdgeIds.has('e1')).toBe(false);
  });

  it('orphan/stub toggle hides role=orphan and role=stub when off', () => {
    const s = snap([
      node('n'),
      node('o', { role: 'orphan' }),
      node('st', { role: 'stub' }),
    ]);
    const state = { ...INITIAL_FILTER_STATE, showOrphansStubs: false };
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('n')).toBe(true);
    expect(r.visibleNodeIds.has('o')).toBe(false);
    expect(r.visibleNodeIds.has('st')).toBe(false);
  });

  it('focusNodeId overrides filter exclusion (pin)', () => {
    const s = snap([node('n1'), node('n2')]);
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const r = applyFilters(s, state, 'n1');
    expect(r.visibleNodeIds.has('n1')).toBe(true);
  });

  it('handles missing frontmatter fields without crashing', () => {
    const s = snap([{ id: 'x', name: 'X', restricted: false }]);
    const r = applyFilters(s, INITIAL_FILTER_STATE);
    expect(r.visibleNodeIds.has('x')).toBe(true);
  });

  it('returns empty sets on empty input', () => {
    const r = applyFilters({ nodes: [], edges: [] }, INITIAL_FILTER_STATE);
    expect(r.visibleNodeIds.size).toBe(0);
    expect(r.visibleEdgeIds.size).toBe(0);
  });
});
