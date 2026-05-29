import { describe, it, expect } from 'vitest';
import { draftKey, draftPrefix, parseDraftKey } from './draftKeys';

describe('draftKeys', () => {
  it('builds a namespaced key', () => {
    expect(draftKey('alice', 'Foo/Bar')).toBe('wikantik.draft.alice.Foo/Bar');
  });
  it('builds a per-login prefix', () => {
    expect(draftPrefix('alice')).toBe('wikantik.draft.alice.');
  });
  it('round-trips the page id out of a key', () => {
    const k = draftKey('alice', 'Foo/Bar');
    expect(parseDraftKey('alice', k)).toBe('Foo/Bar');
  });
  it('returns null for a key belonging to another login', () => {
    expect(parseDraftKey('bob', 'wikantik.draft.alice.Foo')).toBeNull();
  });
});
