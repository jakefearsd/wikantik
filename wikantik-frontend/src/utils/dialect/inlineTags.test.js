import { describe, it, expect } from 'vitest';
import { linearCost } from '../../test/linearCost';
import { dialectParser } from './index';
import { inlineTagRanges } from './inlineTags';
import { commentRangesInTree } from '../obsidianComments';
import { normaliseTag } from '../inlineTags';
import fixture from '../__fixtures__/inline-tags.json';

/** The tags the editor draws: InlineTag nodes outside headings and links, outside `%%` comments. */
const tagsOf = (text) => {
  const slice = (a, b) => text.slice(a, b);
  const tree = dialectParser.parse(text);
  const comments = commentRangesInTree(text, tree);
  return inlineTagRanges(tree, { slice })
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
    expect(inlineTagRanges(tree, { slice: (a, b) => text.slice(a, b) }).map((r) => text.slice(r.from, r.to))).toEqual(['#Foo', '#a/b']);
  });
  it('restricts to a window', () => {
    const text = '#a\n\n#b';
    expect(inlineTagRanges(dialectParser.parse(text), { slice: (a, b) => text.slice(a, b), from: 3 }).map((r) => r.from)).toEqual([4]);
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

describe('InlineTag in brackets', () => {
  it('a link renders only with a URL or a defined label', () => {
    expect(tagsOf('[a #t](u) ![i #t](i.png) [b #t][r] [c #t]\n\n[r]: u')).toEqual(['t']);
    expect(tagsOf('[a #t][R]\n\n[r]: u')).toEqual([]);
    expect(tagsOf('[a #t][]\n\n[a #t]: u')).toEqual([]);
    expect(tagsOf('![a #t][r] ![b #u]\n\n[r]: u')).toEqual(['u']);
  });
});

describe('InlineTag parser cost', () => {
  // The code-span shapes hold many `[{…}]` spans before every # (the span check once scanned them all per #, 9 s at
  // 40k) while Lezer stays cheap on them; a bare `[{A}] #a ` run is quadratic in @lezer/markdown itself (unresolved
  // `[…]`), so it cannot be asserted here.
  it.each(['#', '##a', 'a#b', '#a/', '#1 ', '#a #', '[{ #a ', '`[{A}]` #a ', '`[{A #a}]` '])('%j repeated stays linear', (unit) => {
    const { ratio, bound } = linearCost((n) => unit.repeat(n), (s) => dialectParser.parse(s), { small: 8000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
  });
});
