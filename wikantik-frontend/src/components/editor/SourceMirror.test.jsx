import { describe, it, expect } from 'vitest';
import { render, act } from '@testing-library/react';
import { EditorView } from '@codemirror/view';
import SourceMirror, { CaretSourceMirror } from './SourceMirror';
import { createEditorCursorStore } from '../../utils/editorCursorStore';

const TEXT = '# Title\n\nSome **bold** text.\n\n- item';
const lines = (container) => [...container.querySelectorAll('.cm-line')];
const highlighted = (container) => lines(container)
  .map((el, i) => (el.classList.contains('cm-mirror-caret-line') ? i + 1 : 0)).filter(Boolean);
const viewOf = (container) => EditorView.findFromDOM(container.querySelector('.cm-editor'));

describe('SourceMirror', () => {
  it('renders the Markdown text as it is written', () => {
    const { container } = render(<SourceMirror text={TEXT} line={1} />);
    expect(lines(container).map((el) => el.textContent)).toEqual(['# Title', '', 'Some **bold** text.', '', '- item']);
  });

  it('marks the caret line, and only that line', () => {
    const { container } = render(<SourceMirror text={TEXT} line={3} />);
    expect(highlighted(container)).toEqual([3]);
  });

  it('moves the mark when the line changes', () => {
    const { container, rerender } = render(<SourceMirror text={TEXT} line={3} />);
    rerender(<SourceMirror text={TEXT} line={5} />);
    expect(highlighted(container)).toEqual([5]);
  });

  it('a line past the end marks the last line; a missing line marks the first', () => {
    const { container, rerender } = render(<SourceMirror text={TEXT} line={99} />);
    expect(highlighted(container)).toEqual([5]);
    rerender(<SourceMirror text={TEXT} line={undefined} />);
    expect(highlighted(container)).toEqual([1]);
  });

  it('follows a changed text with one minimal change, keeping the same view', () => {
    const { container, rerender } = render(<SourceMirror text={TEXT} line={3} />);
    const view = viewOf(container);
    const changes = [];
    const dispatch = view.dispatch.bind(view);
    view.dispatch = (...specs) => {
      for (const spec of specs) if (spec.changes) changes.push(spec.changes);
      dispatch(...specs);
    };
    rerender(<SourceMirror text={TEXT.replace('bold', 'bolder')} line={3} />);
    expect(viewOf(container)).toBe(view);
    expect(view.state.doc.toString()).toBe(TEXT.replace('bold', 'bolder'));
    expect(changes).toEqual([{ from: 20, to: 20, insert: 'er' }]);
    expect(highlighted(container)).toEqual([3]);
  });

  it('is read-only and labelled', () => {
    const { container } = render(<SourceMirror text={TEXT} line={1} />);
    const content = container.querySelector('.cm-content');
    expect(content).toHaveAttribute('aria-label', 'Markdown source (read-only)');
    expect(content).toHaveAttribute('contenteditable', 'false');
    expect(viewOf(container).state.readOnly).toBe(true);
  });

  it('marks Markdown markers so they stand out', () => {
    const { container } = render(<SourceMirror text={TEXT} line={1} />);
    const marks = [...container.querySelectorAll('.cm-mirror-mark')].map((el) => el.textContent);
    expect(marks).toEqual(['#', '**', '**', '-']);
  });

  it('destroys its view on unmount', () => {
    const { container, unmount } = render(<SourceMirror text={TEXT} line={1} />);
    const host = container.querySelector('.editor-source-mirror');
    unmount();
    expect(host.querySelector('.cm-editor')).toBeNull();
  });
});

describe('CaretSourceMirror', () => {
  it('marks the line of the editor caret, and follows it', () => {
    const store = createEditorCursorStore();
    store.set({ line: 3, col: 1 });
    const { container } = render(<CaretSourceMirror text={TEXT} cursorStore={store} />);
    expect(highlighted(container)).toEqual([3]);
    act(() => store.set({ line: 5, col: 2 }));
    expect(highlighted(container)).toEqual([5]);
  });

  it('counts the caret line without the lines the text leaves out', () => {
    const store = createEditorCursorStore();
    store.set({ line: 7, col: 1 }); // four frontmatter lines, then the third line of the shown text
    const { container } = render(<CaretSourceMirror text={TEXT} cursorStore={store} lineOffset={4} />);
    expect(highlighted(container)).toEqual([3]);
  });

  it('a caret move within the line does not touch the mirror', () => {
    const store = createEditorCursorStore();
    store.set({ line: 3, col: 1 });
    const { container } = render(<CaretSourceMirror text={TEXT} cursorStore={store} />);
    const view = viewOf(container);
    let dispatched = 0;
    const dispatch = view.dispatch.bind(view);
    view.dispatch = (...specs) => { dispatched += 1; dispatch(...specs); };
    act(() => store.set({ col: 9, selectionText: 'x', topLine: 2 }));
    expect(dispatched).toBe(0);
  });
});
