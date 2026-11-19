/**
 * useBlockPicker wired as PageEditor wires it — the real CodeEditor and the hook — with its command run through the
 * command registry, as the palette runs it. The caret cannot rest on a fence's header line and Tab indents, so this
 * command is the keyboard's way to a block's language or type picker.
 */
import { describe, it, expect, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- the editor must be gone before the next test's selection events
import { render, act, cleanup } from '@testing-library/react';
import { useRef } from 'react';
import { EditorView } from '@codemirror/view';
import '../test/asyncSelectionChange';
import { parseFully } from '../test/fullParse';
import CodeEditor from '../components/CodeEditor';
import { getCommand, runCommand, __resetRegistryForTest } from '../commands/registry';
import { BLOCK_PICKER_COMMAND_ID } from '../utils/editorCommands';
import { useBlockPicker } from './useBlockPicker';

const DOC = 'intro\n\n```js\nconst a = 1;\n```\n\n> [!note] Title\n> body text\n\n> a plain quote\n\n```mermaid\nflowchart TD\n  A-->B\n```\n\nafter\n';

function Host({ doc = DOC, live = true, readOnly = false }) {
  const editorRef = useRef(null);
  const { onViewChange } = useBlockPicker({ editorRef, readOnly });
  return (
    <CodeEditor ref={editorRef} value={doc} onChange={() => {}} livePreview={live} readOnly={readOnly}
      livePreviewContext={{ pageName: 'Here', attachments: [] }} onViewChange={onViewChange} />
  );
}

function mount(props = {}) {
  const utils = render(<Host {...props} />);
  const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
  act(() => { parseFully(view); });
  return { view, ...utils };
}
const docOf = (view) => view.state.doc.toString();
const caretIn = (view, text) => act(() => { view.dispatch({ selection: { anchor: docOf(view).indexOf(text) + 1 } }); });
const run = () => act(async () => { await runCommand(BLOCK_PICKER_COMMAND_ID); });
const available = () => getCommand(BLOCK_PICKER_COMMAND_ID) !== undefined;
const focused = () => document.activeElement;

afterEach(() => {
  document.getSelection()?.removeAllRanges();
  cleanup();
  __resetRegistryForTest();
});

describe('the "Change code language / callout type" command', () => {
  it('exists only while the caret is in a fenced block or a callout', () => {
    const { view } = mount();
    caretIn(view, 'intro');
    expect(available()).toBe(false);
    caretIn(view, 'const a');
    expect(available()).toBe(true);
    expect(getCommand(BLOCK_PICKER_COMMAND_ID)).toMatchObject({ title: 'Change code language / callout type', section: 'Editor' });
    caretIn(view, 'a plain quote');
    expect(available()).toBe(false);
    caretIn(view, 'body text');
    expect(available()).toBe(true);
    caretIn(view, 'after');
    expect(available()).toBe(false);
  });

  it.each([
    ['a code block', 'const a', 'Code language', 'js'],
    ['a callout, from its title line', 'Title', 'Callout type', 'note'],
    ['a callout, from a later line', 'body text', 'Callout type', 'note'],
    ['a diagram', 'A-->B', 'Diagram kind', 'mermaid'],
  ])('in %s it moves the focus to that block\'s picker, and writes nothing', async (_name, text, label, value) => {
    const { view } = mount();
    act(() => { view.focus(); });
    caretIn(view, text);
    await run();
    expect(focused().tagName).toBe('SELECT');
    expect(focused().getAttribute('aria-label')).toBe(label);
    expect(focused().value).toBe(value);
    expect(view.contentDOM.contains(focused())).toBe(true);
    expect(docOf(view)).toBe(DOC);
  });

  it('from the picker, a choice made with the keyboard writes the one word and the focus returns to the text', async () => {
    const { view } = mount();
    act(() => { view.focus(); });
    caretIn(view, 'const a');
    await run();
    const select = focused();
    act(() => {
      select.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })); // opens the list
      select.value = 'python';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(docOf(view)).toBe(DOC.replace('```js', '```python'));
    expect(view.hasFocus).toBe(true);
  });

  it.each([
    ['in Source', { live: false }],
    ['on a read-only document', { readOnly: true }],
  ])('%s it does not exist, and a run held from before moves no focus', async (_name, props) => {
    const first = mount();
    caretIn(first.view, 'const a');
    const held = getCommand(BLOCK_PICKER_COMMAND_ID);
    expect(held).toBeDefined();
    first.rerender(<Host {...props} />);
    act(() => { parseFully(first.view); });
    caretIn(first.view, 'onst a');
    expect(available()).toBe(false);
    const before = focused();
    await act(async () => { await held.run(); });
    expect(focused()).toBe(before);
    expect(docOf(first.view)).toBe(DOC);
  });

  it('with the caret outside any such block a run held from before does nothing', async () => {
    const { view } = mount();
    caretIn(view, 'const a');
    const held = getCommand(BLOCK_PICKER_COMMAND_ID);
    caretIn(view, 'intro');
    act(() => { view.focus(); });
    await act(async () => { await held.run(); });
    expect(focused().tagName).not.toBe('SELECT');
    expect(docOf(view)).toBe(DOC);
  });
});
