/**
 * The Mac's Emacs-style delete keys (CodeMirror binds them on that platform only: Ctrl-h and Ctrl-d delete a character,
 * Ctrl-k to the line end, Ctrl-Alt-h a word back) reach the same commands as Backspace and Delete, so they meet the
 * same guards in Rich mode. CodeMirror reads the platform once, when it is loaded: this file says "Mac" before that.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';

vi.hoisted(() => {
  Object.defineProperty(globalThis.navigator, 'platform', { value: 'MacIntel', configurable: true });
});

import { EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';

const context = { pageName: 'Mac', attachments: [] };
const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount(doc, { live = true, caret = 0 } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      selection: { anchor: caret },
      extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => context, onLinkCard: () => {} }), keymap.of(defaultKeymap)],
    }),
  });
  views.push(view);
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  if (live) setLivePreview(view, true);
  view.dispatch({ selection: { anchor: caret } });
  return view;
}
function ctrl(view, key, init = {}) {
  const event = new KeyboardEvent('keydown', { key, ctrlKey: true, bubbles: true, cancelable: true, ...init });
  view.contentDOM.dispatchEvent(event);
  return event.defaultPrevented;
}
const text = (view) => view.state.doc.toString();
const sel = (view) => [view.state.selection.main.from, view.state.selection.main.to];

const FENCED = 'Above\n```js\nx\n```\nBelow';
const BELOW = FENCED.indexOf('Below');

describe('the Mac delete keys in Rich mode', () => {
  it('control: in Source they are the platform\'s keys (this file runs as a Mac)', () => {
    const view = mount(FENCED, { live: false, caret: BELOW });
    expect(ctrl(view, 'h')).toBe(true);
    expect(text(view)).toBe('Above\n```js\nx\n```Below');
  });

  it.each([
    ['Ctrl-h at the start of the line below a hidden closing fence', BELOW, 'h', {}],
    ['Ctrl-Alt-h there', BELOW, 'h', { altKey: true }],
    ['Ctrl-d at the end of the line above a hidden opening fence', 5, 'd', {}],
    ['Ctrl-k there', 5, 'k', {}],
  ])('%s does nothing: no text is joined onto the fence', (_name, caret, key, mods) => {
    const view = mount(FENCED, { caret });
    expect(ctrl(view, key, mods)).toBe(true);
    expect(text(view)).toBe(FENCED);
  });

  it('Ctrl-k inside a line still deletes to the line end', () => {
    const view = mount(FENCED, { caret: 2 });
    ctrl(view, 'k');
    expect(text(view)).toBe('Ab\n```js\nx\n```\nBelow');
  });

  it('Ctrl-h right after a chip selects the link first, and Ctrl-d right before it', () => {
    const doc = 'x [[Page|a]] y';
    let view = mount(doc, { caret: 12 });
    expect(ctrl(view, 'h')).toBe(true);
    expect(text(view)).toBe(doc);
    expect(sel(view)).toEqual([2, 12]);
    view = mount(doc, { caret: 2 });
    expect(ctrl(view, 'd')).toBe(true);
    expect(text(view)).toBe(doc);
    expect(sel(view)).toEqual([2, 12]);
  });

  it('Ctrl-h right after a line marker removes the innermost marker alone', () => {
    const view = mount('> - item', { caret: 4 });
    expect(ctrl(view, 'h')).toBe(true);
    expect(text(view)).toBe('> item');
  });
});
