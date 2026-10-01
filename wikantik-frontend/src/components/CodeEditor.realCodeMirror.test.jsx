/**
 * CodeEditor against the REAL @uiw/react-codemirror + CodeMirror (no stub). happy-dom has no layout, but
 * EditorView mounts and its state/dispatch/onChange path runs for real — including react-codemirror's
 * typing latch, which defers external `value` changes for ≥200 ms after any non-External transaction.
 * That latch is why editor commands must go through the view (applyEdit) instead of the `value` prop.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, act } from '@testing-library/react';
import { createRef } from 'react';
import { EditorView } from '@codemirror/view';
import { foldedRanges } from '@codemirror/language';
import CodeEditor from './CodeEditor';

function mount(value) {
  const ref = createRef();
  const onChange = vi.fn();
  const utils = render(<CodeEditor ref={ref} value={value} onChange={onChange} />);
  const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
  return { ref, onChange, view, ...utils };
}

describe('CodeEditor on real CodeMirror', () => {
  it('applyEdit applies the text and selection in one normal transaction that fires onChange', () => {
    const { ref, onChange, view } = mount('hello world');
    act(() => { expect(ref.current.applyEdit('**hello** world', 2, 7)).toBe(true); });
    expect(view.state.doc.toString()).toBe('**hello** world');
    expect(ref.current.getSelection()).toEqual({ selStart: 2, selEnd: 7 });
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toBe('**hello** world');
  });

  it('applyEdit lands immediately even inside the typing latch, and later typing is not overwritten', async () => {
    const { ref, view } = mount('hello world');
    act(() => { view.dispatch({ changes: { from: 11, insert: ' x' } }); }); // a keystroke arms the latch
    act(() => { ref.current.applyEdit('**hello** world x', 2, 7); });
    expect(view.state.doc.toString()).toBe('**hello** world x');
    act(() => { view.dispatch({ changes: { from: view.state.doc.length, insert: 'Z' } }); });
    await act(async () => { await new Promise((r) => setTimeout(r, 900)); }); // let the latch expire
    expect(view.state.doc.toString()).toBe('**hello** world xZ');
  });

  it('applyEdit keeps the caret mapped when only part of the document changes', () => {
    const { ref, view } = mount('alpha\nbeta\ngamma');
    act(() => { ref.current.applyEdit('alpha\n## beta\ngamma', 14, 14); });
    expect(view.state.doc.toString()).toBe('alpha\n## beta\ngamma');
    expect(ref.current.getSelection()).toEqual({ selStart: 14, selEnd: 14 });
  });

  it('replaceRange reveals a fold that contains the replaced range', () => {
    const { ref, view } = mount('# One\n\nhidden phrase here\n\n# Two\n\nvisible\n');
    act(() => { ref.current.foldAll(); });
    expect(foldedRanges(view.state).size).toBeGreaterThan(0);
    const at = view.state.doc.toString().indexOf('phrase');
    act(() => { ref.current.replaceRange(at, at + 6, '[phrase](P)'); });
    expect(view.state.doc.toString()).toContain('hidden [phrase](P) here');
    let foldedOverEdit = false;
    foldedRanges(view.state).between(0, view.state.doc.length, (from, to) => {
      if (from <= at && to >= at) foldedOverEdit = true;
    });
    expect(foldedOverEdit).toBe(false);
  });
});
