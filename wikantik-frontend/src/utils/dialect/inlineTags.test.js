import { describe, it, expect } from 'vitest';
import { dialectParser } from './index';
import { inlineTagRanges } from './inlineTags';
import { commentRangesInTree } from '../obsidianComments';
import { normaliseTag } from '../inlineTags';
import fixture from '../__fixtures__/inline-tags.json';

/** The tags the editor draws: InlineTag nodes outside headings and links, outside `%%` comments. */
const tagsOf = (text) => {
  const tree = dialectParser.parse(text);
  const comments = commentRangesInTree(text, tree);
  return inlineTagRanges(tree)
    .filter((r) => !comments.some((c) => r.from < c.to && r.to > c.from))
    .map((r) => normaliseTag(text.slice(r.from, r.to)));
};

describe('InlineTag node (shared fixture inline-tags.json)', () => {
  it.each(fixture.map((c) => [c.name, c]))('%s', (_name, c) => {
    expect(tagsOf(c.markdown)).toEqual(c.tags);
  });
});

describe('InlineTag node beyond the fixture', () => {
  it('spans the # and its body, with the written text', () => {
    const text = 'a #Foo and #a/b.';
    const tree = dialectParser.parse(text);
    expect(inlineTagRanges(tree).map((r) => text.slice(r.from, r.to))).toEqual(['#Foo', '#a/b']);
  });
  it('restricts to a window', () => {
    const text = '#a\n\n#b';
    expect(inlineTagRanges(dialectParser.parse(text), 3, text.length).map((r) => r.from)).toEqual([4]);
  });
  it('a table cell starts a fresh run, an escaped pipe does not end one', () => {
    expect(tagsOf('| a | b |\n| - | - |\n| x | #y |\n|#z| w\\|#v |')).toEqual(['y', 'z']);
  });
  it('is not found in code, plugin text or after a plugin on the same line', () => {
    expect(tagsOf('`#a` [{X #c}] #d')).toEqual(['d']);
  });
  it('an unclosed [{ is not plugin text', () => {
    expect(tagsOf('[{ #a')).toEqual(['a']);
  });
});

describe('InlineTag parser cost', () => {
  const time = (s) => { const t = performance.now(); dialectParser.parse(s); return performance.now() - t; };
  it.each(['#', '##a', 'a#b', '#a/', '#1 ', '#a #', '[{ #a '])('%j repeated stays linear', (unit) => {
    time(unit.repeat(2000));
    const small = Math.max(time(unit.repeat(10000)), 1);
    const big = time(unit.repeat(40000));
    expect(big / small).toBeLessThan(12);
  });
});
