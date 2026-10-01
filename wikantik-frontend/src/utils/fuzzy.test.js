import { describe, it, expect } from 'vitest';
import { fuzzyRank } from './fuzzy';

describe('fuzzyRank', () => {
  it.each([
    ['Fold all headings', 'fold', 0],
    ['Unfold all headings', 'fold', 1],
    ['Insert table', 'intab', 2],
    ['Insert table', 'zzz', -1],
    ['Toggle sidebar', 'TOGGLE side', 0],
    ['Toggle sidebar', 'togsid', 2],
    ['Insert callout: Warning', 'icw', 2],
    ['LowCostIndexFundInvesting', 'lcifi', 2],
    ['HTMLParser', 'hp', 2],
    ['anything', '', 0],
  ])('%s / %s → %i', (text, q, rank) => expect(fuzzyRank(text, q)).toBe(rank));

  it.each([
    ['BackgroundJobProcessing', 'bond'],
    ['BasicsOfCompoundInterest', 'bond'],
    ['Background Job Processing', 'bond'],
    // "itbl": b neither starts a word nor continues the run "t" — a scattered subsequence no longer matches
    ['Insert table', 'itbl'],
  ])('%s does not match %s', (text, q) => expect(fuzzyRank(text, q)).toBe(-1));

  it('backtracks past a word whose run cannot continue', () => {
    // binding "a" to Ax dead-ends; binding it to Ab continues into "b", then Y starts a word
    expect(fuzzyRank('AxQAbQYes', 'aby')).toBe(2);
  });
});
