import { describe, it, expect } from 'vitest';
import {
  deriveFacets,
  applyFacets,
  hasActiveFacets,
  toggleValue,
  EMPTY_SELECTION,
} from './searchFacets';

const RESULTS = [
  { name: 'A', cluster: 'Security', author: 'alice', tags: ['acl', 'auth'], lastModified: '2026-05-01T00:00:00Z' },
  { name: 'B', cluster: 'Security', author: 'bob', tags: ['acl'], lastModified: '2026-01-01T00:00:00Z' },
  { name: 'C', cluster: 'Operations', author: 'alice', tags: [], lastModified: '2026-05-20T00:00:00Z' },
  { name: 'D', cluster: null, author: null, tags: 'auth', lastModified: null },
];

describe('deriveFacets', () => {
  it('counts cluster/author/tag occurrences, ignoring null/empty', () => {
    const f = deriveFacets(RESULTS);
    expect(f.clusters).toEqual([
      { value: 'Security', count: 2 },
      { value: 'Operations', count: 1 },
    ]);
    expect(f.authors).toEqual([
      { value: 'alice', count: 2 },
      { value: 'bob', count: 1 },
    ]);
    // acl appears twice, auth twice → tie broken alphabetically.
    expect(f.tags).toEqual([
      { value: 'acl', count: 2 },
      { value: 'auth', count: 2 },
    ]);
  });

  it('handles empty / nullish input', () => {
    expect(deriveFacets([])).toEqual({ clusters: [], authors: [], tags: [] });
    expect(deriveFacets(null)).toEqual({ clusters: [], authors: [], tags: [] });
  });
});

describe('applyFacets', () => {
  it('returns all results for an empty selection', () => {
    expect(applyFacets(RESULTS, EMPTY_SELECTION)).toHaveLength(4);
  });

  it('ORs values within one facet', () => {
    const out = applyFacets(RESULTS, { ...EMPTY_SELECTION, clusters: ['Security', 'Operations'] });
    expect(out.map(r => r.name)).toEqual(['A', 'B', 'C']);
  });

  it('ANDs across facets', () => {
    const out = applyFacets(RESULTS, { ...EMPTY_SELECTION, clusters: ['Security'], authors: ['alice'] });
    expect(out.map(r => r.name)).toEqual(['A']);
  });

  it('matches a result when ANY of its tags is selected, including scalar tags', () => {
    const out = applyFacets(RESULTS, { ...EMPTY_SELECTION, tags: ['auth'] });
    expect(out.map(r => r.name)).toEqual(['A', 'D']);
  });

  it('filters by since cutoff and drops results with no lastModified', () => {
    const cutoff = new Date('2026-04-01T00:00:00Z').getTime();
    const out = applyFacets(RESULTS, { ...EMPTY_SELECTION, since: cutoff });
    expect(out.map(r => r.name)).toEqual(['A', 'C']);
  });
});

describe('hasActiveFacets', () => {
  it('is false for the empty selection and true once anything is set', () => {
    expect(hasActiveFacets(EMPTY_SELECTION)).toBe(false);
    expect(hasActiveFacets({ ...EMPTY_SELECTION, tags: ['acl'] })).toBe(true);
    expect(hasActiveFacets({ ...EMPTY_SELECTION, since: 1 })).toBe(true);
  });
});

describe('toggleValue', () => {
  it('adds then removes a value immutably', () => {
    const added = toggleValue(EMPTY_SELECTION, 'clusters', 'Security');
    expect(added.clusters).toEqual(['Security']);
    expect(EMPTY_SELECTION.clusters).toEqual([]); // unchanged
    const removed = toggleValue(added, 'clusters', 'Security');
    expect(removed.clusters).toEqual([]);
  });
});
