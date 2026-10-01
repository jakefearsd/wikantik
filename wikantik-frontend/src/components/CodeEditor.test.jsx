/**
 * #19 — CodeEditor imperative-API tests.
 *
 * @uiw/react-codemirror is stubbed with a <textarea>-backed CodeMirror view so
 * the imperative handle (getSelection / setSelection / focus) can be exercised
 * deterministically under happy-dom, which cannot run CodeMirror's real
 * contenteditable/measuring layer. The stub backs the view with the textarea's
 * real selectionStart/End + value.length, so these assertions verify the REAL
 * CodeEditor handle logic (offset mapping, clamping), not mock behavior. Real
 * CodeMirror integration is verified by `npm run build` + manual testing.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import { createRef } from 'react';

// Exposed so tests can inspect/mutate the last-created stub view directly
// (scrollDOM.scrollTop, throw-flags) without going through the imperative
// handle — needed for getViewport/scrollToLine/jumpToLineAligned coverage.
let lastView = null;

function lineStarts(text) {
  const lines = text.split('\n');
  const starts = [];
  let offset = 0;
  for (const l of lines) {
    starts.push(offset);
    offset += l.length + 1;
  }
  return starts;
}

function lineNumberForOffset(text, pos) {
  const starts = lineStarts(text);
  let n = 1;
  for (let i = 0; i < starts.length; i++) {
    if (starts[i] <= pos) n = i + 1;
  }
  return n;
}

vi.mock('@uiw/react-codemirror', async () => {
  const React = (await vi.importActual('react')).default;
  function makeView(ta, onChange) {
    const view = {
      get state() {
        const starts = lineStarts(ta.value);
        return {
          selection: { main: { from: ta.selectionStart, to: ta.selectionEnd, head: ta.selectionEnd } },
          sliceDoc: (from, to) => ta.value.slice(from, to),
          doc: {
            length: ta.value.length,
            lines: starts.length,
            line(n) {
              const clamped = Math.max(1, Math.min(n, starts.length));
              return { from: starts[clamped - 1] };
            },
            lineAt(pos) {
              const number = lineNumberForOffset(ta.value, pos);
              return { number, from: starts[number - 1] };
            },
          },
        };
      },
      scrollDOM: {
        scrollTop: 0,
        getBoundingClientRect: () => ({ top: 20 }),
      },
      focus() { ta.focus(); },
      dispatch(tr) {
        if (tr && tr.changes) {
          // Apply {from, to?, insert} like a real (non-external) transaction: doc changes, onChange fires.
          const { from, to = from, insert = '' } = tr.changes;
          const next = ta.value.slice(0, from) + insert + ta.value.slice(to);
          ta.value = next;
          if (onChange) onChange(next);
        }
        if (tr && tr.selection) {
          ta.setSelectionRange(tr.selection.anchor, tr.selection.head);
        }
      },
      lineBlockAtHeight(top) {
        if (ta.__throwLineBlockAtHeight) throw new Error('boom');
        const total = view.state.doc.lines;
        const lineNum = Math.max(1, Math.min(total, Math.floor(top / 10) + 1));
        return { from: view.state.doc.line(lineNum).from };
      },
      lineBlockAt(pos) {
        if (ta.__throwLineBlockAt) throw new Error('boom');
        const lineNum = lineNumberForOffset(ta.value, pos);
        return { top: (lineNum - 1) * 10 };
      },
      coordsAtPos(pos) {
        if (ta.__throwCoordsAtPos) throw new Error('boom');
        if (ta.__coordsAtPosNull) return null;
        const lineNum = lineNumberForOffset(ta.value, pos);
        return { top: 20 + (lineNum - 1) * 10 };
      },
    };
    lastView = view;
    return view;
  }
  return {
    default: function CodeMirrorStub({ value, onChange, onCreateEditor }) {
      return React.createElement('textarea', {
        ref: (ta) => {
          if (ta && onCreateEditor && value !== '__NO_VIEW__') onCreateEditor(makeView(ta, onChange));
        },
        'data-testid': 'cm-stub-textarea',
        value: value || '',
        onChange: (e) => onChange && onChange(e.target.value),
      });
    },
  };
});

import CodeEditor from './CodeEditor';


function mount(value = 'hello world') {
  const ref = createRef();
  const { getByTestId } = render(
    <CodeEditor ref={ref} value={value} onChange={() => {}} data-testid="editor-textarea" />,
  );
  return { ref, ta: getByTestId('cm-stub-textarea') };
}

describe('CodeEditor insertText / replaceText (upload edits go through the editor)', () => {
  function mountWith(value) {
    const ref = createRef();
    const onChange = vi.fn();
    render(<CodeEditor ref={ref} value={value} onChange={onChange} />);
    return { ref, onChange };
  }

  it('insertText dispatches an insertion at pos, fires onChange, and reports handled', () => {
    const { ref, onChange } = mountWith('Hello world');
    expect(ref.current.insertText(5, '[X]')).toBe(true);
    expect(onChange).toHaveBeenCalledWith('Hello[X] world');
  });

  it('insertText clamps pos into the document', () => {
    const { ref, onChange } = mountWith('abc');
    expect(ref.current.insertText(99, '!')).toBe(true);
    expect(onChange).toHaveBeenCalledWith('abc!');
  });

  it('replaceText swaps the first exact occurrence and fires onChange', () => {
    const { ref, onChange } = mountWith('a [P] b [P]');
    expect(ref.current.replaceText('[P]', '[Q]')).toBe(true);
    expect(onChange).toHaveBeenCalledWith('a [Q] b [P]');
  });

  it('replaceText treats $ in the replacement literally', () => {
    const { ref, onChange } = mountWith('a [P] b');
    ref.current.replaceText('[P]', '$& $1');
    expect(onChange).toHaveBeenCalledWith('a $& $1 b');
  });

  it('replaceText is a handled no-op when the text is gone (user deleted the placeholder)', () => {
    const { ref, onChange } = mountWith('nothing here');
    expect(ref.current.replaceText('[P]', '[Q]')).toBe(true);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('both return false when there is no editor view', () => {
    const { ref } = mountWith('__NO_VIEW__');
    expect(ref.current.insertText(0, 'x')).toBe(false);
    expect(ref.current.replaceText('a', 'b')).toBe(false);
  });
});

describe('#19 CodeEditor imperative API', () => {
  it('renders the wrapper with the forwarded data-testid', () => {
    const { getByTestId } = render(
      <CodeEditor value="x" onChange={() => {}} data-testid="editor-textarea" />,
    );
    expect(getByTestId('editor-textarea')).toBeInTheDocument();
  });

  it('getSelection returns the current character offsets', () => {
    const { ref, ta } = mount('hello world');
    ta.setSelectionRange(6, 11);
    expect(ref.current.getSelection()).toEqual({ selStart: 6, selEnd: 11 });
  });

  it('getCursor returns line, column and selected text', () => {
    const { ref, ta } = mount('abc\ndefgh');
    ta.setSelectionRange(8, 8);
    expect(ref.current.getCursor()).toEqual({ line: 2, col: 5, selectionText: '' });
    ta.setSelectionRange(0, 3);
    expect(ref.current.getCursor()).toMatchObject({ selectionText: 'abc' });
  });

  it('getSelection returns an object with selStart/selEnd shape', () => {
    const ref = createRef();
    render(<CodeEditor ref={ref} value="" onChange={() => {}} />);
    const sel = ref.current.getSelection();
    expect(sel).toHaveProperty('selStart');
    expect(sel).toHaveProperty('selEnd');
  });

  it('setSelection sets the selection range on the view', () => {
    const { ref, ta } = mount('abcdefgh');
    ref.current.setSelection(2, 5);
    expect(ta.selectionStart).toBe(2);
    expect(ta.selectionEnd).toBe(5);
  });

  it('setSelection clamps offsets to the document length', () => {
    const { ref, ta } = mount('abc');
    ref.current.setSelection(-5, 999);
    expect(ta.selectionStart).toBe(0);
    expect(ta.selectionEnd).toBe(3);
  });

  it('focus() focuses the editor without throwing', () => {
    const { ref } = mount('text');
    expect(() => ref.current.focus()).not.toThrow();
  });
});

describe('#19 CodeEditor getViewport / scrollToLine / getScrollerRect', () => {
  const multiline = 'l1\nl2\nl3\nl4\nl5';

  it('getViewport returns the top-visible line + total line count', () => {
    const { ref } = mount(multiline);
    lastView.scrollDOM.scrollTop = 30; // -> line 4 per the stub's 10px-per-line model
    expect(ref.current.getViewport()).toEqual({ topLine: 4, totalLines: 5 });
  });

  it('getViewport falls back to topLine=1 when lineBlockAtHeight throws', () => {
    const { ref, ta } = mount(multiline);
    ta.__throwLineBlockAtHeight = true;
    expect(ref.current.getViewport()).toEqual({ topLine: 1, totalLines: 5 });
  });

  it('getViewport returns null when the view has not been created', () => {
    const ref = createRef();
    render(<CodeEditor ref={ref} value="__NO_VIEW__" onChange={() => {}} />);
    expect(ref.current.getViewport()).toBeNull();
  });

  it('scrollToLine sets scrollTop from the target line block', () => {
    const { ref } = mount(multiline);
    ref.current.scrollToLine(3);
    expect(lastView.scrollDOM.scrollTop).toBe(20);
  });

  it('scrollToLine clamps above the last line', () => {
    const { ref } = mount(multiline);
    ref.current.scrollToLine(999);
    expect(lastView.scrollDOM.scrollTop).toBe(40); // line 5 -> (5-1)*10
  });

  it('scrollToLine clamps below the first line', () => {
    const { ref } = mount(multiline);
    ref.current.scrollToLine(-10);
    expect(lastView.scrollDOM.scrollTop).toBe(0); // line 1
  });

  it('scrollToLine swallows errors from lineBlockAt', () => {
    const { ref, ta } = mount(multiline);
    ta.__throwLineBlockAt = true;
    expect(() => ref.current.scrollToLine(2)).not.toThrow();
  });

  it('scrollToLine is a no-op when the view has not been created', () => {
    const ref = createRef();
    render(<CodeEditor ref={ref} value="__NO_VIEW__" onChange={() => {}} />);
    expect(() => ref.current.scrollToLine(2)).not.toThrow();
  });

  it('getScrollerRect returns the scroller bounding rect', () => {
    const { ref } = mount(multiline);
    expect(ref.current.getScrollerRect()).toEqual({ top: 20 });
  });

  it('getScrollerRect returns null when the view has not been created', () => {
    const ref = createRef();
    render(<CodeEditor ref={ref} value="__NO_VIEW__" onChange={() => {}} />);
    expect(ref.current.getScrollerRect()).toBeNull();
  });
});

describe('#19 CodeEditor jumpToLineAligned', () => {
  const multiline = 'l1\nl2\nl3\nl4\nl5';
  let rafSpy;

  beforeEach(() => {
    // Run the rAF callback synchronously so the two-step alignment completes
    // within the test instead of racing the assertion.
    rafSpy = vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation((cb) => {
      cb();
      return 0;
    });
  });
  afterEach(() => {
    rafSpy.mockRestore();
  });

  it('places the caret, does a rough scroll, then refines using coordsAtPos', () => {
    const { ref } = mount(multiline);
    ref.current.jumpToLineAligned(3, 5);
    // Rough target: lineBlockAt(line 3).top(20) - offset(5) = 15
    // Refined: coordsAtPos top (20 + 20) - scrollerTop(20) + scrollTop(15) - offset(5) = 30
    expect(lastView.scrollDOM.scrollTop).toBe(30);
  });

  it('clamps the requested line to the document bounds', () => {
    const { ref } = mount(multiline);
    expect(() => ref.current.jumpToLineAligned(999, 0)).not.toThrow();
    expect(() => ref.current.jumpToLineAligned(-5, 0)).not.toThrow();
  });

  it('skips the refinement step when coordsAtPos returns null', () => {
    const { ref, ta } = mount(multiline);
    ta.__coordsAtPosNull = true;
    ref.current.jumpToLineAligned(3, 0);
    // Only the rough scroll applied: lineBlockAt(line 3).top(20) - 0
    expect(lastView.scrollDOM.scrollTop).toBe(20);
  });

  it('swallows errors from the rough-scroll step (lineBlockAt throws)', () => {
    const { ref, ta } = mount(multiline);
    ta.__throwLineBlockAt = true;
    expect(() => ref.current.jumpToLineAligned(2, 0)).not.toThrow();
  });

  it('swallows errors from the refine step (coordsAtPos throws)', () => {
    const { ref, ta } = mount(multiline);
    ta.__throwCoordsAtPos = true;
    expect(() => ref.current.jumpToLineAligned(2, 0)).not.toThrow();
  });

  it('is a no-op when the view has not been created', () => {
    const ref = createRef();
    render(<CodeEditor ref={ref} value="__NO_VIEW__" onChange={() => {}} />);
    expect(() => ref.current.jumpToLineAligned(2, 0)).not.toThrow();
  });
});

describe('CodeEditor slashSource prop', () => {
  it('accepts a slash completion source without error', () => {
    const slashSource = vi.fn(() => null);
    const { getByTestId } = render(
      <CodeEditor data-testid="ce" value="x" onChange={() => {}} slashSource={slashSource} />,
    );
    expect(getByTestId('ce')).toBeInTheDocument();
  });
});
