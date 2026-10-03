import { describe, it, expect } from 'vitest';
import { findInlineMath, matchInlineMath } from './inlineMath';
import { CASES } from './inlineMath.cases';


describe('findInlineMath', () => {
  it.each(CASES)('%j', (src, expected) => {
    expect(findInlineMath(src).map((m) => m.tex)).toEqual(expected);
  });
  it('reports exclusive ranges', () => {
    expect(findInlineMath('a $x$ b')).toEqual([{ from: 2, to: 5, tex: 'x' }]);
  });
  it('rejects a blank line inside', () => {
    expect(findInlineMath('$a\n\nb$')).toEqual([]);
  });
});

describe('matchInlineMath', () => {
  it('returns -1 for a non-dollar or a lone trailing dollar', () => {
    expect(matchInlineMath('abc', 0)).toBe(-1);
    expect(matchInlineMath('a$', 1)).toBe(-1);
    expect(matchInlineMath('$a\\', 0)).toBe(-1);
  });
  it('returns the exclusive end', () => {
    expect(matchInlineMath('$x$ y', 0)).toBe(3);
  });
});
