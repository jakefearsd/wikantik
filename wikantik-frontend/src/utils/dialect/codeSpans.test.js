import { describe, it, expect } from 'vitest';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';
import { dialectParser } from './index';

const parse = (t) => dialectParser.parse(t);
const code = (text) => { const out = []; parse(text).iterate({ enter: (n) => { if (n.name === 'InlineCode') out.push(text.slice(n.from, n.to)); } }); return out; };
const marks = (text) => { const out = []; parse(text).iterate({ enter: (n) => { if (n.name === 'CodeMark') out.push(text.slice(n.from, n.to)); } }); return out; };

describe('inline code spans', () => {
  it('a run opens a span when a later whole run has its length', () => {
    expect(code('a `b` c ``d ` e`` f')).toEqual(['`b`', '``d ` e``']);
    expect(marks('``d ` e``')).toEqual(['``', '``']);
    expect(code('a `b`` c')).toEqual([]);
    expect(code('a ``b` c')).toEqual([]);
  });
  it('a span runs over a line break of its paragraph', () => {
    expect(code('a `b\nc` d')).toEqual(['`b\nc`']);
  });
  it('a run with no closer is text, all of it: no shorter span opens inside it', () => {
    expect(code('a ```b` c` d')).toEqual(['` c`']);
  });
  it('opens right after an escaped backtick (the reader does; Lezer alone does not)', () => {
    expect(code('a \\````b``` c')).toEqual(['```b```']);
    expect(code('a \\```b``` c')).toEqual([]); // the escaped one leaves a run of two, which three do not close
    expect(code('a \\`b` c`')).toEqual(['` c`']);
  });
  it('a backtick after an unescaped backtick is mid-run; a doubled backslash escapes nothing', () => {
    expect(code('a \\\\``b`` c')).toEqual(['``b``']);
    expect(code('a \\\\`b c')).toEqual([]);
  });
  it.each(['`a ', '``a` ', '\\```a ', '`a` '])('%j repeated stays linear', (unit) => {
    const { ratio, bound } = linearCost((n) => unit.repeat(n), parse, { small: 4000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
  it('unclosed runs of ever-longer lengths stay linear', () => {
    const make = (n) => { let out = ''; for (let k = 1; out.length < n; k += 1) out += `a${'`'.repeat(k)}b `; return out; };
    const { ratio } = linearCost(make, parse, { small: 4000, factor: 16 });
    expect(ratio).toBeLessThan(16 ** 1.25);
  }, LINEAR_COST_TIMEOUT_MS);
});
