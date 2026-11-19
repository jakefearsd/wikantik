/** Column widths of the table grid: the fit, and the width cache's raise-only rule. */
import { describe, it, expect } from 'vitest';
import { fitColumns, createWidthCache } from './tableWidths';

describe('fitColumns', () => {
  it('columns that fit keep their natural width, at least the minimum', () => {
    expect(fitColumns([100, 20, 300], 600, 64, 192)).toEqual([100, 64, 300]);
  });

  it('a table too wide is squeezed: columns under the fair share keep theirs, the rest share what is left', () => {
    expect(fitColumns([100, 900, 500], 700, 64, 192)).toEqual([100, 300, 300]);
  });

  it('a table that cannot fit even at the minimum is not squeezed: it scrolls, with columns capped at the wide limit', () => {
    expect(fitColumns([100, 900, 30, 250], 200, 64, 192)).toEqual([100, 192, 64, 192]);
  });
});

describe('width cache', () => {
  const perChar = (_font, text) => text.length * 10;

  it('estimates what a cell draws (marks and link destinations stripped), once per font and text', () => {
    let calls = 0;
    const cache = createWidthCache((font, text) => { calls += 1; return perChar(font, text); });
    expect(cache.widthOf('f', '**bold** [a](https://example.com)')).toBe('bold a'.length * 10);
    cache.widthOf('f', '**bold** [a](https://example.com)');
    expect(calls).toBe(1);
  });

  it('a measurement raises a width and never lowers one', () => {
    const cache = createWidthCache(perChar);
    expect(cache.widthOf('f', 'abc')).toBe(30);
    const before = cache.generation;
    cache.raise('f', 'abc', 44);
    expect(cache.widthOf('f', 'abc')).toBe(44);
    expect(cache.generation).not.toBe(before);
    cache.raise('f', 'abc', 12);
    expect(cache.widthOf('f', 'abc')).toBe(44);
    cache.raise('f', 'wide', 5); // under its estimate
    expect(cache.widthOf('f', 'wide')).toBe(40);
  });

  it('a raised width in use survives the cache filling up, however many other texts pass through', () => {
    const cache = createWidthCache(perChar, 8);
    cache.raise('f', 'kept', 99);
    for (let i = 0; i < 100; i += 1) {
      cache.widthOf('f', `text${i}`);
      cache.raise('f', `other${i}`, 500);
      expect(cache.widthOf('f', 'kept')).toBe(99); // read on every build, as a drawn table's cells are
    }
  });
});
