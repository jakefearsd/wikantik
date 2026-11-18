/**
 * A sticky range is a line marker only when its spec says so (`lineMarker: true`), not because it happens to start
 * the line. No inline construct is sticky yet, so this file adds one through a mock of the spec builder: the `**`
 * that opens a line, or follows a heading marker, is emitted as a sticky hide with no lineMarker flag.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';

vi.mock('./ranges', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    livePreviewSpecs: (state, activeLines, options = {}) => {
      const specs = actual.livePreviewSpecs(state, activeLines, options);
      const { from = 0, to = state.doc.length } = options;
      const extra = [];
      for (let n = state.doc.lineAt(from).number; n <= state.doc.lineAt(to).number; n += 1) {
        const line = state.doc.line(n);
        const at = line.text.startsWith('**') ? 0 : line.text.startsWith('## **') ? 3 : -1;
        if (at >= 0) extra.push({ kind: 'hide', from: line.from + at, to: line.from + at + 2, sticky: true });
      }
      // The real builder hides the same `**` off the caret's line: keep one replacing spec per range.
      return [...specs.filter((s) => !extra.some((e) => s.kind === 'hide' && s.from === e.from)), ...extra].sort((a, b) => a.from - b.from || a.to - b.to);
    },
  };
});

import { EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { stickyRangesOf, removeLineMarker, leaveLineMarker } from './sticky';

const DOC = 'Para\n**bold** x\n## **b** t';
const B = DOC.indexOf('**bold'); // 5
const H = DOC.indexOf('## '); // heading line: `## ` is a line marker, the `**` after it is not

const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount() {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc: DOC, extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => ({ pageName: 'P', attachments: [] }) }), keymap.of(defaultKeymap)] }),
  });
  views.push(view);
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  setLivePreview(view, true);
  return view;
}
const caretAt = (view, pos) => view.dispatch({ selection: { anchor: pos } });
const head = (view) => view.state.selection.main.head;

describe('a sticky range that starts a line but is not a line marker', () => {
  it('is sticky (the mock took effect) and atomic', () => {
    const view = mount();
    caretAt(view, 0);
    const ranges = [];
    for (const it = stickyRangesOf(view).iter(); it.value; it.next()) ranges.push([it.from, it.to]);
    expect(ranges).toContainEqual([B, B + 2]);
    caretAt(view, B + 1);
    expect([B, B + 2]).toContain(head(view)); // never inside it
  });

  it('the caret may rest before it, at the line start', () => {
    const view = mount();
    caretAt(view, B);
    expect(head(view)).toBe(B);
  });

  it('Backspace right after it is not the marker command, and ArrowLeft is not the leave-the-line command', () => {
    const view = mount();
    caretAt(view, B + 2);
    expect(head(view)).toBe(B + 2);
    expect(removeLineMarker(view)).toBe(false);
    expect(leaveLineMarker(view)).toBe(false);
    expect(view.state.doc.toString()).toBe(DOC);
  });

  it('after a real line marker it does not extend the marker run: the caret rests after `## `, and Backspace there removes `## ` only', () => {
    const view = mount();
    caretAt(view, H);
    expect(head(view)).toBe(H + 3);
    caretAt(view, H + 5);
    expect(removeLineMarker(view)).toBe(false);
    caretAt(view, H + 3);
    expect(removeLineMarker(view)).toBe(true);
    expect(view.state.doc.toString()).toBe('Para\n**bold** x\n**b** t');
  });
});
