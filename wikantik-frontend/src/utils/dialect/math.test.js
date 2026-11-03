import { describe, it, expect } from 'vitest';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';
import { dialectParser, opaqueRanges } from './index';
import { commentRangesInTree } from '../obsidianComments';
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
  it('ends where the list item does when its closer lies outside it (the render draws the fence only that far)', () => {
    // Rendered by the reader (ReaderStructure.renderedMath = [8-18]): the math is `$$\n  x\n`, up to the dedented `para`.
    expect(nodes('- item\n\n  $$\n  x\n\npara\n$$\n', 'DisplayMath')).toEqual(['$$\n  x']);
    expect(nodes('- item\n  $$\n  x\ny\n  $$\n- next\n', 'DisplayMath')).toEqual(['$$\n  x']);
  });
  it('is not math when no `$$` line follows at all, even in a list item', () => {
    expect(nodes('- item\n  $$\n  x\n  y\n', 'DisplayMath')).toEqual([]);
  });
  it('closes at a content line that is a bare fence (the generated math fence closes there)', () => {
    // Rendered by the reader: renderedMath [0-6], [0-8], [0-10] for these three.
    expect(nodes('$$\n```\n%%s%%\n```\n$$\n', 'DisplayMath')).toEqual(['$$\n```']);
    expect(nodes('$$\nx\n```\n%%s%%\n$$\n', 'DisplayMath')).toEqual(['$$\nx\n```']);
    expect(nodes('$$\nx\n  ```\n%%s%%\n$$\n', 'DisplayMath')).toEqual(['$$\nx\n  ```']);
  });
  it('does not close at an info-string fence or a tilde fence', () => {
    expect(nodes('$$\n```js\n%%s%%\n$$\n', 'DisplayMath')).toEqual(['$$\n```js\n%%s%%\n$$']);
    expect(nodes('$$\nx\n~~~\n%%s%%\n$$\n', 'DisplayMath')).toEqual(['$$\nx\n~~~\n%%s%%\n$$']);
  });
  it('holds the same shapes with CRLF line ends', () => {
    expect(nodes('$$\r\nx\r\n```\r\n%%s%%\r\n$$\r\n', 'DisplayMath')).toEqual(["$$\r\nx\r\n```\r"]);
    expect(nodes('- item\r\n  $$\r\n  x\r\ny\r\n  $$\r\n- next\r\n', 'DisplayMath')).toEqual(['$$\r\n  x\r']);
  });
  it('stays code at an indent of four or more (code wins in the editor; the reader pre-pass is code-blind)', () => {
    expect(nodes('    $$\n    x\n    $$\n', 'DisplayMath')).toEqual([]);
  });
  it('does not treat $$ inside a fence as math', () => {
    expect(nodes('```\n$$\nx\n$$\n```\n', 'DisplayMath')).toEqual([]);
  });
});

/** Whether `pos` lies in one of the tree's outermost code or math ranges. */
const opaqueAt = (tree, pos) => opaqueRanges(tree).some(([from, to]) => pos >= from && pos < to);

describe('opaqueRanges by position', () => {
  const text = 'a `c` $m$ $$d$$ z\n\n$$\nb\n$$\n\n```\nf\n```\n';
  const tree = dialectParser.parse(text);
  it.each([['c', true], ['m', true], ['d', true], ['b', true], ['f', true], ['z', false], ['a', false]])('%s', (ch, expected) => {
    expect(opaqueAt(tree, text.lastIndexOf(ch))).toBe(expected);
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

describe('opaqueRanges boundaries and shapes', () => {
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


describe('parse cost is linear in the input', () => {
  // Quadrupling the input costs ~4x when linear and ~16x when quadratic: compare ratios, never absolute milliseconds.
  const INPUTS = [
    ['a paragraph of $$x spans', (n) => '$$x %% '.repeat(n)],
    ['a paragraph of $x$ spans', (n) => '$x$ '.repeat(n)],
    ['unpairable dollars', (n) => '$a '.repeat(n)],
    ['a page of display-math delimiter lines', (n) => '$$\n'.repeat(n)],
    ['a page of display blocks', (n) => '$$\nx\n'.repeat(n)],
    ['a page of list-item blocks closed by a dedent', (n) => '- a\n  $$\n  x\ny\n  $$\n'.repeat(n)],
    ['a page of blocks closed by a bare fence', (n) => '$$\n```\n%%a%%\n$$\n'.repeat(n)],
  ];
  it.each(INPUTS)('%s: 4x the input costs well under 16x the time', (_name, make) => {
    const { ratio, bound } = linearCost(make, (s) => dialectParser.parse(s));
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);

  it('reading every comment marker off a tree with thousands of math spans is linear too', () => {
    const make = (n) => { const text = '$$x %% '.repeat(n); return { text, tree: dialectParser.parse(text) }; };
    const { ratio, bound } = linearCost(make, ({ text, tree }) => commentRangesInTree(text, tree), { small: 10000 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);

  it('opaqueRanges lists the outermost code and math nodes in order', () => {
    const text = 'a $x$ b `c` d\n\n$$\ny\n$$\n\n```\nz\n```\n';
    const tree = dialectParser.parse(text);
    expect(opaqueRanges(tree).map(([from, to]) => text.slice(from, to))).toEqual(['$x$', '`c`', '$$\ny\n$$', '```\nz\n```']);
  });
});
