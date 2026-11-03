import { describe, it, expect } from 'vitest';
import { linearCost } from '../../test/linearCost';
import { dialectParser } from './index';
import { wikiLinkRanges, wikiLinkNodeAt } from './wikiLinks';
import { inlineTagRanges } from './inlineTags';
import fixture from '../__fixtures__/wikilinks.json';

const parse = (t) => dialectParser.parse(t);
const links = (t) => wikiLinkRanges(parse(t)).map((r) => t.slice(r.from, r.to));
const shape = (t) => {
  const out = [];
  parse(t).iterate({ enter: (n) => { if (/^Wiki/.test(n.name)) out.push([n.name, t.slice(n.from, n.to)]); } });
  return out;
};

describe('WikiLink node (shared fixture wikilinks.json)', () => {
  // The reader renders one anchor per `expected` entry; the editor must find exactly those wikilinks (none in code,
  // none with a leading space like `[[ -f x ]]`).
  it.each(fixture.map((c) => [c.name, c]))('%s', (_name, c) => {
    expect(links(c.markdown)).toHaveLength(c.expected.length);
  });
  it('finds the two links of a line', () => {
    expect(links('[[ParityTarget]] and [[NoSuchParityPage|n]]\n')).toEqual(['[[ParityTarget]]', '[[NoSuchParityPage|n]]']);
  });
  it('a table cell holds its link with an escaped pipe', () => {
    expect(links('| a |\n|---|\n| [[ParityTarget\\|cell]] |\n')).toEqual(['[[ParityTarget\\|cell]]']);
  });
});

describe('WikiLink shape', () => {
  it('page, heading and alias children', () => {
    expect(shape('[[P#H|a b]]')).toEqual([
      ['WikiLink', '[[P#H|a b]]'], ['WikiLinkMark', '[['], ['WikiLinkTarget', 'P#H'], ['WikiLinkMark', '|'],
      ['WikiLinkAlias', 'a b'], ['WikiLinkMark', ']]'],
    ]);
  });
  it('a same-page heading has a target and no alias', () => {
    expect(shape('[[#H]]').map((x) => x[0])).toEqual(['WikiLink', 'WikiLinkMark', 'WikiLinkTarget', 'WikiLinkMark']);
  });
  it('an escaped pipe ends the target before its backslash', () => {
    expect(shape('[[A\\|b]]')).toContainEqual(['WikiLinkTarget', 'A']);
  });
  it('a second pipe stays in the alias', () => {
    expect(shape('[[a|b|c]]')).toContainEqual(['WikiLinkAlias', 'b|c']);
  });
  it('an embed is a WikiEmbed starting at the bang', () => {
    expect(shape('![[f.png|300]]')[0]).toEqual(['WikiEmbed', '![[f.png|300]]']);
    expect(wikiLinkRanges(parse('x ![[f.png]]'))).toEqual([{ from: 2, to: 12, embed: true }]);
  });
  it('is never a Link', () => {
    let link = false;
    parse('[[x]] ![[y.png]]').iterate({ enter: (n) => { if (n.name === 'Link' || n.name === 'Image') link = true; } });
    expect(link).toBe(false);
  });
});

describe('WikiLink edges', () => {
  it('is not found in code or math', () => {
    expect(links('`[[a]]` $[[b]]$ ```[[c]]``` x')).toEqual([]);
  });
  it('an escaped opener is text; an escaped bang leaves the link', () => {
    expect(links('\\[[x]]')).toEqual([]);
    expect(links('\\![[x]]')).toEqual(['[[x]]']);
    expect(links('\\\\[[x]]')).toEqual(['[[x]]']);
  });
  it('unclosed, empty, spaced and multi-line are text', () => {
    expect(links('[[x')).toEqual([]);
    expect(links('[[]] [[ x]] [[x\ny]]')).toEqual([]);
    expect(links('[[a[b]]')).toEqual([]);
  });
  it('is found inside a markdown link text and a heading', () => {
    expect(links('[see [[A]]](u)')).toEqual(['[[A]]']);
    expect(links('# H [[A]]')).toEqual(['[[A]]']);
  });
  it('a # inside is a heading fragment, not a tag', () => {
    const t = '[[Page#Heading]] #real';
    expect(inlineTagRanges(parse(t), { slice: (a, b) => t.slice(a, b) }).map((r) => t.slice(r.from, r.to))).toEqual(['#real']);
  });
  it('wikiLinkNodeAt resolves through children', () => {
    const t = 'x [[Page|a]] y';
    const tree = parse(t);
    expect(wikiLinkNodeAt(tree, 6).from).toBe(2);
    expect(wikiLinkNodeAt(tree, 0)).toBeNull();
  });
});

describe('WikiLink parser cost', () => {
  it.each(['[[', '[[a', ']]', '[[a|', '![[', '[[a]]', '![[a|b '])('%j repeated stays linear', (unit) => {
    const { ratio, bound } = linearCost((n) => unit.repeat(n), parse, { small: 4000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
  });
});
