import { describe, it, expect } from 'vitest';
import { normaliseTag, tagViewHref } from './inlineTags';

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
