import { describe, it, expect } from 'vitest';
import { toggleWrap, toggleLinePrefix, insertLink, insertTable, insertCodeBlock, setHeading, insertCallout, insertMathBlock, insertRule, insertFence, insertFootnote, insertEmbed, insertAccessRule } from './markdownFormat';

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

describe('insertTable', () => {
  it('inserts a GFM table skeleton and selects the first header cell', () => {
    const state = { text: '', selStart: 0, selEnd: 0 };
    const result = insertTable(state);
    expect(result.text).toContain('| Header 1 | Header 2 |');
    expect(result.text).toContain('| --- | --- |');
    expect(result.text).toContain('| Cell 1 | Cell 2 |');
    // Selection lands on the first header label so the user can type over it.
    expect(result.text.slice(result.selStart, result.selEnd)).toBe('Header 1');
  });

  it('prefixes a newline when the cursor is mid-line, but not at line start', () => {
    const atStart = insertTable({ text: 'abc', selStart: 0, selEnd: 0 });
    expect(atStart.text.startsWith('| Header 1')).toBe(true);

    const midLine = insertTable({ text: 'abc', selStart: 3, selEnd: 3 });
    expect(midLine.text).toBe('abc\n| Header 1 | Header 2 |\n| --- | --- |\n| Cell 1 | Cell 2 |\n');

    const afterNewline = insertTable({ text: 'abc\n', selStart: 4, selEnd: 4 });
    expect(afterNewline.text).toBe('abc\n| Header 1 | Header 2 |\n| --- | --- |\n| Cell 1 | Cell 2 |\n');
  });
});

describe('insertCodeBlock', () => {
  it('wraps the selection in a fenced block and selects the language token', () => {
    const state = { text: 'foo bar baz', selStart: 4, selEnd: 7 }; // "bar"
    const result = insertCodeBlock(state);
    expect(result.text).toBe('foo \n```language\nbar\n```\n baz');
    expect(result.text.slice(result.selStart, result.selEnd)).toBe('language');
  });

  it('inserts an empty fence with the language selected when there is no selection', () => {
    const state = { text: '', selStart: 0, selEnd: 0 };
    const result = insertCodeBlock(state);
    expect(result.text).toBe('```language\n\n```\n');
    expect(result.text.slice(result.selStart, result.selEnd)).toBe('language');
  });
});

describe('block inserts for slash commands', () => {
  it('setHeading replaces any existing heading prefix on the line', () => {
    expect(setHeading({ text: '## Old', selStart: 3, selEnd: 3 }, 1).text).toBe('# Old');
    expect(setHeading({ text: 'plain', selStart: 0, selEnd: 0 }, 3).text).toBe('### plain');
  });
  it('setHeading toggles the heading off when the line already has exactly that level', () => {
    const r = setHeading({ text: 'intro\n## Title', selStart: 10, selEnd: 10 }, 2);
    expect(r.text).toBe('intro\nTitle');
    expect(r.selStart).toBe(11);
    expect(setHeading({ text: '### Deep', selStart: 4, selEnd: 4 }, 2).text).toBe('## Deep'); // other level: set
  });
  it('insertCallout starts a callout block on its own line with the cursor after the marker', () => {
    const r = insertCallout({ text: 'para', selStart: 4, selEnd: 4 }, 'warning');
    expect(r.text).toBe('para\n\n> [!warning] \n> ');
    expect(r.text.slice(0, r.selStart)).toBe('para\n\n> [!warning] ');
  });
  it('insertMathBlock puts the cursor on the empty line inside $$', () => {
    const r = insertMathBlock({ text: '', selStart: 0, selEnd: 0 });
    expect(r.text).toBe('$$\n\n$$\n');
    expect(r.selStart).toBe(3);
  });
  it('insertRule adds a thematic break on its own line', () => {
    expect(insertRule({ text: 'a', selStart: 1, selEnd: 1 }).text).toBe('a\n\n---\n');
  });
  it('adds only one newline when the cursor is at the start of a line below a paragraph', () => {
    expect(insertRule({ text: 'a\nb', selStart: 2, selEnd: 2 }).text).toBe('a\n\n---\n\nb');
  });
  it('adds no lead when the previous line is already blank', () => {
    expect(insertRule({ text: 'a\n\nb', selStart: 3, selEnd: 3 }).text).toBe('a\n\n---\n\nb');
  });
  it('keeps a blank line after a math block when text follows', () => {
    const r = insertMathBlock({ text: 'x\n\nnext', selStart: 3, selEnd: 3 });
    expect(r.text).toBe('x\n\n$$\n\n$$\n\nnext');
    expect(r.selStart).toBe(6);
  });
});

