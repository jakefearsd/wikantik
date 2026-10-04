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
import { StateEffect } from '@codemirror/state';
import { foldable, foldEffect, foldedRanges, syntaxTree } from '@codemirror/language';
import { undo } from '@codemirror/commands';
import { linkAt } from '../utils/linkInteraction';
import CodeEditor, { minimalChange } from './CodeEditor';
import { parseFully } from '../test/fullParse';

function mount(value) {
  const ref = createRef();
  const onChange = vi.fn();
  const utils = render(<CodeEditor ref={ref} value={value} onChange={onChange} />);
  const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
  return { ref, onChange, view, ...utils };
}

describe('CodeEditor on real CodeMirror', () => {
  it('parses GitHub-flavoured markdown (task lists, strikethrough, tables)', () => {
    const { view } = mount('- [ ] todo ~~gone~~\n\n| a | b |\n|---|---|\n| 1 | 2 |\n');
    parseFully(view);
    const names = new Set();
    syntaxTree(view.state).iterate({ enter: (n) => { names.add(n.name); } });
    expect([...names]).toEqual(expect.arrayContaining(['Task', 'TaskMarker', 'Strikethrough', 'Table']));
  });

  it('a re-render with unchanged config props does not reconfigure the editor (no per-keystroke reconfigure)', () => {
    const onChange = vi.fn();
    const utils = render(<CodeEditor value="a" onChange={onChange} />);
    const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
    let reconfigures = 0;
    const dispatch = view.dispatch.bind(view);
    view.dispatch = (...specs) => {
      if (specs.some((sp) => [].concat(sp?.effects || []).some((e) => e.is(StateEffect.reconfigure)))) reconfigures += 1;
      return dispatch(...specs);
    };
    utils.rerender(<CodeEditor value="a" onChange={onChange} />);
    utils.rerender(<CodeEditor value="a" onChange={onChange} />);
    expect(reconfigures).toBe(0);
    utils.rerender(<CodeEditor value="a" onChange={onChange} dark />); // a real config change still reconfigures
    expect(reconfigures).toBe(1);
  });

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

  it('applyChanges skips (and warns about) a change that overlaps an earlier one', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const { ref, view } = mount('0123456789');
      act(() => {
        expect(ref.current.applyChanges([{ from: 3, to: 8, insert: 'Y' }, { from: 0, to: 5, insert: 'X' }, { from: 9, to: 10, insert: 'Z' }])).toBe(true);
      });
      expect(view.state.doc.toString()).toBe('X5678Z');   // sorted: [0,5) applied, [3,8) overlaps it -> skipped
      expect(warn).toHaveBeenCalledTimes(1);
      expect(String(warn.mock.calls[0][0])).toContain('overlap');
    } finally {
      warn.mockRestore();
    }
  });

  it('applyChanges normalises CRLF in inserted text, and a minimalChange of LF vs CRLF text adds no blank line', () => {
    const { ref, view } = mount('x\nfoo\nEND');
    act(() => { ref.current.applyChanges([minimalChange('x\nfoo\nEND', 'x\r\nbar\r\nEND')]); });
    expect(view.state.doc.toString()).toBe('x\nbar\nEND');
    act(() => { ref.current.applyChanges([{ from: 0, to: 1, insert: 'p\r\nq\rr' }]); });
    expect(view.state.doc.toString()).toBe('p\nq\nr\nbar\nEND');
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

describe('CodeEditor live preview', () => {
  const liveMount = (value, props = {}) => {
    const ref = createRef();
    const utils = render(<CodeEditor ref={ref} value={value} onChange={vi.fn()} {...props} />);
    const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
    return { ref, view, ...utils };
  };
  const firstLine = (view) => view.contentDOM.querySelector('.cm-line').textContent;
  it('starts live when mounted with livePreview, and the toggle keeps the same view, doc and selection', () => {
    const { view, rerender } = liveMount('**a**\n\nend', { livePreview: true });
    act(() => { view.dispatch({ selection: { anchor: 9 } }); });
    expect(firstLine(view)).toBe('a');
    rerender(<CodeEditor value={'**a**\n\nend'} onChange={vi.fn()} livePreview={false} />);
    expect(EditorView.findFromDOM(document.querySelector('.cm-editor'))).toBe(view);
    expect(firstLine(view)).toBe('**a**');
    expect(view.state.doc.toString()).toBe('**a**\n\nend');
    expect(view.state.selection.main.head).toBe(9);
  });
  it('stays live across a re-render that reconfigures the editor (theme change)', () => {
    const { view, rerender } = liveMount('**a**\n\nend', { livePreview: true });
    act(() => { view.dispatch({ selection: { anchor: 9 } }); });
    rerender(<CodeEditor value={'**a**\n\nend'} onChange={vi.fn()} livePreview dark />);
    expect(view.dom.classList.contains('cm-live-preview')).toBe(true);
    expect(firstLine(view)).toBe('a');
  });
  it('reconfigure while live leaves document, selection and undo history unchanged', () => {
    const { view, rerender } = liveMount('hello\n\nend', { livePreview: true });
    act(() => { view.dispatch({ changes: { from: 5, insert: ' world' }, selection: { anchor: 11 }, userEvent: 'input.type' }); });
    rerender(<CodeEditor value={'hello world\n\nend'} onChange={vi.fn()} livePreview dark />);
    rerender(<CodeEditor value={'hello world\n\nend'} onChange={vi.fn()} livePreview />);
    expect(view.state.doc.toString()).toBe('hello world\n\nend');
    expect(view.state.selection.main.head).toBe(11);
    expect(view.dom.classList.contains('cm-live-preview')).toBe(true);
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('hello\n\nend');
  });
  it('undo across a live toggle restores the text and keeps the mode', () => {
    const { view, rerender } = liveMount('hello\n\nend');
    act(() => { view.dispatch({ changes: { from: 5, insert: ' world' }, selection: { anchor: 11 }, userEvent: 'input.type' }); });
    rerender(<CodeEditor value={'hello world\n\nend'} onChange={vi.fn()} livePreview />);
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('hello\n\nend');
    expect(view.dom.classList.contains('cm-live-preview')).toBe(true);
  });
  it('keeps folds through a toggle', () => {
    const { view, rerender } = liveMount('# A\none\ntwo\n# B\nthree');
    act(() => { view.dispatch({ effects: foldEffect.of({ from: 3, to: 11 }) }); });
    rerender(<CodeEditor value={'# A\none\ntwo\n# B\nthree'} onChange={vi.fn()} livePreview />);
    expect(foldedRanges(view.state).size).toBe(1);
  });
  it('Ctrl-hover link marks still wrap the visible link text in live mode', () => {
    const { view } = liveMount('see [the hub](IndexFundsHub) now\n\nend', { livePreview: true });
    act(() => { view.dispatch({ selection: { anchor: view.state.doc.length } }); });
    expect([...view.contentDOM.querySelectorAll('.cm-link-range')].map((e) => e.textContent).join('')).toBe('the hub');
    expect(linkAt(view.state, 6).url).toBe('IndexFundsHub');
  });
  it('a new livePreviewContext refreshes widgets (attachment list arrives late)', () => {
    const doc = '![a](pic.png)\n\nend';
    const { view, rerender } = liveMount(doc, { livePreview: true, livePreviewContext: { pageName: 'P', attachments: [] } });
    act(() => { view.dispatch({ selection: { anchor: doc.length } }); });
    expect(view.dom.querySelector('img.cm-lp-image').getAttribute('src')).toBe('pic.png');
    rerender(<CodeEditor value={doc} onChange={vi.fn()} livePreview livePreviewContext={{ pageName: 'P', attachments: ['pic.png'] }} />);
    expect(view.dom.querySelector('img.cm-lp-image').getAttribute('src')).toBe('/attach/P/pic.png');
  });
});
