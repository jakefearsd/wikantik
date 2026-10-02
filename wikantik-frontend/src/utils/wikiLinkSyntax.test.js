import { describe, it, expect } from 'vitest';
import {
  parseWikiLink, findWikiLinks, wikiLinkDisplayText, wikiLinkHref, collectNativeWikiLinkTargets, isImageFileName,
} from './wikiLinkSyntax';

const one = (t) => parseWikiLink(t);
const targets = (s) => findWikiLinks(s).map((r) => r.target);

describe('wikiLinkSyntax', () => {
  it('parses plain, alias, heading and same-page links', () => {
    expect(one('[[Page]]').target).toBe('Page');
    expect(wikiLinkDisplayText(one('[[Page]]'))).toBe('Page');
    const a = one('[[Page|The Alias]]');
    expect([a.target, a.alias]).toEqual(['Page', 'The Alias']);
    const h = one('[[Page#My Heading]]');
    expect(h.heading).toBe('My Heading');
    expect(wikiLinkDisplayText(h)).toBe('Page > My Heading');
    const s = one('[[#Intro Part]]');
    expect(s.isSamePage).toBe(true);
    expect(wikiLinkDisplayText(s)).toBe('Intro Part');
  });

  it('treats a table-escaped pipe like a plain pipe', () => {
    const r = one('[[Page\\|cell]]');
    expect([r.target, r.alias]).toEqual(['Page', 'cell']);
  });

  it('parses embeds, attachments and sizes', () => {
    const e = one('![[Page#H]]');
    expect([e.embed, e.target, e.heading]).toEqual([true, 'Page', 'H']);
    const img = one('![[Owner/pic.png|300x200]]');
    expect([img.isAttachment, img.pageName, img.fileName, img.size]).toEqual([true, 'Owner', 'pic.png', [300, 200]]);
    expect(one('![[O/f.png|300]]').size).toEqual([300, -1]);
    expect(one('[[Page|300 reasons]]').size).toBeNull();
  });

  it('rejects leading whitespace, empty, bare hash and nested brackets', () => {
    expect(one('[[ -f "$x" ]]')).toBeNull();
    expect(one('[[ Page]]')).toBeNull();
    expect(one('[[#]]')).toBeNull();
    expect(one('[[a [b] c]]')).toBeNull();
  });

  it('trims the target at the end', () => {
    expect(one('[[Page  |x]]').target).toBe('Page');
    expect(one('[[Page ]]').target).toBe('Page');
  });

  it('skips backslash escapes in findWikiLinks', () => {
    expect(targets('a [[One]] \\[[Five]] ![[Six|x]]')).toEqual(['One', 'Six']);
    expect(targets('\\\\[[x]]')).toEqual(['x']);
    const r = findWikiLinks('\\![[x]]')[0];
    expect([r.target, r.embed, r.from]).toEqual(['x', false, 2]);
  });

  it('reports absolute offsets', () => {
    const md = 'x ![[Owner/f.png|300]]';
    const r = findWikiLinks(md)[0];
    expect([r.from, r.to, r.raw]).toEqual([2, md.length, '![[Owner/f.png|300]]']);
  });

  it('handles edge cases like the Java parser', () => {
    expect(findWikiLinks('a\r\n[[T]]')[0].from).toBe(3);
    expect(targets('[[a]]]')).toEqual(['a']);
    expect(one('[[a|b|c]]').alias).toBe('b|c');
    expect(findWikiLinks('[[Page\\|cell]]')[0].alias).toBe('cell');
  });

  it('builds view hrefs', () => {
    expect(wikiLinkHref(parseWikiLink('[[My Page#Set Up]]'))).toBe('My%20Page#set-up');
    expect(wikiLinkHref(parseWikiLink('[[#Intro Part]]'))).toBe('#intro-part');
    expect(wikiLinkHref(parseWikiLink('[[foo]]'), 'Foo')).toBe('Foo');
  });

  it('collects page targets outside code only', () => {
    expect(collectNativeWikiLinkTargets('[[B]] `[[C]]` [[A|x]] [[#H]] ![[O/f.png]]\n\n```\n[[D]]\n```')).toEqual(['A', 'B']);
    expect(collectNativeWikiLinkTargets('\\[[Esc]] [[Real]]')).toEqual(['Real']);
  });

  it('recognises image file names', () => {
    expect(isImageFileName.test('a.PNG')).toBe(true);
    expect(isImageFileName.test('a.pdf')).toBe(false);
  });
});
