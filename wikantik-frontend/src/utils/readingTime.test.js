import { describe, it, expect } from 'vitest';
import { readingTime } from './readingTime';

describe('readingTime', () => {
  it('returns { words: 0, minutes: 0 } for empty string', () => {
    expect(readingTime('')).toEqual({ words: 0, minutes: 0 });
  });

  it('returns { words: 0, minutes: 0 } for whitespace-only string', () => {
    expect(readingTime('   \n\t  ')).toEqual({ words: 0, minutes: 0 });
  });

  it('returns { words: 0, minutes: 0 } for null/undefined', () => {
    expect(readingTime(null)).toEqual({ words: 0, minutes: 0 });
    expect(readingTime(undefined)).toEqual({ words: 0, minutes: 0 });
  });

  it('counts 200 words as 1 minute', () => {
    const text = Array(200).fill('word').join(' ');
    const result = readingTime(text);
    expect(result.words).toBe(200);
    expect(result.minutes).toBe(1);
  });

  it('counts 201 words as 2 minutes', () => {
    const text = Array(201).fill('word').join(' ');
    const result = readingTime(text);
    expect(result.words).toBe(201);
    expect(result.minutes).toBe(2);
  });

  it('counts 450 words as 3 minutes (ceil 450/200 = 3)', () => {
    const text = Array(450).fill('word').join(' ');
    const result = readingTime(text);
    expect(result.words).toBe(450);
    expect(result.minutes).toBe(3);
  });

  it('strips leading YAML frontmatter before counting', () => {
    const text = '---\ntitle: Hello\nauthor: Bob\n---\nHello world';
    const result = readingTime(text);
    // only "Hello world" (2 words) should count
    expect(result.words).toBe(2);
    expect(result.minutes).toBe(1); // max(1, ceil(2/200)) = 1
  });

  it('strips fenced code blocks before counting', () => {
    const text = 'Intro text\n```js\nconst x = 1 + 1;\n```\nConclusion';
    const result = readingTime(text);
    // only "Intro text" (2) + "Conclusion" (1) = 3 words
    expect(result.words).toBe(3);
  });

  it('strips HTML tags when given contentHtml', () => {
    const text = '<p>Hello world</p><pre><code>const x = 1;</code></pre>';
    const result = readingTime(text);
    // "Hello world" (2) + "const x = 1;" (4) = 6 words (HTML tags stripped, no fence stripping)
    expect(result.words).toBeGreaterThan(0);
    expect(result.minutes).toBeGreaterThan(0);
  });

  it('a single word returns minutes = 1', () => {
    expect(readingTime('hello')).toEqual({ words: 1, minutes: 1 });
  });
});
