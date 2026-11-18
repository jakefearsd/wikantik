/**
 * Round-trip invariant: the live preview is a pure view. On a real EditorView with the full extension set,
 * toggling it on/off/on, moving the caret to every line and mousedown-ing every widget leaves the document
 * byte-identical to the fixture and never produces a docChanged transaction. The no-op invariant at the end of the
 * file extends the caret part to every line start and end of every corpus page.
 */
import { describe, it, expect, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { history } from '@codemirror/commands';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview, liveModeField } from './index';
import { setLiveMode } from './plugin';
import { fullyParsed, parseFully } from '../../test/fullParse';
import { pages } from '../../test/corpusPages';
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
      try {
        for (const el of widgets) {
          el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
        }
      } finally {
        window.removeEventListener('selectionchange', holdSelectionChange, true);
      }
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
    const kinds = ['cm-lp-rule', 'cm-lp-math-block', 'cm-lp-math', 'cm-lp-embed', 'cm-lp-table-block', 'cm-lp-fence-block',
      'cm-lp-callout-icon', 'cm-lp-image-wrap', 'cm-lp-chip'];
    for (const kind of kinds) {
      expect(seen.some((c) => c.split(/\s+/).includes(kind)), kind).toBe(true);
    }
  });
});

/**
 * The no-op invariant every Rich-mode rule rests on: opening a page and putting the caret anywhere changes nothing.
 * Rich mode moves a caret out of hidden markers (sticky.js), so the sweep goes where it does — every line's start and
 * end — over the kitchen-sink fixture and every page the corpus sweep covers. A failure names the page and the offset.
 *
 * Two passes, for cost: every page at state level (transactions through the full extension: the caret rule is a
 * transaction filter, so this is where a caret move could pick up a change), and the fixture plus a deterministic
 * sample of the corpus on a mounted editor, where view plugins and listeners run too. Mounting all ~1200 pages takes
 * ~28 s locally against ~2 s for the state pass.
 */
