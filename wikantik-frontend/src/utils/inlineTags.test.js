import { describe, it, expect } from 'vitest';
import { normaliseTag, tagViewHref, tagEndAt, pluginSpans } from './inlineTags';

describe('tagEndAt (the one rule)', () => {
  it('ends a tag after its body, at the line start or after whitespace', () => {
    expect(tagEndAt('#x', 0)).toBe(2);
    expect(tagEndAt('a #Foo, b', 2)).toBe(6);
    expect(tagEndAt('a #a/b-c_d', 2)).toBe(10);
    expect(tagEndAt('#123a', 0)).toBe(5);
    expect(tagEndAt('#café', 0)).toBe(5);
  });
  it('is -1 for digits only, glued hashes, an empty body and a non-hash', () => {
    expect(tagEndAt('#123', 0)).toBe(-1);
    expect(tagEndAt('x#foo', 1)).toBe(-1);
    expect(tagEndAt('# a', 0)).toBe(-1);
    expect(tagEndAt('##a', 0)).toBe(-1);
    expect(tagEndAt('ab', 0)).toBe(-1);
  });
});

describe('pluginSpans', () => {
  it('reads the leftmost opener to the first } when ] follows', () => {
    expect(pluginSpans('a [{X #b}] c [{Y}]')).toEqual([[2, 10], [13, 18]]);
    expect(pluginSpans('[{a } b}]')).toEqual([]);
    expect(pluginSpans('[{}] [{ open')).toEqual([]);
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
