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
import { foldable, foldedRanges } from '@codemirror/language';
import { undo } from '@codemirror/commands';
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

  it('applyChanges applies several changes (all against the current doc) in one transaction that fires onChange once', () => {
    const { ref, onChange, view } = mount('![a](old.png) and [b](old.png)');
    act(() => {
      expect(ref.current.applyChanges([
        { from: 5, to: 12, insert: 'new.png' },
        { from: 22, to: 29, insert: 'new.png' },
      ])).toBe(true);
    });
    expect(view.state.doc.toString()).toBe('![a](new.png) and [b](new.png)');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toBe('![a](new.png) and [b](new.png)');
  });

  it('applyChanges maps the caret through the changes instead of moving it', () => {
    const { ref, view } = mount('[a](x.png) typing here');
    act(() => { view.dispatch({ selection: { anchor: 15 } }); });     // caret inside "typing"
    act(() => { ref.current.applyChanges([{ from: 4, to: 9, insert: 'longer-name.png' }]); });
    expect(view.state.doc.toString()).toBe('[a](longer-name.png) typing here');
    expect(view.state.selection.main.head).toBe(25);                   // shifted by the +10 growth
    act(() => { view.dispatch({ selection: { anchor: 2, head: 30 } }); }); // a selection spanning a change
    act(() => { ref.current.applyChanges([{ from: 4, to: 19, insert: 'y.png' }]); });
    expect(view.state.selection.main.from).toBe(2);
    expect(view.state.selection.main.to).toBe(20);
  });

  it('applyChanges clamps out-of-range positions and orders a reversed range', () => {
    const { ref, view } = mount('abc');
    act(() => { ref.current.applyChanges([{ from: 99, to: 120, insert: '!' }, { from: -5, to: -1, insert: '>' }]); });
    expect(view.state.doc.toString()).toBe('>abc!');
    act(() => { ref.current.applyChanges([{ from: 3, to: 1, insert: 'X' }]); });
    expect(view.state.doc.toString()).toBe('>Xc!');
  });

  it('applyChanges with no changes is a handled no-op', () => {
    const { ref, onChange, view } = mount('abc');
    act(() => { expect(ref.current.applyChanges([])).toBe(true); });
    expect(view.state.doc.toString()).toBe('abc');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('applyChanges is its own undo step, never merged with typing just before or after it', () => {
    const { ref, view } = mount('hello');
    act(() => { view.dispatch({ changes: { from: 5, insert: ' w' }, selection: { anchor: 7 }, userEvent: 'input.type' }); });
    act(() => { ref.current.applyChanges([{ from: 0, to: 7, insert: 'HELLO W' }]); });
    act(() => { view.dispatch({ changes: { from: 7, insert: 'x' }, selection: { anchor: 8 }, userEvent: 'input.type' }); });
    expect(view.state.doc.toString()).toBe('HELLO Wx');
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('HELLO W');
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('hello w');
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

  it('marks a callout [!type] in the source as a callout marker, not a link', () => {
    const { container } = mount('> [!warning]- Careful\n> body\n');
    const marker = container.querySelector('.cm-callout-marker');
    expect(marker).not.toBeNull();
    expect(marker.textContent).toBe('[!warning]-');
    expect(marker.classList.contains('cm-callout-marker-warning')).toBe(true);
  });

  it('offers fold markers only on headings, frontmatter and fences; folds show a ⋯ placeholder', () => {
    const doc = '---\ntitle: x\n---\n# One\npara one\npara two\n\n> q one\n> q two\n\n# Two\nend\n';
    const { ref, view, container } = mount(doc);
    const lineNo = (text) => view.state.doc.toString().split('\n').indexOf(text) + 1;
    const foldableAt = (text) => {
      const line = view.state.doc.line(lineNo(text));
      return foldable(view.state, line.from, line.to);
    };
    expect(foldableAt('para one')).toBeNull();
    expect(foldableAt('> q one')).toBeNull();
    expect(foldableAt('title: x')).toBeNull();
    expect(foldableAt('# One')).not.toBeNull();
    expect(foldableAt('---')).not.toBeNull();
    act(() => { ref.current.foldAll(); });
    const placeholder = container.querySelector('.cm-foldPlaceholder');
    expect(placeholder).not.toBeNull();
    expect(placeholder.textContent).toBe('⋯');
  });
});
