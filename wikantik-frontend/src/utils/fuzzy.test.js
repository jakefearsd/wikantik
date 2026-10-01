import { describe, it, expect } from 'vitest';
import { fuzzyRank } from './fuzzy';

describe('fuzzyRank', () => {
  it.each([
    ['Fold all headings', 'fold', 0],
    ['Unfold all headings', 'fold', 1],
    ['Insert table', 'itbl', 2],
    ['Insert table', 'zzz', -1],
    ['Toggle sidebar', 'TOGGLE side', 0],
    ['anything', '', 0],
  ])('%s / %s → %i', (text, q, rank) => expect(fuzzyRank(text, q)).toBe(rank));
});
