/**
 * Sticky ranges on a real EditorView: a sticky decoration stays hidden while the caret is on its line, the caret
 * cannot rest before or inside it, and Backspace right after a line-start one removes exactly its text. Nothing is
 * sticky in production yet, so these tests turn ATX heading markers sticky through the `stickyKinds` context seam.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { livePreviewPlugin } from './plugin';
import { livePreviewSpecs, activeLinesOf } from './ranges';
import { stickyRangesOf, removeLineMarker, leaveLineMarker } from './sticky';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';

const stickyHeadings = { pageName: 'Sticky', attachments: [], stickyKinds: ['heading'] };
const DOC = 'Para one\n## Title\nAfter';
const H = DOC.indexOf('## '); // heading line start (9); its marker `## ` is [H, H + 3)

const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount(doc = DOC, { live = true, context = stickyHeadings, extra = [] } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => context }), keymap.of(defaultKeymap), extra],
    }),
  });
  views.push(view);
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  if (live) setLivePreview(view, true);
  return view;
}

function press(view, key, init = {}) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
  view.contentDOM.dispatchEvent(event);
  return event.defaultPrevented;
}
const caretAt = (view, pos) => view.dispatch({ selection: { anchor: pos } });
const head = (view) => view.state.selection.main.head;
const text = (view) => view.state.doc.toString();
const type = (view, ch) => {
  const pos = head(view);
  view.dispatch({ changes: { from: pos, insert: ch }, selection: { anchor: pos + ch.length }, userEvent: 'input.type' });
};
const rangesOf = (set) => {
  const out = [];
  for (const it = set.iter(); it.value; it.next()) out.push([it.from, it.to]);
  return out;
};
const hiddenRanges = (view) => {
  const out = [];
  for (const it = view.plugin(livePreviewPlugin).decorations.iter(); it.value; it.next()) {
    if (it.to > it.from && !it.value.spec.class) out.push([it.from, it.to]);
  }
  return out;
};

describe('sticky specs', () => {
  it('a sticky spec is emitted on an active line and carries sticky: true; a non-sticky one is not emitted there', () => {
    const view = mount();
    caretAt(view, H + 5);
    const active = activeLinesOf(view.state);
    const at = (context) => livePreviewSpecs(view.state, active, { context }).filter((s) => s.kind === 'hide' && s.from === H);

    expect(at(stickyHeadings)).toEqual([expect.objectContaining({ from: H, to: H + 3, sticky: true })]);
    expect(at({ pageName: 'Sticky', attachments: [] })).toEqual([]);
  });

  it('without the seam no spec carries a sticky flag at all (production emits none)', () => {
    const view = mount(DOC, { context: { pageName: 'Sticky', attachments: [] } });
    caretAt(view, 0);
    const specs = livePreviewSpecs(view.state, activeLinesOf(view.state), { context: { pageName: 'Sticky', attachments: [] } });
    expect(specs.some((s) => s.kind === 'hide' && s.from === H)).toBe(true);
    expect(specs.filter((s) => 'sticky' in s)).toEqual([]);
    expect(rangesOf(stickyRangesOf(view))).toEqual([]);
  });

  it('the marker stays hidden with the caret on its line, and stickyRangesOf reports it', () => {
    const view = mount();
    caretAt(view, H + 5);
    expect(hiddenRanges(view)).toContainEqual([H, H + 3]);
    expect(rangesOf(stickyRangesOf(view))).toEqual([[H, H + 3]]);
  });

  it('stickyRangesOf is empty outside live mode', () => {
    const view = mount(DOC, { live: false });
    expect(rangesOf(stickyRangesOf(view))).toEqual([]);
  });
});

describe('sticky caret', () => {
  it('Home on a heading line puts the caret after the marker, and again leaves it there', () => {
    const view = mount();
    caretAt(view, H + 6);
    press(view, 'Home');
    expect(head(view)).toBe(H + 3);
    press(view, 'Home');
    expect(head(view)).toBe(H + 3);
  });

  it('Home on a first-line heading puts the caret at offset 3, not 0', () => {
    const view = mount('## Title');
    caretAt(view, 8);
    press(view, 'Home');
    expect(head(view)).toBe(3);
  });

  it('ArrowLeft from just after the marker goes to the end of the previous line', () => {
    const view = mount();
    caretAt(view, H + 3);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(H - 1);
    expect(view.state.selection.main.empty).toBe(true);
  });

  it('word-left from just after the marker goes to the end of the previous line too', () => {
    const view = mount();
    caretAt(view, H + 3);
    press(view, 'ArrowLeft', { ctrlKey: true });
    expect(head(view)).toBe(H - 1);
  });

  it('Shift+ArrowLeft from just after the marker selects the whole marker (it is atomic)', () => {
    const view = mount();
    caretAt(view, H + 3);
    press(view, 'ArrowLeft', { shiftKey: true });
    expect(view.state.selection.main.anchor).toBe(H + 3);
    expect(head(view)).toBe(H);
  });

  it('ArrowLeft just after the marker of a first-line heading stays put', () => {
    const view = mount('## Title');
    caretAt(view, 3);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(3);
  });

  it('ArrowRight from the end of the previous line lands after the marker', () => {
    const view = mount();
    caretAt(view, H - 1);
    press(view, 'ArrowRight');
    expect(head(view)).toBe(H + 3);
  });

  it.each([[0, 3], [1, 3], [2, 3], [3, 3], [4, 4]])('a caret placed at offset %i of the heading line rests at %i', (at, rests) => {
    const view = mount();
    caretAt(view, H + at);
    expect(head(view)).toBe(H + rests);
  });

  it('typing at the caret never inserts before the marker', () => {
    const view = mount();
    for (const at of [H, H + 1, H + 2]) {
      caretAt(view, at);
      type(view, 'x');
    }
    expect(text(view)).toBe('Para one\n## xxxTitle\nAfter');
  });

  it('a non-empty selection that ends inside the marker is left alone', () => {
    const view = mount();
    view.dispatch({ selection: { anchor: 2, head: H + 1 } });
    expect(view.state.selection.main.anchor).toBe(2);
    expect(head(view)).toBe(H + 1);
  });

  it('during composition the caret is not moved: a compose transaction', () => {
    const view = mount();
    caretAt(view, H + 5);
    view.dispatch({ selection: { anchor: H }, userEvent: 'input.type.compose' });
    expect(head(view)).toBe(H);
  });

  it('during composition the caret is not moved: the view is composing', () => {
    const view = mount();
    caretAt(view, H + 5);
    Object.defineProperty(view, 'composing', { get: () => true, configurable: true });
    view.dispatch({ selection: { anchor: H } });
    expect(head(view)).toBe(H);
    delete view.composing;
    view.dispatch({ selection: { anchor: H } });
    expect(head(view)).toBe(H + 3);
  });

  it('every range of a multi-range selection obeys the rule', () => {
    const view = mount('## One\n## Two', { extra: EditorState.allowMultipleSelections.of(true) });
    view.dispatch({ selection: EditorSelection.create([EditorSelection.cursor(0), EditorSelection.cursor(8)], 1) });
    expect(view.state.selection.ranges.map((r) => r.head)).toEqual([3, 10]);
  });
});

describe('Backspace after a line marker', () => {
  it('removes exactly the marker and its one trailing space', () => {
    const view = mount();
    caretAt(view, H + 3);
    expect(press(view, 'Backspace')).toBe(true);
    expect(text(view)).toBe('Para one\nTitle\nAfter');
    expect(head(view)).toBe(H);
  });

  it('Backspace at offset 3 of `## Title` gives `Title`', () => {
    const view = mount('## Title');
    caretAt(view, 3);
    press(view, 'Backspace');
    expect(text(view)).toBe('Title');
  });

  it('declines anywhere else and with a non-empty selection', () => {
    const view = mount();
    caretAt(view, H + 5);
    expect(removeLineMarker(view)).toBe(false);
    view.dispatch({ selection: { anchor: H + 3, head: H + 5 } });
    expect(removeLineMarker(view)).toBe(false);
    expect(text(view)).toBe(DOC);
  });

  it('elsewhere on the line Backspace deletes one character as usual', () => {
    const view = mount();
    caretAt(view, H + 5);
    press(view, 'Backspace');
    expect(text(view)).toBe('Para one\n## Ttle\nAfter');
  });
});

describe('live mode off: none of it applies', () => {
  it('Home goes to offset 0, the caret can rest inside the marker, Backspace deletes one character', () => {
    const view = mount('## Title', { live: false });
    caretAt(view, 8);
    press(view, 'Home');
    expect(head(view)).toBe(0);
    caretAt(view, 1);
    expect(head(view)).toBe(1);
    expect(removeLineMarker(view)).toBe(false);
    caretAt(view, 3);
    expect(leaveLineMarker(view)).toBe(false);
    press(view, 'Backspace');
    expect(text(view)).toBe('##Title');
  });

  it('turning live mode off after it was on restores plain behaviour', () => {
    const view = mount('## Title');
    setLivePreview(view, false);
    caretAt(view, 0);
    expect(head(view)).toBe(0);
    expect(rangesOf(stickyRangesOf(view))).toEqual([]);
  });
});

describe('a selection that cuts into a sticky range', () => {
  it.each(['Delete', 'Backspace'])('%s over previous paragraph .. offset 1 of the marker yields the same text as in Source mode', (key) => {
    const run = (live) => {
      const view = mount(DOC, { live });
      view.dispatch({ selection: { anchor: 5, head: H + 1 } });
      press(view, key);
      return text(view);
    };
    expect(run(true)).toBe(run(false));
    expect(run(true)).toBe('Para # Title\nAfter');
  });
});

describe('cost', () => {
  it('caret moves on a long page stay linear in the page at worst (the caret rule reads one line)', () => {
    const make = (n) => {
      const doc = Array.from({ length: n }, (_, i) => (i % 7 === 0 ? `## Heading ${i}` : `Some *text* with [[Link${i}]] and \`code\` ${i}`)).join('\n');
      const view = mount(doc);
      return { view, mid: view.state.doc.line(Math.floor(n / 2)).from };
    };
    const moves = ({ view, mid }) => { for (let i = 0; i < 200; i += 1) caretAt(view, mid + (i % 40)); };
    const { ratio, bound } = linearCost(make, moves, { small: 2000 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});
