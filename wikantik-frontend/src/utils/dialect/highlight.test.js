import { describe, it, expect } from 'vitest';
import { dialectParser } from './index';
import cases from '../__fixtures__/highlight.json';

const spans = (text, name = 'Highlight') => {
  const out = [];
  dialectParser.parse(text).iterate({ enter: (n) => { if (n.name === name) out.push(text.slice(n.from, n.to)); } });
  return out;
};

describe('Highlight node (shared fixture)', () => {
  it.each(cases)('$name', ({ markdown, html, noMark }) => {
    const expected = noMark ? 0 : (html.match(/<mark>/g) || []).length;
    expect(spans(markdown)).toHaveLength(expected);
  });
});

describe('Highlight node', () => {
  it('spans its delimiters and carries two marks', () => {
    expect(spans('a ==b c== d')).toEqual(['==b c==']);
    expect(spans('a ==b c== d', 'HighlightMark')).toEqual(['==', '==']);
  });
  it('wraps inline structure', () => {
    expect(spans('==**x**==')).toEqual(['==**x**==']);
    expect(spans('**==x==**')).toEqual(['==x==']);
  });
  it('crosses a soft break inside a paragraph, not a blank line', () => {
    expect(spans('Some ==marked\nacross lines== text.')).toEqual(['==marked\nacross lines==']);
    expect(spans('==a\n\nb==')).toEqual([]);
  });
  it('is found in a list item and a heading', () => {
    expect(spans('- ==x==')).toEqual(['==x==']);
    expect(spans('# ==x==')).toEqual(['==x==']);
  });
  it('pairs adjacent highlights separately', () => {
    expect(spans('==a== ==b==')).toEqual(['==a==', '==b==']);
  });
  it('treats runs of one, three or more as text', () => {
    expect(spans('=x= ===x=== ====x====')).toEqual([]);
  });
});

describe('Highlight parser cost', () => {
  const time = (text) => {
    const t = process.hrtime.bigint();
    dialectParser.parse(text);
    return Number(process.hrtime.bigint() - t) / 1e6;
  };
  const best = (text) => Math.min(time(text), time(text), time(text));
  it.each(['==a ', 'a== ', '=====', '==a== ', '== '])('is linear on a paragraph of %j repeats', (unit) => {
    const small = unit.repeat(6000);
    const large = unit.repeat(24000);
    best(small); // warm up
    const ratio = best(large) / Math.max(best(small), 0.05);
    // 4x the input: linear is ~4, quadratic ~16.
    expect(ratio).toBeLessThan(9);
  });
});
