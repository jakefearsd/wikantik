/**
 * The editor preview pane's third of the three-renderer parity fixture (`utils/__fixtures__/live-preview-parity.json`):
 * each entry is rendered through EditorPreviewMarkdown with PageEditor's preview plugin stack and the DOM is classified
 * into the fixture's kinds. The live preview (`utils/livePreview/parity.test.js`) and the reader (`LivePreviewParityTest`,
 * wikantik-main) classify the same entries, so the three agree on what each construct is. The expectation is the entry's
 * `previewExpect`, else its `kind`. On a mismatch every label that does hold is reported, with the HTML.
 *
 * PageEditor builds its plugin arrays inline (in useMemo, from hook state), so they cannot be imported; the stack below
 * is pinned to PageEditor's source by the drift test at the end.
 */
import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import remarkInlineMath from '../../utils/remarkInlineMath';
import remarkCallouts from '../../utils/remarkCallouts';
import remarkBlockIds, { rehypeBlockIds } from '../../utils/remarkBlockIds';
import { remarkWikiMarkup } from '../../utils/remarkWikiMarkup';
import { remarkWikiLinks } from '../../utils/remarkWikiLinks';
import { remarkMissingLinks } from '../../utils/wikiLinkTargets';
import { remarkAttachments } from '../../utils/remarkAttachments';
import { rehypeRenderedFences } from '../../utils/fences/rehypeRenderedFences';
import { rehypeHighlightCode } from '../../utils/codeHighlight';
import rehypeSourceLine from '../../utils/rehypeSourceLine';
import fixture from '../../utils/__fixtures__/live-preview-parity.json';
import pageEditorSource from '../PageEditor.jsx?raw';
import EditorPreviewMarkdown from './EditorPreviewMarkdown';

const PAGE = 'LivePreviewParityHost';
// PageEditor's stack with its hook inputs at their empty values: no resolved or missing pages, no attachments, and no
// lowlight (no entry holds a code block).
const REMARK = [
  [remarkGfm, { singleTilde: false }],
  [remarkMath, { singleDollarTextMath: false }],
  remarkInlineMath,
  remarkCallouts,
  remarkBlockIds,
  remarkWikiMarkup,
  [remarkWikiLinks, { resolved: new Map(), attachments: [], pageName: PAGE }],
  [remarkMissingLinks, { missing: new Set() }],
  [remarkAttachments, { attachments: [], pageName: PAGE }],
];
const REHYPE = [rehypeBlockIds, rehypeKatex, rehypeRenderedFences, [rehypeHighlightCode, { lowlight: null }], rehypeSourceLine];
const EXPECT_FIELDS = ['liveExpect', 'previewExpect', 'readerExpect'];

function render(markdown) {
  const html = renderToStaticMarkup(<EditorPreviewMarkdown content={markdown} remarkPlugins={REMARK} rehypePlugins={REHYPE} />);
  const root = document.createElement('div');
  root.innerHTML = html;
  return { html, root, text: root.textContent };
}

