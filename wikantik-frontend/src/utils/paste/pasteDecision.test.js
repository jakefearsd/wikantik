import { describe, it, expect } from 'vitest';
import { decidePaste, singleUrl } from './pasteDecision';

const origin = 'https://wiki.wikantik.com';
const RICH = '<p>See <b>this</b></p>';

describe('singleUrl', () => {
  it('accepts one http(s) URL, trimmed', () => {
    expect(singleUrl('  https://example.com/a?b=1#c \n')).toBe('https://example.com/a?b=1#c');
    expect(singleUrl('http://x.test')).toBe('http://x.test');
  });
  it('rejects anything else', () => {
    expect(singleUrl('https://a.test https://b.test')).toBeNull();
    expect(singleUrl('ftp://x.test')).toBeNull();
    expect(singleUrl('see https://x.test')).toBeNull();
    expect(singleUrl('')).toBeNull();
    expect(singleUrl(undefined)).toBeNull();
  });
});

describe('decidePaste', () => {
  it('plain paste requested → default paste, even for rich HTML', () => {
    expect(decidePaste({ text: 'See this', html: RICH, plain: true })).toBeNull();
  });

  it('inside code → default paste', () => {
    expect(decidePaste({ text: 'See this', html: RICH, inCode: true })).toBeNull();
  });

  it('rich HTML → converted Markdown', () => {
    expect(decidePaste({ text: 'See this', html: RICH, origin })).toEqual({ kind: 'markdown', insert: 'See **this**', droppedImages: 0 });
  });

  it('an inline fragment keeps the spaces at its edges; block content does not', () => {
    expect(decidePaste({ text: ' the word ', html: '<span> the </span><b>word</b><span> </span>' }).insert).toBe(' the **word** ');
    expect(decidePaste({ text: ' Title\n', html: '<h2>Title</h2>' }).insert).toBe('## Title');
  });

  it('structure-free HTML or no HTML → default paste', () => {
    expect(decidePaste({ text: 'words', html: '<p>words</p>' })).toBeNull();
    expect(decidePaste({ text: 'words' })).toBeNull();
    expect(decidePaste({})).toBeNull();
  });

  it('a URL over a selection links the selection', () => {
    expect(decidePaste({ text: 'https://example.com/x', selection: 'the docs' }))
      .toEqual({ kind: 'link', insert: '[the docs](https://example.com/x)', droppedImages: 0 });
  });

  it('a wiki URL over a selection becomes a wiki link', () => {
    expect(decidePaste({ text: 'https://wiki.wikantik.com/wiki/HybridRetrieval#bm25', selection: 'retrieval', origin }))
      .toEqual({ kind: 'link', insert: '[retrieval](HybridRetrieval#bm25)', droppedImages: 0 });
  });

  it('a URL link keeps the selection\'s surrounding spaces outside the brackets and escapes parentheses', () => {
    expect(decidePaste({ text: 'https://e.test/a(b)', selection: ' word ' }).insert).toBe(' [word](https://e.test/a\\(b\\)) ');
  });

  it('a URL wins over the HTML a browser puts beside it (link selection)', () => {
    expect(decidePaste({ text: 'https://e.test', html: '<a href="https://e.test">https://e.test</a>', selection: 'here' }).insert)
      .toBe('[here](https://e.test)');
  });

  it('a URL with no selection, a multi-line selection, a blank selection or a URL selection → default paste', () => {
    const html = '<a href="https://e.test">https://e.test</a>';
    expect(decidePaste({ text: 'https://e.test', html })).toBeNull();
    expect(decidePaste({ text: 'https://e.test', selection: 'two\nlines' })).toBeNull();
    expect(decidePaste({ text: 'https://e.test', selection: '   ' })).toBeNull();
    expect(decidePaste({ text: 'https://e.test', selection: 'https://old.test' })).toBeNull();
  });
});
