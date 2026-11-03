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
  it('is not math in a blockquote (the reader never matches a `> $$` line)', () => {
    expect(nodes('> $$\n> x\n> $$\n', 'DisplayMath')).toEqual([]);
  });
  it('is not math when a later top-level $$ is outside the opener\'s blockquote', () => {
    expect(nodes('> $$\n> x\n\ntext\n$$\n', 'DisplayMath')).toEqual([]);
    expect(nodes('> $$\n> x\ntext\n$$\n', 'DisplayMath')).toEqual([]);
    expect(nodes('> $$\n> x\n> > $$\n', 'DisplayMath')).toEqual([]);
  });
  it('is not math when a bullet marker shares the line', () => {
    expect(nodes('- $$\n  x\n\npara\n$$\n', 'DisplayMath')).toEqual([]);
  });
  it('is math on indented lines inside a list item, ending at the closer line', () => {
    expect(nodes('- item\n\n  $$\n  x\n\n  y\n  $$\n\npara\n', 'DisplayMath')).toEqual(['$$\n  x\n\n  y\n  $$']);
  });
  it('is not math when the list item ends before a closer (a later top-level $$)', () => {
    expect(nodes('- item\n\n  $$\n  x\n\npara\n$$\n', 'DisplayMath')).toEqual([]);
  });
  it('stays code at an indent of four or more (code wins in the editor; the reader pre-pass is code-blind)', () => {
    expect(nodes('    $$\n    x\n    $$\n', 'DisplayMath')).toEqual([]);
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

describe('DisplayMath follows the reader (DisplayMathPreProcessor)', () => {
  it('may hold a blank line', () => {
    expect(nodes('$$\na\n\nb\n$$\n', 'DisplayMath')).toEqual(['$$\na\n\nb\n$$']);
  });
  it('needs nothing after the closing line', () => {
    expect(nodes('$$\nx\n$$\nmore', 'DisplayMath')).toEqual(['$$\nx\n$$']);
  });
  it('interrupts a paragraph', () => {
    expect(nodes('text\n$$\nx\n$$\n', 'DisplayMath')).toEqual(['$$\nx\n$$']);
  });
  it('is not a node when unterminated, even after text', () => {
    expect(nodes('text\n$$\nx\n', 'DisplayMath')).toEqual([]);
  });
});

describe('opaqueAt boundaries and shapes', () => {
  it('is opaque at a node start and not at its end', () => {
    const inline = 'a $x$%%';
    const t = dialectParser.parse(inline);
    expect(opaqueAt(t, inline.indexOf('$x$'))).toBe(true);
    expect(opaqueAt(t, inline.indexOf('%%'))).toBe(false);
    const block = '$$\nx\n$$\n%%';
    const b = dialectParser.parse(block);
    expect(opaqueAt(b, 0)).toBe(true);
    expect(opaqueAt(b, block.lastIndexOf('$$') + 1)).toBe(true);
    expect(opaqueAt(b, block.indexOf('%%'))).toBe(false);
  });
  it('is not opaque in an unterminated $$', () => {
    const text = '$$\nx\n';
    expect(opaqueAt(dialectParser.parse(text), text.indexOf('x'))).toBe(false);
  });
  it('is opaque in an indented code block', () => {
    const text = '    code %%\n';
    expect(opaqueAt(dialectParser.parse(text), text.indexOf('%%'))).toBe(true);
  });
  it('is not opaque in an unterminated container $$', () => {
    for (const text of ['> $$\n> x\n\ntext\n$$\n', '- $$\n  x\n\npara\n$$\n']) {
      expect(opaqueAt(dialectParser.parse(text), text.indexOf('x'))).toBe(false);
    }
  });
});
