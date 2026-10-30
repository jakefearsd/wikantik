/**
 * Paste-as-Markdown through the REAL CodeEditor + CodeMirror: the paste DOM event reaches CodeMirror's handler chain
 * exactly as a browser's would, so these cover precedence against the file-paste handler and CodeMirror's own paste.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { createRef } from 'react';
import { EditorView } from '@codemirror/view';
import { undo } from '@codemirror/commands';
import CodeEditor from '../../components/CodeEditor';
import { parseFully } from '../../test/fullParse';
import { inCodeAt } from './markdownPaste';

function mount(value, props = {}) {
  const ref = createRef();
  const onChange = vi.fn();
  const utils = render(<CodeEditor ref={ref} value={value} onChange={onChange} {...props} />);
  const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
  parseFully(view);
  return { ref, onChange, view, ...utils };
}

function clipboard({ text = '', html = '', files = [] }) {
  const data = { 'text/plain': text, 'text/html': html };
  return { getData: (type) => data[type] || '', files, types: Object.keys(data).filter((k) => data[k]) };
}

function paste(view, contents) {
  const event = new Event('paste', { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'clipboardData', { value: clipboard(contents) });
  act(() => { view.contentDOM.dispatchEvent(event); });
  return event;
}

function pressPlainPaste(view, mods = { ctrlKey: true }) {
  act(() => {
    view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', { key: 'V', code: 'KeyV', shiftKey: true, bubbles: true, cancelable: true, ...mods }));
  });
}

const select = (view, anchor, head = anchor) => act(() => { view.dispatch({ selection: { anchor, head } }); });

const GOOGLE_DOCS = '<meta charset="utf-8"><b style="font-weight:normal;" id="docs-internal-guid-1"><p dir="ltr"><span style="font-weight:400">Ship </span>'
  + '<span style="font-weight:700">three</span><span style="font-weight:400"> features</span></p></b>';

afterEach(() => vi.restoreAllMocks());

describe('paste as Markdown (real CodeMirror)', () => {
  it('converts rich clipboard HTML at the caret and reports the conversion', () => {
    const onPasteConverted = vi.fn();
    const { view, onChange } = mount('Intro: ', { onPasteConverted });
    select(view, 7);
    const event = paste(view, { text: 'Ship three features', html: GOOGLE_DOCS });
    expect(event.defaultPrevented).toBe(true);
    expect(view.state.doc.toString()).toBe('Intro: Ship **three** features');
    expect(view.state.selection.main.head).toBe(view.state.doc.length);
    expect(onChange).toHaveBeenLastCalledWith('Intro: Ship **three** features', expect.anything());
    expect(onPasteConverted).toHaveBeenCalledWith({ kind: 'markdown', insert: 'Ship **three** features', droppedImages: 0 });
  });

  it('one undo removes the whole converted paste', () => {
    const { view } = mount('x');
    select(view, 1);
    paste(view, { text: 'Ship three features', html: GOOGLE_DOCS });
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('x');
  });

  it('replaces the selection', () => {
    const { view } = mount('keep REPLACE keep');
    select(view, 5, 12);
    paste(view, { text: 'bold', html: '<b>bold</b>' });
    expect(view.state.doc.toString()).toBe('keep **bold** keep');
  });

  it('Ctrl+Shift+V pastes the plain text, for exactly one paste', () => {
    const onPasteConverted = vi.fn();
    const { view } = mount('', { onPasteConverted });
    pressPlainPaste(view);
    paste(view, { text: 'Ship three features', html: GOOGLE_DOCS });
    expect(view.state.doc.toString()).toBe('Ship three features');
    expect(onPasteConverted).not.toHaveBeenCalled();
    paste(view, { text: ' bold', html: '<span> </span><b>bold</b>' });
    expect(view.state.doc.toString()).toBe('Ship three features **bold**');
  });

  it('Cmd+Shift+V (and Cmd+Option+Shift+V) on a Mac also paste plain text', () => {
    const { view } = mount('');
    pressPlainPaste(view, { metaKey: true });
    paste(view, { text: 'a', html: '<b>a</b>' });
    pressPlainPaste(view, { metaKey: true, altKey: true });
    paste(view, { text: 'b', html: '<b>b</b>' });
    expect(view.state.doc.toString()).toBe('ab');
  });

  it('a plain-paste request that no paste follows expires', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    const { view } = mount('');
    pressPlainPaste(view);
    now.mockReturnValue(3_000);
    paste(view, { text: 'a', html: '<b>a</b>' });
    expect(view.state.doc.toString()).toBe('**a**');
  });

  it('pastes verbatim inside a fenced code block', () => {
    const { view } = mount('```\n\n```\n');
    select(view, 4);
    paste(view, { text: 'const x = 1;', html: '<b>const</b> x = 1;' });
    expect(view.state.doc.toString()).toBe('```\nconst x = 1;\n```\n');
  });

  it('pastes verbatim inside inline code, converts right after it', () => {
    const { view } = mount('a `code` b');
    select(view, 5);
    paste(view, { text: 'X', html: '<b>X</b>' });
    expect(view.state.doc.toString()).toBe('a `coXde` b');
    select(view, 9);
    paste(view, { text: 'Y', html: '<b>Y</b>' });
    expect(view.state.doc.toString()).toBe('a `coXde`**Y** b');
  });

  it('a URL pasted over a selection links it', () => {
    const { view } = mount('read the docs today');
    select(view, 5, 13);
    paste(view, { text: 'https://example.com/docs' });
    expect(view.state.doc.toString()).toBe('read [the docs](https://example.com/docs) today');
  });

  it('a URL pasted at a caret is inserted as-is', () => {
    const { view } = mount('');
    paste(view, { text: 'https://example.com/docs', html: '<a href="https://example.com/docs">https://example.com/docs</a>' });
    expect(view.state.doc.toString()).toBe('https://example.com/docs');
  });

  it('structure-free HTML (a code editor copy) pastes the plain text', () => {
    const { view } = mount('');
    paste(view, { text: 'const a = 1;', html: '<div style="font-family: monospace;white-space: pre;"><div><span style="color:#569cd6">const</span> a = 1;</div></div>' });
    expect(view.state.doc.toString()).toBe('const a = 1;');
  });

  it('a file-only paste still goes to onFiles; files beside rich text are ignored in favour of the text', () => {
    const onFiles = vi.fn();
    const file = new File(['x'], 'shot.png', { type: 'image/png' });
    const { view } = mount('', { onFiles });
    paste(view, { files: [file] });
    expect(onFiles).toHaveBeenCalledTimes(1);
    paste(view, { text: 'Bold', html: '<b>Bold</b>', files: [file] });
    expect(onFiles).toHaveBeenCalledTimes(1);
    expect(view.state.doc.toString()).toBe('**Bold**');
  });

  it('with pasteAsMarkdown off (legacy wiki-markup pages) every paste is plain text', () => {
    const onPasteConverted = vi.fn();
    const { view } = mount('', { onPasteConverted, pasteAsMarkdown: false });
    paste(view, { text: 'Bold', html: '<b>Bold</b>' });
    expect(view.state.doc.toString()).toBe('Bold');
    expect(onPasteConverted).not.toHaveBeenCalled();
  });

  it('pasteText inserts plain text over the selection as one edit', () => {
    const { ref, view, onChange } = mount('one TWO three');
    select(view, 4, 7);
    act(() => { expect(ref.current.pasteText('**not converted** <b>x</b>')).toBe(true); });
    expect(view.state.doc.toString()).toBe('one **not converted** <b>x</b> three');
    expect(onChange).toHaveBeenCalled();
    // pasteText focuses the editor; happy-dom then fires selectionchange synchronously inside undo's update (browsers
    // queue it), so blur before undoing.
    act(() => { view.contentDOM.blur(); undo(view); });
    expect(view.state.doc.toString()).toBe('one TWO three');
  });
});

describe('inCodeAt', () => {
  it('is true strictly inside code and false at its outer edges', () => {
    const { view } = mount('p `ic` q\n\n```js\nlet a;\n```\n\n    indented\n');
    const doc = view.state.doc.toString();
    expect(inCodeAt(view.state, doc.indexOf('ic') + 1)).toBe(true);
    expect(inCodeAt(view.state, doc.indexOf('`ic`'))).toBe(false);
    expect(inCodeAt(view.state, doc.indexOf('`ic`') + 4)).toBe(false);
    expect(inCodeAt(view.state, doc.indexOf('let'))).toBe(true);
    expect(inCodeAt(view.state, doc.indexOf('indented') + 2)).toBe(true);
    expect(inCodeAt(view.state, 1)).toBe(false);
  });
});
