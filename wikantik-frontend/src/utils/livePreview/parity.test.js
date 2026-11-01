/**
 * The live preview's third of the three-renderer parity fixture (`__fixtures__/live-preview-parity.json`): each entry is
 * decorated with the caret elsewhere (no active line) and the decorations are classified into the fixture's kinds. The
 * editor preview pane (`components/editor/EditorPreviewMarkdown.parity.test.jsx`) and the reader
 * (`LivePreviewParityTest`, wikantik-main) classify the same entries, so the three agree on what each construct is.
 * The expectation is the entry's `liveExpect`, else its `kind`; an entry stating a per-renderer expectation says why in
 * `notes`. On a mismatch every label that does hold is reported.
 */
import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import fixture from '../__fixtures__/live-preview-parity.json';
import { fullyParsed } from '../../test/fullParse';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreviewSpecs, blockSpecs } from './ranges';

const CONTEXT = { pageName: 'LivePreviewParityHost', attachments: [] };
const KINDS = ['table', 'table-nested', 'footnote-ref', 'footnote-def', 'ref-link', 'ref-link-undefined', 'shortcut-link',
  'autolink', 'bare-url', 'escaped', 'escaped-footnote', 'setext-h1', 'setext-h2', 'html-block', 'plugin-line',
  'inline-math', 'display-math', 'callout', 'task', 'wikilink'];
const EXPECT_FIELDS = ['liveExpect', 'previewExpect', 'readerExpect'];

function decorate(doc) {
  const state = fullyParsed(EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig)] }));
  const none = new Set(); // the caret is elsewhere: no line is active
  return { state, specs: livePreviewSpecs(state, none, { context: CONTEXT }), blocks: blockSpecs(state, none, CONTEXT) };
}

const textOf = (state, s) => state.sliceDoc(s.from, s.to);
const widgets = (o, type) => o.specs.filter((s) => s.kind === 'widget' && s.widget.type === type);
const hides = (o) => o.specs.filter((s) => s.kind === 'hide').map((s) => textOf(o.state, s));
const marks = (o, cls) => o.specs.filter((s) => s.kind === 'mark' && s.cls === cls);
const lineClasses = (o) => new Set(o.specs.filter((s) => s.kind === 'line').map((s) => s.cls));
const chips = (o, cls) => widgets(o, 'chip').filter((s) => s.widget.cls.split(' ').includes(cls));
const isQuoteMark = (t) => /^> ?$/.test(t);
/** Line 1 as the reader of the editor sees it: hidden ranges removed, each widget as `[type]`. */
function visibleFirstLine(o) {
  const line = o.state.doc.line(1);
  let out = '';
  let pos = line.from;
  for (const s of o.specs.filter((x) => (x.kind === 'hide' || x.kind === 'widget') && x.from >= line.from && x.to <= line.to)) {
    out += o.state.sliceDoc(pos, s.from) + (s.kind === 'widget' ? `[${s.widget.type}]` : '');
    pos = s.to;
  }
  return out + o.state.sliceDoc(pos, line.to);
}
const links = (o) => marks(o, 'cm-lp-link');
const looksLikeUrl = (t) => /^(?:[a-z][a-z0-9+.-]*:|www\.)/i.test(t);
/** Link marks over whole URLs: trailing sentence punctuation is never part of the link. */
const urlLinks = (o) => links(o).filter((s) => !/[.,:;!?]$/.test(textOf(o.state, s)));

