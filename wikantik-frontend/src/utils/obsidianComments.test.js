import { describe, it, expect } from 'vitest';
import { stripComments, commentRanges } from './obsidianComments';
import fixture from './__fixtures__/comments.json';

describe('obsidianComments (shared fixture comments.json, also run by CommentsParityTest)', () => {
  it('has rows', () => expect(fixture.length).toBeGreaterThan(10));
  it.each(fixture.map((r) => [r.name, r]))('%s', (_name, row) => {
    expect(stripComments(row.input)).toBe(row.output);
  });
});

describe('commentRanges', () => {
  it('locates each comment, delimiters included', () => {
    const text = 'a %%x%% b %%y\nz%% c';
    expect(commentRanges(text).map((r) => text.slice(r.from, r.to))).toEqual(['%%x%%', '%%y\nz%%']);
  });
  it('is empty without a comment, for an unterminated one, and for null', () => {
    expect(commentRanges('plain 100% text')).toEqual([]);
    expect(commentRanges('a %%b')).toEqual([]);
    expect(commentRanges(null)).toEqual([]);
  });
  it('returns the very same string when nothing is removed', () => {
    const text = 'no comments here';
    expect(stripComments(text)).toBe(text);
  });
  // Each input has 80k-ish markers, backticks, dollars, fences or quote levels: quadratic in any of them takes minutes.
  it.each([
    ['%%`', '%%`'.repeat(80000)],
    ['`%%', '`%%'.repeat(80000)],
    ['%%x$', '%%x$'.repeat(80000)],
    ['$a ', `${'$a '.repeat(80000)}%%`],
    ['opener + masked closers', `%% ${'`%%` '.repeat(80000)}`],
    ['%%x ', '%%x '.repeat(80000)],
    ['$$ ', `${'$$ '.repeat(80000)}%%`],
    ['$$x %% ', '$$x %% '.repeat(80000)],
    ['fences', '```\n%%\n```\n'.repeat(20000)],
    ['quoted fences', '> ```\n> %%\n> ```\n'.repeat(20000)],
    ['unclosed quoted fences', '%%\n> ```\n'.repeat(20000)],
    ['deep quote', `${'>'.repeat(80000)} \`\`\`\n%%x%%\n`],
    ['deep quoted fence', `${'> '.repeat(40000)}~~~\n${'> %%x%%\n'.repeat(20000)}`],
  ])('stays linear on adversarial input: %s', (_name, text) => {
    const start = Date.now();
    stripComments(text);
    expect(Date.now() - start).toBeLessThan(3000);
  });
  it('is linear on a long run of unpaired openers', () => {
    const text = `${'a $b '.repeat(20000)}\n%%\n`;
    const start = Date.now();
    stripComments(text);
    expect(Date.now() - start).toBeLessThan(3000);
  });
});

describe('commentRanges on text with CRLF line endings', () => {
  it('gives offsets in that very text, not in a normalised copy', () => {
    const text = 'a\r\n%%x\r\ny%% b\r\n```\r\n%% c\r\n```\r\n%%z%%';
    expect(commentRanges(text).map((r) => text.slice(r.from, r.to))).toEqual(['%%x\r\ny%%', '%%z%%']);
  });
  it('reads a blank CRLF line as blank: an HTML block ends there, so the math after it is math (reader: <b><p><span class="math-inline">)', () => {
    const text = '<b t="">\r\n\r\n$|%%1. ```\r\n%%$$\r\n      $$\r\n]]b ';
    expect(commentRanges(text)).toEqual([]);
    expect(commentRanges(text.replace(/\r\n/g, '\n'))).toEqual([]);
  });
  it('maps ranges back past every CR before them, over several lines', () => {
    const text = 'a\r\nb\r\n%%x\r\ny%%\r\n\r\n%%z%%';
    expect(commentRanges(text).map((r) => text.slice(r.from, r.to))).toEqual(['%%x\r\ny%%', '%%z%%']);
  });
  it('maps mixed line endings exactly: only a \\r\\n lost a character', () => {
    const cases = ['a\nb\r\n%%x%%', 'a\r\nb\n%%x\ny%%\r\nc', '%%x\r\ny\nz%%', 'a\r\n%%x%%', 'a\rb\r\n%%x%%\r%%y%%'];
    for (const text of cases) {
      const found = commentRanges(text).map((r) => text.slice(r.from, r.to));
      const expected = [...text.matchAll(/%%[\s\S]*?%%/g)].map((m) => m[0]);
      expect(found, JSON.stringify(text)).toEqual(expected);
    }
    expect(commentRanges('a\nb\r\n%%x%%')).toEqual([{ from: 5, to: 10 }]);
  });
  it('an endpoint right after a \\r\\n and a lone \\r map exactly', () => {
    const text = 'a\r\n%%x%%\r\n%%y%%';
    expect(commentRanges(text).map((r) => [r.from, r.to])).toEqual([[3, 8], [10, 15]]);
    expect(commentRanges('a\r%%x%%\r\nb').map((r) => [r.from, r.to])).toEqual([[2, 7]]);
  });
  it('stripComments agrees on mixed line endings', () => {
    expect(stripComments('a\nb\r\n%%x%% c\r\nd')).toBe('a\nb\r\n c\r\nd');
    expect(stripComments('a\r\n%%x\ny%%\nb')).toBe('a\r\n\n\nb');
  });
  it('keeps every newline a stripped comment held', () => {
    expect(stripComments('a %%x\r\ny%% b')).toBe('a \n b');
  });
});
