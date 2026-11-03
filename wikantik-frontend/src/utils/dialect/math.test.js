import { describe, it, expect } from 'vitest';
import { dialectParser, opaqueAt } from './index';
import { CASES } from '../inlineMath.cases';

const nodes = (text, name) => {
  const out = [];
  dialectParser.parse(text).iterate({ enter: (n) => { if (n.name === name) out.push(text.slice(n.from, n.to)); } });
  return out;
};

describe('InlineMath node', () => {
  it.each(CASES)('%j', (source, tex) => {
    expect(nodes(source, 'InlineMath').map((s) => s.slice(1, -1))).toEqual(tex);
  });
  it('is not found inside code spans', () => {
    expect(nodes('`$a$` and $b$', 'InlineMath')).toEqual(['$b$']);
  });
});

describe('InlineDisplayMath node', () => {
  it('reads $$…$$ within a paragraph', () => {
    expect(nodes('see $$x+y$$ here', 'InlineDisplayMath')).toEqual(['$$x+y$$']);
  });
  it('needs a closing pair', () => {
    expect(nodes('see $$x+y here', 'InlineDisplayMath')).toEqual([]);
  });
});

describe('DisplayMath node', () => {
  it('opens and closes on $$ lines', () => {
    expect(nodes('$$\nx = 1\n$$\n\nafter', 'DisplayMath')).toEqual(['$$\nx = 1\n$$']);
  });
  it('is not a node when unterminated', () => {
    expect(nodes('$$\nx = 1\n\nafter', 'DisplayMath')).toEqual([]);
  });
  it('is not a node at end of input without a close', () => {
    expect(nodes('$$\nx = 1', 'DisplayMath')).toEqual([]);
  });
  it('works inside a blockquote', () => {
    expect(nodes('> $$\n> x\n> $$\n', 'DisplayMath')).toHaveLength(1);
  });
  it('does not treat $$ inside a fence as math', () => {
    expect(nodes('```\n$$\nx\n$$\n```\n', 'DisplayMath')).toEqual([]);
  });
});

describe('opaqueAt', () => {
  const text = 'a `c` $m$ $$d$$ z\n\n$$\nb\n$$\n\n```\nf\n```\n';
  const tree = dialectParser.parse(text);
  it.each([['c', true], ['m', true], ['d', true], ['b', true], ['f', true], ['z', false], ['a', false]])('%s', (ch, expected) => {
    expect(opaqueAt(tree, text.lastIndexOf(ch === 'a' ? 'a' : ch))).toBe(expected);
  });
});

describe('DisplayMath shape rules (a paragraph-compatible block)', () => {
  it('is not a node when text follows the closing $$ directly', () => {
    expect(nodes('$$\nx\n$$\nmore', 'DisplayMath')).toEqual([]);
  });
  it('does not interrupt a paragraph', () => {
    expect(nodes('text\n$$\nx\n$$\n', 'DisplayMath')).toEqual([]);
  });
});