describe('no-op invariant: placing the caret never changes the document', () => {
  const corpusContext = { pageName: 'CorpusPage', attachments: [], loadEmbed: () => Promise.resolve({ state: 'ok', html: '' }) };
  const subjects = [{ name: 'live-preview-kitchen-sink.md', body: KITCHEN_SINK, ctx: context },
    ...pages.map((p) => ({ ...p, ctx: corpusContext }))];
  const VIEW_SAMPLE = 100;
  const SWEEP_TIMEOUT_MS = 120_000; // headroom for coverage instrumentation and loaded CI workers, as in corpus.test.js
  const extensionsFor = (ctx, extra) => [markdown(editorMarkdownConfig), history(), livePreview({ getContext: () => ctx }), extra];
  /** Each line's start and end, in document order. */
  function* placesOf(doc) {
    for (let n = 1; n <= doc.lines; n += 1) {
      const line = doc.line(n);
      yield line.from;
      if (line.length) yield line.to;
    }
  }

  /** State pass. Returns the failures (`page@offset: why`) and how many carets the caret rule moved. */
  function sweepState({ name, body, ctx }, extra = []) {
    let state = fullyParsed(EditorState.create({ doc: body, extensions: extensionsFor(ctx, extra) }), 60000);
    state = state.update({ effects: setLiveMode.of(true) }).state;
    if (!state.field(liveModeField)) return { bad: [`${name}: Rich mode did not turn on`], moved: 0 };
    const baseline = state.doc; // CodeMirror normalises CRLF on load; the sweep must not alter what it loaded
    let moved = 0;
    for (const at of placesOf(baseline)) {
      const tr = state.update({ selection: { anchor: at }, userEvent: 'select' });
      if (tr.docChanged) return { bad: [`${name}@${at}: the caret move carried a document change`], moved };
      if (tr.state.doc !== baseline) return { bad: [`${name}@${at}: the document is no longer the one loaded`], moved };
      if (tr.state.selection.main.head !== at) moved += 1;
      state = tr.state;
    }
    return { bad: [], moved };
  }

  /** Mounted pass: the same places on a real EditorView. Returns the failures. */
  function sweepView({ name, body, ctx }, extra = []) {
    const bad = [];
    let at = -1;
    const parent = document.createElement('div');
    document.body.appendChild(parent);
    const view = new EditorView({
      parent,
      state: EditorState.create({
        doc: body,
        extensions: extensionsFor(ctx, [extra, EditorView.updateListener.of((u) => {
          if (u.transactions.some((tr) => tr.docChanged)) bad.push(`${name}@${at}: a transaction changed the document`);
        })]),
      }),
    });
    try {
      parseFully(view, 60000);
      setLivePreview(view, true);
      if (!view.state.field(liveModeField)) return [`${name}: Rich mode did not turn on`];
      const baseline = view.state.doc;
      for (at of placesOf(baseline)) {
        view.dispatch({ selection: { anchor: at }, userEvent: 'select' });
        if (!bad.length && view.state.doc !== baseline) bad.push(`${name}@${at}: the document is no longer the one loaded`);
        if (bad.length) break;
      }
    } catch (e) {
      bad.push(`${name}@${at}: ${e && e.message}`);
    } finally {
      view.destroy();
      parent.remove();
    }
    return bad;
  }

  /** Runs `fn` with console.warn captured; returns the editor's own warnings (KaTeX grumbles about corpus math too). */
  function ownWarnings(fn) {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      fn();
      return warn.mock.calls.map((c) => String(c[0])).filter((m) => m.startsWith('[rich]') || m.startsWith('[live-preview]'));
    } finally {
      warn.mockRestore();
    }
  }

  it('state pass: the fixture and every corpus page, at every line start and line end', () => {
    expect(pages.length).toBeGreaterThan(1000);
    const bad = [];
    let moved = 0;
    const t0 = performance.now();
    const warnings = ownWarnings(() => {
      for (const subject of subjects) {
        try {
          const r = sweepState(subject);
          bad.push(...r.bad);
          moved += r.moved;
        } catch (e) {
          bad.push(`${subject.name}: ${e && e.message}`);
        }
      }
    });
    console.info(`no-op invariant: state pass over ${subjects.length} pages in ${((performance.now() - t0) / 1000).toFixed(1)} s; the caret rule moved ${moved} carets`);
    expect(bad, `placing the caret changed the document: ${bad.slice(0, 20).join('; ')}`).toEqual([]);
    expect(warnings.slice(0, 5)).toEqual([]);
    expect(moved).toBeGreaterThan(10_000); // the sweep did meet the caret rule: the corpus is full of headings and lists
  }, SWEEP_TIMEOUT_MS);

  it('mounted pass: the fixture and a deterministic sample of the corpus, at every line start and line end', () => {
    const step = Math.max(1, Math.floor(pages.length / VIEW_SAMPLE));
    const sample = [subjects[0], ...subjects.slice(1).filter((_, i) => i % step === 0).slice(0, VIEW_SAMPLE)];
    expect(sample.length).toBeGreaterThan(VIEW_SAMPLE / 2);
    const bad = [];
    const t0 = performance.now();
    const warnings = ownWarnings(() => { for (const subject of sample) bad.push(...sweepView(subject)); });
    console.info(`no-op invariant: mounted pass over ${sample.length} pages in ${((performance.now() - t0) / 1000).toFixed(1)} s`);
    expect(bad, `placing the caret changed the document: ${bad.slice(0, 20).join('; ')}`).toEqual([]);
    expect(warnings.slice(0, 5)).toEqual([]);
  }, SWEEP_TIMEOUT_MS);

  it('both passes can fail: a rule that edits on a caret move is reported with its page and offset', () => {
    // Stands in for a broken caret rule: a caret placed at the heading line's start (9) gets a change attached.
    const meddling = EditorState.transactionFilter.of((tr) => (tr.selection && !tr.docChanged && tr.newSelection.main.head === 9
      ? [tr, { changes: { from: 0, insert: ' ' }, sequential: true }] : tr));
    const probe = { name: 'Probe', body: 'Para one\n## Title', ctx: context };
    expect(sweepState(probe, meddling).bad).toEqual(['Probe@9: the caret move carried a document change']);
    expect(sweepView(probe, meddling)).toEqual(['Probe@9: a transaction changed the document']);
    expect(sweepState(probe).bad).toEqual([]);
    expect(sweepView(probe)).toEqual([]);
  });
});