const all = (o, sel) => [...o.root.querySelectorAll(sel)];
const firstLine = (e) => e.markdown.split('\n')[0];
const unescaped = (s) => s.replace(/\\([!-/:-@[-`{-~])/g, '$1');
/** A link flagged as a missing attachment (the preview draws it as a broken, red wavy link): not a working link. */
const isBroken = (a) => a.hasAttribute('data-attachment-missing');
const isUrlLink = (a) => {
  if (isBroken(a)) return false;
  const href = a.getAttribute('href') || '';
  const text = a.textContent.trim();
  // GFM: www.x → http://www.x; trailing sentence punctuation is never part of the link
  return text !== '' && !/[.,:;!?]$/.test(text) && [text, `mailto:${text}`, `http://${text}`].includes(href);
};
const hasTextLink = (o) => all(o, 'a[href^="http"]').some((a) => !isBroken(a) && !isUrlLink(a));
const hasFootnoteRef = (o) => all(o, 'sup a[href^="#"]').length > 0;

/** Label → whether the preview DOM of an entry shows it; the same rules LivePreviewParityTest applies to the reader. */
const PREDICATES = {
  table: (o) => all(o, 'table').some((t) => !t.closest('li, blockquote')),
  'table-nested': (o) => all(o, 'table').some((t) => t.closest('li, blockquote')),
  'footnote-ref': hasFootnoteRef,
  'footnote-def': (o) => all(o, '.footnotes li').some((li) => li.textContent.trim() !== ''),
  'ref-link': (o) => hasTextLink(o) && !o.text.includes('['),
  'shortcut-link': (o) => hasTextLink(o) && !o.text.includes('['),
  'ref-link-undefined': (o) => all(o, 'a').length === 0 && o.text.includes('[') && o.text.includes(']'),
  autolink: (o) => all(o, 'a').some(isUrlLink),
  'bare-url': (o) => all(o, 'a').some(isUrlLink),
  escaped: (o, e) => o.text.includes(unescaped(firstLine(e))) && all(o, 'em, strong').length === 0 && !hasTextLink(o),
  'escaped-footnote': (o) => !hasFootnoteRef(o) && o.text.includes('[^'),
  'setext-h1': (o) => all(o, 'h1').length > 0,
  'setext-h2': (o) => all(o, 'h2').length > 0,
  'html-block': (o, e) => {
    const tag = /<([A-Za-z][A-Za-z0-9-]*)/.exec(firstLine(e));
    return Boolean(tag) && all(o, tag[1]).length > 0 && !o.text.includes('<');
  },
  'plugin-line': (o) => !o.text.includes('[{') && all(o, '.wiki-plugin-chip').length === 0,
  'inline-math': (o) => all(o, '.math-inline, .katex').some((m) => !m.parentElement?.closest('.math-display, .katex-display')),
  'display-math': (o) => all(o, '.math-display, .katex-display').length > 0,
  callout: (o) => all(o, '.callout').length > 0,
  task: (o) => all(o, 'input[type="checkbox"]').length > 0,
  wikilink: (o) => all(o, 'a').some((a) => (a.getAttribute('href') || '').includes('OtherPage')) && !o.text.includes('[['),
  chip: (o) => all(o, '.wiki-plugin-chip').length > 0,
  // Shown as its source text, not as the construct.
  literal: (o, e) => !PREDICATES[e.kind](o, e) && o.text.includes(firstLine(e)),
};

const expectationOf = (e) => e.previewExpect ?? e.kind;

describe('editor preview pane — live-preview parity fixture', () => {
  it('states a reason for every per-renderer expectation and only known labels', () => {
    const problems = fixture.flatMap((e) => [
      ...(EXPECT_FIELDS.some((f) => f in e) && !(typeof e.notes === 'string' && e.notes.trim()) ? [`${e.name}: no notes`] : []),
      ...(expectationOf(e) in PREDICATES ? [] : [`${e.name}: unknown previewExpect ${e.previewExpect}`]),
    ]);
    expect(problems).toEqual([]);
  });

  it.each(fixture.map((e) => [e.name, e]))('the preview pane shows %s as expected', (_name, e) => {
    const o = render(e.markdown);
    const want = expectationOf(e);
    if (!PREDICATES[want](o, e)) {
      const observed = Object.keys(PREDICATES).filter((k) => PREDICATES[k](o, e));
      expect.fail(`expected ${want}; observed [${observed.join(', ')}]\n  html ${o.html}`);
    }
  });

  it("uses PageEditor's preview plugin stack, in order", () => {
    const block = (name) => {
      // `const name = useMemo(() => [ … ], [deps…])`: the list ends where the dependency array follows it.
      const m = new RegExp(`const ${name} = useMemo\\(\\s*\\(\\) => \\[([\\s\\S]*?)\\],\\s*\\[[\\w$, ]*\\],?\\s*\\)`).exec(pageEditorSource);
      expect(m, `${name} not found in PageEditor.jsx`).not.toBeNull();
      return m[1];
    };
    // The plugin of each top-level entry: a bare identifier, or the first element of a `[plugin, options]` pair.
    const pluginsOf = (src) => [...src.replace(/\{[^{}]*\}/g, '').matchAll(/(?:^|,)\s*\[?\s*([A-Za-z_$][\w$]*)/g)].map((m) => m[1]);
    const nameOf = (p) => (Array.isArray(p) ? p[0] : p).name;
    const remark = block('previewRemarkPlugins');
    expect(pluginsOf(remark)).toEqual(REMARK.map(nameOf));
    expect(remark).toContain('[remarkGfm, { singleTilde: false }]');
    expect(remark).toContain('[remarkMath, { singleDollarTextMath: false }]');
    expect(pluginsOf(block('previewRehypePlugins'))).toEqual(REHYPE.map(nameOf));
  });
});
