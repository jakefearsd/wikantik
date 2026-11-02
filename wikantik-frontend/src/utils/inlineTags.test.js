import { describe, it, expect } from 'vitest';
import { normaliseTag, tagViewHref, findInlineTags } from './inlineTags';

describe('findInlineTags (one line)', () => {
  const tags = (t, blocked) => findInlineTags(t, blocked).map((x) => [x.from, x.to, x.written, x.tag]);
  it('finds tags after the line start or whitespace, with offsets and both forms', () => {
    expect(tags('a #Foo and #a/b')).toEqual([[2, 6, 'Foo', 'foo'], [11, 15, 'a/b', 'a-b']]);
    expect(tags('#x')).toEqual([[0, 2, 'x', 'x']]);
  });
  it('ignores digits only, glued hashes, heading lines and links', () => {
    expect(tags('#123 x#foo [t](#frag) https://e.com/#f')).toEqual([]);
    expect(tags('## H #x')).toEqual([]);
    expect(tags('#123a')).toEqual([[0, 5, '123a', '123a']]);
  });
  it('honours the caller refusing a range', () => {
    expect(tags('#a #b', (from) => from === 0)).toEqual([[3, 5, 'b', 'b']]);
  });
});

describe('normaliseTag', () => {
  it('lowercases, flattens slashes and drops leading hashes', () => {
    expect(normaliseTag('#Team/Core')).toBe('team-core');
    expect(normaliseTag(' Foo ')).toBe('foo');
    expect(normaliseTag('##x')).toBe('x');
    expect(normaliseTag(null)).toBe('');
  });
});

describe('tagViewHref', () => {
  it('leads to the tag view by the normalised, percent-encoded tag', () => {
    expect(tagViewHref('Foo')).toBe('/search?tag=foo');
    expect(tagViewHref('a/b')).toBe('/search?tag=a-b');
    expect(tagViewHref('Café')).toBe('/search?tag=caf%C3%A9');
  });
});
