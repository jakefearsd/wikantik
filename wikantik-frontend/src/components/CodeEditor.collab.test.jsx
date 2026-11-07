/**
 * CodeEditor in collaborative mode (docs/MultiEdit.MD §0 A4) against the REAL @uiw/react-codemirror + CodeMirror.
 *
 * A controlled `value` that differs from the document makes react-codemirror dispatch a whole-document replacement,
 * which `collab()` would record as a local edit and broadcast, reverting everybody else's work. In collab mode the
 * editor is uncontrolled: it starts from the joined document and a parent re-render never touches it.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { createRef } from 'react';
import { EditorView } from '@codemirror/view';
import { collab, sendableUpdates } from '@codemirror/collab';
import { ExternalChange } from '@uiw/react-codemirror';
import CodeEditor from './CodeEditor';

const wait = (ms) => act(async () => { await new Promise((r) => setTimeout(r, ms)); });

function setup({ value = 'Hello', collabDoc = 'Hello' } = {}) {
  const seen = [];
  const spy = EditorView.updateListener.of((u) => { u.transactions.forEach((tr) => seen.push(tr)); });
  const collabExtensions = [collab({ startVersion: 0, clientID: 'c1' }), spy];
  const onChange = vi.fn();
  const ref = createRef();
  const el = (props = {}) => (
    <CodeEditor ref={ref} value={value} collabDoc={collabDoc} collabExtensions={collabExtensions} onChange={onChange} {...props} />
  );
  const utils = render(el());
  const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
  return { ...utils, el, view, seen, onChange, ref, collabExtensions };
}

afterEach(() => { vi.restoreAllMocks(); });

describe('CodeEditor in collab mode', () => {
  it('starts from the joined document, not from the value prop', () => {
    const { view } = setup({ value: 'stale page text', collabDoc: 'Joined draft' });
    expect(view.state.doc.toString()).toBe('Joined draft');
    expect(sendableUpdates(view.state)).toHaveLength(0);
  });

  it('noFullDocumentReplacementReachesCollab: a parent re-render with a new value dispatches nothing', async () => {
    const { view, seen, rerender, el } = setup();
    seen.length = 0;
    rerender(el({ value: 'Totally different text' }));
    rerender(el({ value: '' }));
    await wait(300); // react-codemirror replays a deferred value change after its 200 ms typing latch
    expect(seen.filter((tr) => tr.docChanged)).toEqual([]);
    expect(view.state.doc.toString()).toBe('Hello');
    expect(sendableUpdates(view.state)).toHaveLength(0);
  });

  it('typing then a parent re-render with the typed text (and later a stale one) leaves exactly one local update', async () => {
    const { view, seen, rerender, el, onChange } = setup();
    act(() => { view.dispatch({ changes: { from: 5, insert: '!' }, userEvent: 'input.type' }); });
    expect(onChange).toHaveBeenLastCalledWith('Hello!', expect.anything());
    seen.length = 0;
    rerender(el({ value: 'Hello!' }));
    rerender(el({ value: 'Hello' })); // a stale parent value
    await wait(300);
    expect(seen.filter((tr) => tr.docChanged)).toEqual([]);
    expect(view.state.doc.toString()).toBe('Hello!');
    expect(sendableUpdates(view.state)).toHaveLength(1);
  });

  it('refuses a whole-document replacement marked as an external value change', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { view } = setup();
    act(() => {
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: 'clobbered' }, annotations: ExternalChange.of(true) });
    });
    expect(view.state.doc.toString()).toBe('Hello');
    expect(sendableUpdates(view.state)).toHaveLength(0);
    expect(warn).toHaveBeenCalled();
  });

  it('body replacements through the handle are one minimal local transaction', () => {
    const { view, ref, seen } = setup({ collabDoc: 'one two three' });
    seen.length = 0;
    act(() => { expect(ref.current.applyChanges([{ from: 4, to: 7, insert: 'TWO' }])).toBe(true); });
    expect(view.state.doc.toString()).toBe('one TWO three');
    const changed = seen.filter((tr) => tr.docChanged);
    expect(changed).toHaveLength(1);
    const spans = [];
    changed[0].changes.iterChanges((fromA, toA) => spans.push([fromA, toA]));
    expect(spans).toEqual([[4, 7]]);
  });

  it('keeps the collab extensions it mounted with: new ones need a remount (a reconfigure keeps a stale start version)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { view, rerender, el } = setup();
    rerender(el({ collabExtensions: [collab({ startVersion: 9, clientID: 'other' })] }));
    act(() => { view.dispatch({ changes: { from: 0, insert: 'x' } }); });
    expect(sendableUpdates(view.state)[0].clientID).toBe('c1');
    expect(warn).toHaveBeenCalled();
  });

  it('readOnly makes the editor read-only', () => {
    const { view, rerender, el } = setup();
    expect(view.state.readOnly).toBe(false);
    rerender(el({ readOnly: true }));
    expect(view.state.readOnly).toBe(true);
  });

  it('without collabExtensions the editor stays controlled (single-author mode is unchanged)', async () => {
    const onChange = vi.fn();
    const utils = render(<CodeEditor value="a" onChange={onChange} />);
    const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
    utils.rerender(<CodeEditor value="b" onChange={onChange} />);
    await wait(10);
    expect(view.state.doc.toString()).toBe('b');
  });
});
