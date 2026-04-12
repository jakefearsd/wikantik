import { describe, it, expect } from 'vitest';
import {
  INITIAL_FILTER_STATE, PRESETS,
  applyPreset, toggleCluster, setTags, setEdgeTypeHidden,
} from './filter-state.js';

describe('filter-state', () => {
  it('initial state is preset=full with hide mode', () => {
    expect(INITIAL_FILTER_STATE.preset).toBe(PRESETS.FULL);
    expect(INITIAL_FILTER_STATE.visualMode).toBe('hide');
    expect(INITIAL_FILTER_STATE.clusters.size).toBe(0);
    expect(INITIAL_FILTER_STATE.tags.size).toBe(0);
  });

  it('applyPreset(BACKBONE) sets hubsOnly and hide mode', () => {
    const s = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    expect(s.preset).toBe(PRESETS.BACKBONE);
    expect(s.hubsOnly).toBe(true);
    expect(s.includeHubNeighbors).toBe(false);
    expect(s.visualMode).toBe('hide');
  });

  it('applyPreset(TAGS) sets fade mode and clears cluster/hubs', () => {
    const afterBackbone = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const s = applyPreset(afterBackbone, PRESETS.TAGS);
    expect(s.preset).toBe(PRESETS.TAGS);
    expect(s.hubsOnly).toBe(false);
    expect(s.visualMode).toBe('fade');
  });

  it('applyPreset preserves orthogonal controls (edge types, orphan toggle)', () => {
    const base = setEdgeTypeHidden(INITIAL_FILTER_STATE, 'links_to', true);
    const s = applyPreset(base, PRESETS.BACKBONE);
    expect(s.hiddenEdgeTypes.has('links_to')).toBe(true);
  });

  it('toggleCluster adds then removes a cluster', () => {
    let s = toggleCluster(INITIAL_FILTER_STATE, 'mathematics');
    expect(s.clusters.has('mathematics')).toBe(true);
    s = toggleCluster(s, 'mathematics');
    expect(s.clusters.has('mathematics')).toBe(false);
  });

  it('setTags replaces the tags set', () => {
    const s = setTags(INITIAL_FILTER_STATE, ['optimization', 'simplex']);
    expect(s.tags.size).toBe(2);
    expect(s.tags.has('optimization')).toBe(true);
  });

  it('applyPreset(COMMUNITIES) hides when clusters selected, else hide stays but all visible', () => {
    const s = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    expect(s.preset).toBe(PRESETS.COMMUNITIES);
    expect(s.visualMode).toBe('hide');
    expect(s.clusters.size).toBe(0);
  });
});