/** Label → whether the live decorations of an entry show it. */
const PREDICATES = {
  table: (o) => o.blocks.some((b) => b.widget.type === 'table'),
  'table-nested': (o) => o.blocks.some((b) => b.widget.type === 'table'),
  'footnote-ref': (o) => chips(o, 'cm-lp-footnote-ref').length > 0,
  'footnote-def': (o) => chips(o, 'cm-lp-footnote-def').length > 0,
  'ref-link': (o) => links(o).length > 0 && !visibleFirstLine(o).includes('['),
  'shortcut-link': (o) => links(o).length > 0 && !visibleFirstLine(o).includes('['),
  'ref-link-undefined': (o) => links(o).length === 0 && hides(o).length === 0,
  autolink: (o) => urlLinks(o).length > 0 && hides(o).includes('<') && hides(o).includes('>'),
  'bare-url': (o) => urlLinks(o).length > 0 && hides(o).length === 0,
  escaped: (o) => hides(o).length > 0 && hides(o).every((t) => t === '\\')
    && ['cm-lp-em', 'cm-lp-strong', 'cm-lp-del'].every((c) => marks(o, c).length === 0)
    && links(o).every((s) => looksLikeUrl(textOf(o.state, s))),
  'escaped-footnote': (o) => hides(o).includes('\\') && chips(o, 'cm-lp-footnote-ref').length === 0,
  'setext-h1': (o) => lineClasses(o).has('cm-lp-h1') && lineClasses(o).has('cm-lp-setext-underline'),
  'setext-h2': (o) => lineClasses(o).has('cm-lp-h2') && lineClasses(o).has('cm-lp-setext-underline'),
  'html-block': (o) => lineClasses(o).has('cm-lp-html'),
  'plugin-line': () => false, // the editor never runs a plugin (only the server does): see `chip`
  'inline-math': (o) => widgets(o, 'math').some((s) => !s.widget.display),
  'display-math': (o) => o.blocks.some((b) => b.widget.type === 'math' && b.widget.display),
  callout: (o) => widgets(o, 'callout-title').length > 0,
  task: (o) => widgets(o, 'checkbox').length > 0,
  wikilink: (o) => links(o).length > 0 && hides(o).some((t) => t.startsWith('[[')),
  chip: (o) => widgets(o, 'chip').some((s) => !/cm-lp-footnote/.test(s.widget.cls)),
  // Shown as its source: no block widget and nothing replaced or styled inline beyond list bullets and quote marks
  // (line classes, such as an HTML block's muted cm-lp-html, only style the source).
  literal: (o) => o.blocks.length === 0
    && o.specs.every((s) => s.kind === 'line'
      || (s.kind === 'widget' && s.widget.type === 'bullet')
      || (s.kind === 'hide' && isQuoteMark(textOf(o.state, s)))),
};

const expectationOf = (e) => e.liveExpect ?? e.kind;

describe('live-preview parity fixture', () => {
  it('is well formed: known kinds, and every per-renderer expectation says why', () => {
    expect(fixture.length).toBeGreaterThan(0);
    const problems = [];
    for (const e of fixture) {
      if (!KINDS.includes(e.kind)) problems.push(`${e.name}: unknown kind ${e.kind}`);
      if (EXPECT_FIELDS.some((f) => f in e) && !(typeof e.notes === 'string' && e.notes.trim())) {
        problems.push(`${e.name}: a per-renderer expectation must say why in notes`);
      }
      if (!(expectationOf(e) in PREDICATES)) problems.push(`${e.name}: unknown liveExpect ${e.liveExpect}`);
    }
    expect(problems).toEqual([]);
  });

  it('covers every kind', () => {
    expect(KINDS.filter((k) => !fixture.some((e) => e.kind === k))).toEqual([]);
  });

  it.each(fixture.map((e) => [e.name, e]))('live preview shows %s as expected', (_name, e) => {
    const o = decorate(e.markdown);
    const want = expectationOf(e);
    if (!PREDICATES[want](o, e)) {
      const observed = Object.keys(PREDICATES).filter((k) => PREDICATES[k](o, e));
      const shown = o.specs.map((s) => (s.kind === 'line' ? `line ${s.cls}` : `${s.kind} ${s.cls ?? s.widget?.type ?? ''} ${JSON.stringify(textOf(o.state, s))}`));
      expect.fail(`expected ${want}; observed [${observed.join(', ')}]\n  specs  ${shown.join(' | ')}\n  blocks ${o.blocks.map((b) => b.widget.type).join(', ')}`);
    }
  });
});
