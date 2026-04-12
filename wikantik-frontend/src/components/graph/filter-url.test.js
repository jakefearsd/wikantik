import { describe, it, expect } from 'vitest';
import { filterStateToParams, paramsToFilterState } from './filter-url.js';
import { INITIAL_FILTER_STATE, applyPreset, setTags, toggleCluster, PRESETS } from './filter-state.js';

describe('filter-url', () => {
  it('round-trips backbone preset with +1 hop', () => {
    let s = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    s = { ...s, includeHubNeighbors: true };
    const params = filterStateToParams(s);
    expect(params.get('preset')).toBe('backbone');
    expect(params.get('hop')).toBe('1');
    const restored = paramsToFilterState(params);
    expect(restored.preset).toBe(PRESETS.BACKBONE);
    expect(restored.includeHubNeighbors).toBe(true);
  });

  it('round-trips communities with multiple clusters', () => {
    let s = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    s = toggleCluster(s, 'math');
    s = toggleCluster(s, 'ops');
    const params = filterStateToParams(s);
    expect(params.get('cluster')).toBe('math,ops');
    const restored = paramsToFilterState(params);
    expect(restored.clusters.has('math')).toBe(true);
    expect(restored.clusters.has('ops')).toBe(true);
  });

  it('round-trips tags and search', () => {
    let s = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    s = setTags(s, ['optimization', 'simplex']);
    s = { ...s, searchText: 'warehouse' };
    const params = filterStateToParams(s);
    expect(params.get('tags')).toBe('optimization,simplex');
    expect(params.get('search')).toBe('warehouse');
    const restored = paramsToFilterState(params);
    expect(restored.tags.has('optimization')).toBe(true);
    expect(restored.searchText).toBe('warehouse');
  });

  it('preserves unrelated params (focus)', () => {
    const input = new URLSearchParams();
    input.set('focus', 'LinearAlgebra');
    input.set('preset', 'backbone');
    const restored = paramsToFilterState(input);
    const params = filterStateToParams(restored, input);
    expect(params.get('focus')).toBe('LinearAlgebra');
    expect(params.get('preset')).toBe('backbone');
  });

  it('returns initial-equivalent state for empty params', () => {
    const restored = paramsToFilterState(new URLSearchParams());
    expect(restored.preset).toBe(PRESETS.FULL);
    expect(restored.clusters.size).toBe(0);
  });
});