/** Minimal-change check: what precedes selStart and follows selEnd survives; only the span between is rewritten. */
function expectMinimalChange(before, after, state, tailAddition = '') {
  expect(after.text.startsWith(before.text.slice(0, before.selStart))).toBe(true);
  expect(after.text.endsWith(before.text.slice(before.selEnd) + tailAddition)).toBe(true);
}

describe('insertFence', () => {
  const body = 'graph TD\n  A --> B';
  it('adds a blank line before the fence and puts the caret at the body', () => {
    const state = { text: 'intro', selStart: 5, selEnd: 5 };
    const r = insertFence(state, 'mermaid', body);
    expect(r.text).toBe('intro\n\n```mermaid\ngraph TD\n  A --> B\n```\n');
    expect(r.selStart).toBe(r.selEnd);
    expect(r.selStart).toBe('intro\n\n```mermaid\n'.length);
    expectMinimalChange(state, r, state);
  });
  it('works in an empty document', () => {
    const r = insertFence({ text: '', selStart: 0, selEnd: 0 }, 'plantuml', 'a -> b');
    expect(r.text).toBe('```plantuml\na -> b\n```\n');
    expect(r.selStart).toBe(r.selEnd);
    expect(r.selStart).toBe('```plantuml\n'.length);
  });
  it('mid-paragraph leaves a blank line before the following text', () => {
    const state = { text: 'ab cd', selStart: 2, selEnd: 2 };
    const r = insertFence(state, 'mermaid', 'x');
    expect(r.text).toBe('ab\n\n```mermaid\nx\n```\n\n cd');
    expect(r.selStart).toBe('ab\n\n```mermaid\n'.length);
    expectMinimalChange(state, r, state);
  });
  it('replaces a selection', () => {
    const state = { text: 'one\n\nTWO\n\nthree', selStart: 5, selEnd: 8 };
    const r = insertFence(state, 'mermaid', 'x');
    expect(r.text).toBe('one\n\n```mermaid\nx\n```\n\n\nthree');
    expect(r.selStart).toBe(r.selEnd);
    expectMinimalChange(state, r, state);
  });
});

describe('insertFootnote', () => {
  it('inserts [^1] at the caret and appends the definition', () => {
    const state = { text: 'hello world', selStart: 5, selEnd: 5 };
    const r = insertFootnote(state);
    expect(r.text).toBe('hello[^1] world\n\n[^1]: ');
    expect(r.selStart).toBe(r.text.length);
    expect(r.selEnd).toBe(r.text.length);
  });
  it('works in an empty document', () => {
    const r = insertFootnote({ text: '', selStart: 0, selEnd: 0 });
    expect(r.text).toBe('[^1]\n\n[^1]: ');
    expect(r.selStart).toBe(r.selEnd);
    expect(r.selStart).toBe(r.text.length);
  });
  it('picks the smallest unused number', () => {
    const text = 'a[^1] b[^3]\n\n[^1]: x\n[^3]: y';
    const r = insertFootnote({ text, selStart: 1, selEnd: 1 });
    expect(r.text.startsWith('a[^2][^1] b[^3]')).toBe(true);
    expect(r.text.endsWith('\n\n[^2]: ')).toBe(true);
  });
  it('inserts after a selection without touching it', () => {
    const state = { text: 'say hello now', selStart: 4, selEnd: 9 };
    const r = insertFootnote(state);
    expect(r.text).toBe('say hello[^1] now\n\n[^1]: ');
    expect(r.selStart).toBe(r.text.length);
  });
  it('keeps text before and after the caret, appending only the definition', () => {
    const state = { text: 'one two three', selStart: 4, selEnd: 4 };
    const r = insertFootnote(state);
    expectMinimalChange(state, r, state, '\n\n[^1]: ');
  });
});

