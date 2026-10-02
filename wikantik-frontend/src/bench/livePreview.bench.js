/**
 * Live-preview decoration cost on a real EditorView (happy-dom renders a viewport), for 2,000- and
 * 5,000-line documents. Run with `npm run bench`. Each scenario is measured with live mode ON and OFF;
 * the difference is what the live preview adds to that editor action.
 */
import { bench, describe } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { history } from '@codemirror/commands';
import { editorMarkdownConfig } from '../utils/editorMarkdown';
import { livePreview, setLivePreview } from '../utils/livePreview';
import { activeLinesOf, livePreviewSpecs, blockCandidates, updateBlockCandidates } from '../utils/livePreview/ranges';
import { makeDoc, lineStart } from './fixtures';

const context = { pageName: 'Bench', attachments: [], loadEmbed: () => Promise.resolve({ state: 'ok', html: '<p>x</p>' }) };

function makeView(doc, live) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig), history(), livePreview({ getContext: () => context })] }),
  });
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  // Line 5 is the first section's paragraph: an ordinary line inside the rendered viewport.
  view.dispatch({ selection: { anchor: lineStart(doc, 5) + 10 } });
  if (live) setLivePreview(view, true);
  return view;
}

const OPTS = { time: 400, warmupTime: 100 };

for (const lines of [2000, 5000]) {
  const doc = makeDoc(lines);
  const para = lineStart(doc, 5) + 10;   // inside the first paragraph (line 5)
  const otherLine = lineStart(doc, 8) + 4; // the first list item (line 8)
  const far = lineStart(doc, Math.floor(lines * 0.6)); // well outside the initial viewport

  for (const live of [true, false]) {
    describe(`${lines} lines — live ${live ? 'ON' : 'OFF'} — EditorView.dispatch`, () => {
      let view;
      let flip = false;
      const setup = () => { view = makeView(doc, live); flip = false; };
      const teardown = () => { view.destroy(); view.dom.parentNode?.remove(); };

      bench('keystroke on an ordinary line', () => {
        const pos = view.state.selection.main.head;
        view.dispatch({ changes: { from: pos, insert: 'a' }, selection: { anchor: pos + 1 }, userEvent: 'input.type' });
      }, { ...OPTS, setup, teardown });

      bench('caret move within a line', () => {
        flip = !flip;
        view.dispatch({ selection: { anchor: para + (flip ? 5 : 0) }, userEvent: 'select' });
      }, { ...OPTS, setup, teardown });

      bench('caret move to another line', () => {
        flip = !flip;
        view.dispatch({ selection: { anchor: flip ? otherLine : para }, userEvent: 'select' });
      }, { ...OPTS, setup, teardown });

      bench('scroll (viewport jump)', () => {
        flip = !flip;
        view.dispatch({ effects: EditorView.scrollIntoView(flip ? far : 0, { y: 'start' }) });
      }, { ...OPTS, setup, teardown });
    });
  }

  describe(`${lines} lines — pure spec builders`, () => {
    let state;
    let active;
    let viewport;
    let keystroke;
    let before;
    const setup = () => {
      const view = makeView(doc, true);
      state = view.state;
      active = activeLinesOf(state);
      viewport = view.visibleRanges[0];
      keystroke = state.update({ changes: { from: para, insert: 'a' }, userEvent: 'input.type' });
      before = blockCandidates(state, context);
      view.destroy();
    };
    bench('livePreviewSpecs (visible range)', () => { livePreviewSpecs(state, active, { ...viewport, context }); }, { ...OPTS, setup });
    bench('blockCandidates (full document scan)', () => { blockCandidates(state, context); }, { ...OPTS, setup });
    bench('updateBlockCandidates (one keystroke)', () => { updateBlockCandidates(before, keystroke, context); }, { ...OPTS, setup });
  });
}
