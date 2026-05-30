import { describe, it, expect } from 'vitest';
import { toggleWrap, toggleLinePrefix, insertLink } from './markdownFormat';

describe('toggleWrap', () => {
  it('wraps selection with marker', () => {
    const state = { text: 'hello world', selStart: 6, selEnd: 11 };
    const result = toggleWrap(state, '**');
    expect(result.text).toBe('hello **world**');
    expect(result.selStart).toBe(8);
    expect(result.selEnd).toBe(13);
  });

  it('unwraps when surrounding characters are the marker', () => {
    // text = 'hello **world**', selection is on 'world' (indices 8..13)
    const state = { text: 'hello **world**', selStart: 8, selEnd: 13 };
    const result = toggleWrap(state, '**');
    expect(result.text).toBe('hello world');
    expect(result.selStart).toBe(6);
    expect(result.selEnd).toBe(11);
  });

  it('unwraps when selection includes the markers', () => {
    const state = { text: 'hello **world** end', selStart: 6, selEnd: 15 };
    const result = toggleWrap(state, '**');
    expect(result.text).toBe('hello world end');
    expect(result.selStart).toBe(6);
    expect(result.selEnd).toBe(11);
  });

  it('wraps with single marker for italic', () => {
    const state = { text: 'abc', selStart: 0, selEnd: 3 };
    const result = toggleWrap(state, '*');
    expect(result.text).toBe('*abc*');
  });

  it('wraps with backtick for inline code', () => {
    const state = { text: 'foo bar', selStart: 4, selEnd: 7 };
    const result = toggleWrap(state, '`');
    expect(result.text).toBe('foo `bar`');
  });

  it('places markers with empty selection (no selection)', () => {
    const state = { text: 'hello', selStart: 5, selEnd: 5 };
    const result = toggleWrap(state, '**');
    expect(result.text).toBe('hello****');
    // Cursor should be between the markers
    expect(result.selStart).toBe(7);
    expect(result.selEnd).toBe(7);
  });
});

describe('toggleLinePrefix', () => {
  it('adds prefix to a single line', () => {
    const state = { text: 'Hello world', selStart: 0, selEnd: 11 };
    const result = toggleLinePrefix(state, '## ');
    expect(result.text).toBe('## Hello world');
  });

  it('removes prefix when all lines already have it', () => {
    const state = { text: '## Hello world', selStart: 3, selEnd: 14 };
    const result = toggleLinePrefix(state, '## ');
    expect(result.text).toBe('Hello world');
  });

  it('adds prefix to multiple selected lines', () => {
    const text = 'line one\nline two\nline three';
    const state = { text, selStart: 0, selEnd: text.length };
    const result = toggleLinePrefix(state, '- ');
    expect(result.text).toBe('- line one\n- line two\n- line three');
  });

  it('removes prefix from multiple selected lines', () => {
    const text = '- line one\n- line two\n- line three';
    const state = { text, selStart: 0, selEnd: text.length };
    const result = toggleLinePrefix(state, '- ');
    expect(result.text).toBe('line one\nline two\nline three');
  });

  it('handles selection in the middle of lines', () => {
    const text = 'first line\nsecond line\nthird line';
    // Select within 'second line'
    const state = { text, selStart: 11, selEnd: 22 };
    const result = toggleLinePrefix(state, '> ');
    expect(result.text).toBe('first line\n> second line\nthird line');
  });
});

describe('insertLink', () => {
  it('wraps selection in link syntax with selection on url', () => {
    const state = { text: 'click here now', selStart: 6, selEnd: 10 };
    const result = insertLink(state);
    expect(result.text).toBe('click [here](url) now');
    // selection should be on 'url'
    const urlIndex = result.text.indexOf('url');
    expect(result.selStart).toBe(urlIndex);
    expect(result.selEnd).toBe(urlIndex + 3);
  });

  it('inserts [text](url) with selection on text when no selection', () => {
    const state = { text: 'end', selStart: 0, selEnd: 0 };
    const result = insertLink(state);
    expect(result.text).toBe('[text](url)end');
    // selection on 'text'
    expect(result.selStart).toBe(1);
    expect(result.selEnd).toBe(5);
  });

  it('handles selection at start of text', () => {
    const state = { text: 'Wikipedia', selStart: 0, selEnd: 9 };
    const result = insertLink(state);
    expect(result.text).toBe('[Wikipedia](url)');
    const urlIndex = result.text.indexOf('url');
    expect(result.selStart).toBe(urlIndex);
    expect(result.selEnd).toBe(urlIndex + 3);
  });
});
