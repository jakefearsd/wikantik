import { describe, it, expect, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView, keymap, runScopeHandlers } from '@codemirror/view';
import { defaultKeymap } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { obsidianSyntaxKeymap } from './obsidianSyntaxKeymap';

let view;
afterEach(() => view?.destroy());

/** An editor with the stock keymap AFTER ours in the extension list, so only precedence can make ours win. */
function editor(doc, from, to = from) {
  view = new EditorView({
    parent: document.body,
    state: EditorState.create({
      doc,
      selection: { anchor: from, head: to },
      extensions: [keymap.of(defaultKeymap), obsidianSyntaxKeymap, markdown()],
    }),
  });
  return view;
}
const press = (v, init) => runScopeHandlers(v, new KeyboardEvent('keydown', init), 'editor');
const text = () => view.state.doc.toString();
const sel = () => [view.state.selection.main.from, view.state.selection.main.to];

describe('Mod-Shift-H — highlight', () => {
  it('wraps the selection in == and keeps it selected', () => {
    const v = editor('say hello now', 4, 9);
    expect(press(v, { key: 'H', keyCode: 72, shiftKey: true, ctrlKey: true })).toBe(true);
    expect(text()).toBe('say ==hello== now');
    expect(sel()).toEqual([6, 11]);
  });
  it('unwraps when the selection is already highlighted', () => {
    const v = editor('say ==hello== now', 6, 11);
    press(v, { key: 'H', keyCode: 72, shiftKey: true, ctrlKey: true });
    expect(text()).toBe('say hello now');
    expect(sel()).toEqual([4, 9]);
  });
  it('with no selection puts the caret between the markers', () => {
    const v = editor('ab', 1);
    press(v, { key: 'H', keyCode: 72, shiftKey: true, ctrlKey: true });
    expect(text()).toBe('a====b');
    expect(sel()).toEqual([3, 3]);
  });
});

describe('Mod-/ — toggle comment', () => {
  it('wraps the selection in %% (not the stock HTML-comment toggle)', () => {
    const v = editor('a note here', 2, 6);
    expect(press(v, { key: '/', ctrlKey: true })).toBe(true);
    expect(text()).toBe('a %%note%% here');
    expect(text()).not.toContain('<!--');
  });
  it('unwraps on a second press', () => {
    const v = editor('a %%note%% here', 4, 8);
    press(v, { key: '/', ctrlKey: true });
    expect(text()).toBe('a note here');
  });
});
