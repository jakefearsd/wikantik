import { describe, it, expect } from 'vitest';
import { parseGfm } from './gfmParse';
import { collectNativeWikiLinkTargets } from './wikiLinkSyntax';
import { collectWikiLinkTargets } from './wikiLinkTargets';

describe('parseGfm', () => {
  it('reuses the tree for the same text and re-parses changed text', () => {
    const a = parseGfm('a ~~b~~ [x](Y)');
    expect(parseGfm('a ~~b~~ [x](Y)')).toBe(a);
    const b = parseGfm('other');
    expect(b).not.toBe(a);
    expect(a.children[0].children.map((n) => n.type)).toEqual(['text', 'delete', 'text', 'link']);
  });

  it('the two editor scans of one body share one parse and leave it intact', () => {
    const md = '[[Alpha]] and [b](Beta) and `[[NotALink]]`';
    expect(collectWikiLinkTargets(md)).toEqual(['Beta']);
    const tree = parseGfm(md);
    const before = JSON.stringify(tree);
    expect(collectNativeWikiLinkTargets(md)).toEqual(['Alpha']);
    expect(parseGfm(md)).toBe(tree);
    expect(JSON.stringify(tree)).toBe(before);
  });
});
