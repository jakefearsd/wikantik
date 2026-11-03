import { describe, it, expect } from 'vitest';
import { dialectExtensions, dialectParser } from './index';

describe('dialect index', () => {
  it('exports extensions and a parser yielding math nodes', () => {
    expect(dialectExtensions.length).toBeGreaterThan(0);
    const names = new Set();
    dialectParser.parse('$x$\n\n$$\ny\n$$\n').iterate({ enter: (n) => names.add(n.name) });
    expect(names.has('InlineMath')).toBe(true);
    expect(names.has('DisplayMath')).toBe(true);
  });
  it('has no subscript, superscript or emoji nodes', () => {
    const names = new Set();
    dialectParser.parse('~x~ ^x^ :smile: ~~gone~~').iterate({ enter: (n) => names.add(n.name) });
    expect(names.has('Subscript')).toBe(false);
    expect(names.has('Superscript')).toBe(false);
    expect(names.has('Emoji')).toBe(false);
    expect(names.has('Strikethrough')).toBe(true);
  });
});
