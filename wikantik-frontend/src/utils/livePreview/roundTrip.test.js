/**
 * Round-trip invariant: the live preview is a pure view. On a real EditorView with the full extension set,
 * toggling it on/off/on, moving the caret to every line and mousedown-ing every widget leaves the document
 * byte-identical to the fixture and never produces a docChanged transaction.
 */
import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { history } from '@codemirror/commands';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import KITCHEN_SINK from '../__fixtures__/live-preview-kitchen-sink.md?raw';

const context = {
  pageName: 'KitchenSink',
  attachments: ['pic.png'],
  loadEmbed: () => Promise.resolve({ state: 'ok', html: '<p>embedded</p>' }),
};

function mount(doc) {
  const changed = [];
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [
        markdown(editorMarkdownConfig),
        history(),
        livePreview({ getContext: () => context }),
        EditorView.updateListener.of((u) => { if (u.docChanged) changed.push(u.state.doc.toString()); }),
      ],
    }),
  });
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  return { view, changed, parent };
}

// Widget roots only (not the line/mark decorations that share the cm-lp- prefix: a mousedown on real text is CodeMirror's
// own selection handling, which happy-dom cannot lay out).
const WIDGET_SELECTOR = ['bullet', 'callout-icon', 'callout-title-widget', 'embed', 'fence-block', 'image-wrap', 'math',
  'math-block', 'rule', 'table-block', 'chip', 'task'].map((k) => `.cm-lp-${k}`).join(',');
const flush = () => new Promise((r) => setTimeout(r, 0));

// happy-dom has no layout, so CodeMirror renders only the first screenful; the fixture is therefore also swept in
// slices that start at later sections so every widget kind gets mounted and clicked at least once.
const SLICES = ['## Blocks', '> [!NOTE]', '$$\n\\int', '![[Other]]', '| Name'];
const docs = [['whole fixture', KITCHEN_SINK],
  ...SLICES.map((s) => [`slice from ${JSON.stringify(s)}`, KITCHEN_SINK.slice(KITCHEN_SINK.indexOf(s))])];

const seen = [];

describe('live preview round trip', () => {
  it.each(docs)('leaves the document byte-identical and never dispatches a doc change: %s', async (_name, DOC) => {
    const { view, changed, parent } = mount(DOC);
    try {
      setLivePreview(view, true);
      setLivePreview(view, false);
      setLivePreview(view, true);

      for (let n = 1; n <= view.state.doc.lines; n += 1) {
        const line = view.state.doc.line(n);
        view.dispatch({ selection: { anchor: line.from + Math.floor(line.length / 2) }, userEvent: 'select' });
      }
      await flush();

      // Task-checkbox widgets (.cm-lp-task) change the document BY DESIGN (a click toggles `[ ]`), so they
      // are excluded from the sweep; every other rendered widget must be inert to a mousedown.
      const widgets = [...view.dom.querySelectorAll(WIDGET_SELECTOR)].filter((el) => !el.classList.contains('cm-lp-task'));
      expect(widgets.length).toBeGreaterThan(0);
      seen.push(...widgets.map((w) => w.className));
      // happy-dom fires `selectionchange` synchronously while CodeMirror is mid-update (browsers deliver it later),
      // which makes the observer re-enter the view; hold those events back for the sweep.
      const holdSelectionChange = (e) => e.stopImmediatePropagation();
      window.addEventListener('selectionchange', holdSelectionChange, true);
      for (const el of widgets) {
        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
      }
      window.removeEventListener('selectionchange', holdSelectionChange, true);
      await flush();

      setLivePreview(view, false);
      setLivePreview(view, true);

      expect(view.state.doc.toString()).toBe(DOC);
      expect(changed).toEqual([]);
    } finally {
      view.destroy();
      parent.remove();
    }
  });

  it('mounted every widget kind across the slices', () => {
    for (const kind of ['cm-lp-rule', 'cm-lp-math-block', 'cm-lp-embed', 'cm-lp-table-block', 'cm-lp-fence-block', 'cm-lp-callout-icon', 'cm-lp-image-wrap']) {
      expect(seen.some((c) => c.includes(kind)), kind).toBe(true);
    }
  });
});