describe('insertEmbed', () => {
  it('inserts ![[]] with the caret between the inner brackets', () => {
    const state = { text: 'see  here', selStart: 4, selEnd: 4 };
    const r = insertEmbed(state);
    expect(r.text).toBe('see ![[]] here');
    expect(r.selStart).toBe(7);
    expect(r.selEnd).toBe(7);
    expectMinimalChange(state, r, state);
  });
  it('works in an empty document', () => {
    const r = insertEmbed({ text: '', selStart: 0, selEnd: 0 });
    expect(r.text).toBe('![[]]');
    expect(r.selStart).toBe(3);
    expect(r.selEnd).toBe(3);
  });
  it('replaces a selection', () => {
    const state = { text: 'a XX b', selStart: 2, selEnd: 4 };
    const r = insertEmbed(state);
    expect(r.text).toBe('a ![[]] b');
    expect(r.selStart).toBe(5);
    expectMinimalChange(state, r, state);
  });
});

describe('insertAccessRule', () => {
  it('puts the rule on its own line before the caret line', () => {
    const state = { text: 'hello world', selStart: 5, selEnd: 5 };
    const r = insertAccessRule(state, { permission: 'view', principals: ['Admin'] });
    expect(r.text).toBe('[{ALLOW view Admin}]\nhello world');
    expect(r.selStart).toBe('[{ALLOW view Admin}]\n'.length);
    expect(r.selEnd).toBe(r.selStart);
  });
  it('works in an empty document', () => {
    const r = insertAccessRule({ text: '', selStart: 0, selEnd: 0 }, { permission: 'edit', principals: ['Editors'] });
    expect(r.text).toBe('[{ALLOW edit Editors}]\n');
    expect(r.selStart).toBe(r.text.length);
  });
  it('joins several principals in the canonical spelling', () => {
    const r = insertAccessRule({ text: 'x', selStart: 0, selEnd: 0 }, { permission: 'modify', principals: ['Jane', 'Bob'] });
    expect(r.text).toBe('[{ALLOW modify Jane, Bob}]\nx');
  });
  it('uses the line of a selection start and leaves the selection text alone', () => {
    const state = { text: 'one\ntwo three\nfour', selStart: 8, selEnd: 13 };
    const r = insertAccessRule(state, { permission: 'view', principals: ['All'] });
    expect(r.text).toBe('one\n[{ALLOW view All}]\ntwo three\nfour');
    expect(r.selStart).toBe('one\n[{ALLOW view All}]\n'.length);
    expect(r.text.slice(r.selStart)).toBe('two three\nfour');
  });
  it('changes nothing but the inserted line', () => {
    const state = { text: 'one\ntwo', selStart: 5, selEnd: 5 };
    const r = insertAccessRule(state, { permission: 'delete', principals: ['Admin'] });
    expect(r.text.startsWith('one\n')).toBe(true);
    expect(r.text.endsWith('two')).toBe(true);
    expect(r.text.length).toBe(state.text.length + '[{ALLOW delete Admin}]\n'.length);
  });
});

describe('setHeading level 4', () => {
  it('makes an H4 and toggles it back', () => {
    const on = setHeading({ text: 'Title', selStart: 2, selEnd: 2 }, 4);
    expect(on.text).toBe('#### Title');
    expect(on.selStart).toBe(on.text.length);
    const off = setHeading({ text: on.text, selStart: 3, selEnd: 3 }, 4);
    expect(off.text).toBe('Title');
  });
  it('replaces a different level', () => {
    expect(setHeading({ text: '## Title', selStart: 0, selEnd: 0 }, 4).text).toBe('#### Title');
  });
});

describe('toggleLinePrefix with list and quote prefixes', () => {
  it('numbers every selected line with "1. "', () => {
    const state = { text: 'a\nb\nc', selStart: 0, selEnd: 5 };
    const r = toggleLinePrefix(state, '1. ');
    expect(r.text).toBe('1. a\n1. b\n1. c');
    expect(toggleLinePrefix({ text: r.text, selStart: 0, selEnd: r.text.length }, '1. ').text).toBe('a\nb\nc');
  });
  it('adds a task prefix on the caret line only', () => {
    const r = toggleLinePrefix({ text: 'x\ny', selStart: 3, selEnd: 3 }, '- [ ] ');
    expect(r.text).toBe('x\n- [ ] y');
  });
  it('quotes a selection and unquotes it again', () => {
    const r = toggleLinePrefix({ text: 'a\nb', selStart: 0, selEnd: 3 }, '> ');
    expect(r.text).toBe('> a\n> b');
    expect(toggleLinePrefix({ text: r.text, selStart: 0, selEnd: r.text.length }, '> ').text).toBe('a\nb');
  });
});
