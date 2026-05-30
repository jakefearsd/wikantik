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
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import { createRef } from 'react';

vi.mock('@uiw/react-codemirror', async () => {
  const React = (await vi.importActual('react')).default;
  function makeView(ta) {
    return {
      get state() {
        return {
          selection: { main: { from: ta.selectionStart, to: ta.selectionEnd } },
          doc: { length: ta.value.length },
        };
      },
      focus() { ta.focus(); },
      dispatch(tr) {
        if (tr && tr.selection) {
          ta.setSelectionRange(tr.selection.anchor, tr.selection.head);
        }
      },
    };
  }
  return {
    default: function CodeMirrorStub({ value, onChange, onCreateEditor }) {
      return React.createElement('textarea', {
        ref: (ta) => { if (ta && onCreateEditor) onCreateEditor(makeView(ta)); },
        'data-testid': 'cm-stub-textarea',
        value: value || '',
        onChange: (e) => onChange && onChange(e.target.value),
      });
    },
  };
});

import CodeEditor from './CodeEditor';

afterEach(() => cleanup());

function mount(value = 'hello world') {
  const ref = createRef();
  const { getByTestId } = render(
    <CodeEditor ref={ref} value={value} onChange={() => {}} data-testid="editor-textarea" />,
  );
  return { ref, ta: getByTestId('cm-stub-textarea') };
}

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
