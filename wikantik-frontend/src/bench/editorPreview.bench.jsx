/**
 * The editor's preview pane on a 2,000-line page: what one keystroke costs end to end (CodeMirror transaction
 * + PageEditor re-render + the preview's react-markdown pipeline), how many times react-markdown runs per
 * keystroke, and how many full CodeMirror reconfigures react-codemirror performs per keystroke. Also the
 * standalone cost of one react-markdown pass with the editor's plugin stack. Run with `npm run bench`.
 */
import { bench, describe, vi } from 'vitest';
import { act } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { StateEffect } from '@codemirror/state';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import remarkCallouts from '../utils/remarkCallouts';
import { remarkWikiMarkup } from '../utils/remarkWikiMarkup';
import { remarkWikiLinks } from '../utils/remarkWikiLinks';
import { remarkMissingLinks } from '../utils/wikiLinkTargets';
import { remarkAttachments } from '../utils/remarkAttachments';
import rehypeSourceLine from '../utils/rehypeSourceLine';
import { makeDoc, lineStart, report } from './fixtures';

vi.mock('react-markdown', async (importOriginal) => {
  const actual = await importOriginal();
  const Counted = (props) => { globalThis.__markdownRenders = (globalThis.__markdownRenders || 0) + 1; return actual.default(props); };
  return { ...actual, default: Counted };
});
vi.mock('../api/client', () => ({
  api: {
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(() => Promise.reject(new Error('bench'))),
    listAttachments: vi.fn(() => Promise.resolve({ attachments: [] })),
    listPages: vi.fn(() => Promise.resolve({ pages: [], resolved: {} })),
    scanMentions: vi.fn(() => Promise.resolve({ mentions: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
    getPageEmbed: vi.fn(() => Promise.resolve({ html: '<p>embedded</p>' })),
  },
}));
vi.mock('../hooks/usePagePreview', () => ({ loadPreview: vi.fn(), evictPreview: vi.fn() }));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ user: { authenticated: true, loginPrincipal: 'bench', roles: ['Admin'] } }) }));
vi.mock('../hooks/useDraft', () => ({ useDraft: () => ({ draft: null, saveDraft: () => {}, clearDraft: () => {} }) }));
const ATTACHMENTS = { list: [], uploadAttachment: () => {}, renameAttachment: () => {}, deleteAttachment: () => {} };
vi.mock('../hooks/useAttachments', () => ({ useAttachments: () => ATTACHMENTS }));
vi.mock('../hooks/useEditorDrop', () => ({ useEditorDrop: () => {} }));
vi.mock('../hooks/useToast', () => ({ useToast: () => ({ success: () => {}, error: () => {}, info: () => {} }) }));

const { default: PageEditor } = await import('../components/PageEditor');
const { NavigationGuardProvider } = await import('../navigation/NavigationGuardProvider');
const { api } = await import('../api/client');
const { mountRealEditor } = await import('../test/realEditorHarness');
const { cleanup } = await import('@testing-library/react');

const DOC = makeDoc(2000);
const OPTS = { time: 1500, warmupTime: 200 };
const typeKey = (view) => act(() => {
  const pos = view.state.selection.main.head;
  view.dispatch({ changes: { from: pos, insert: 'x' }, selection: { anchor: pos + 1 }, userEvent: 'input.type' });
});

describe('editor preview pipeline', () => {
  bench('one react-markdown pass, 2,000-line page (editor plugin stack, static render)', () => {
    renderToStaticMarkup(
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath, remarkCallouts, remarkWikiMarkup,
          [remarkWikiLinks, { resolved: new Map(), attachments: [], pageName: 'Bench' }],
          [remarkMissingLinks, { missing: new Set() }], [remarkAttachments, { attachments: [], pageName: 'Bench' }]]}
        rehypePlugins={[rehypeKatex, rehypeSourceLine]}
      >
        {DOC}
      </ReactMarkdown>,
    );
  }, { time: 3000, warmupTime: 500 });

  // 500 lines (~9.5k chars) stays under the live-preview limit; 2,000 lines (~38k chars) is above it.
  for (const lines of [500, 2000]) {
    const doc = makeDoc(lines);
    let view;
    let reconfigures = 0;
    const setup = async () => {
      window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
      localStorage.clear();
      api.getPage.mockResolvedValue({ content: doc, metadata: {}, version: 1, markupSyntax: 'markdown' });
      view = await mountRealEditor(PageEditor, NavigationGuardProvider, doc);
      const dispatch = view.dispatch.bind(view);
      view.dispatch = (...specs) => {
        if (specs.some((s) => [].concat(s?.effects || []).some((e) => e.is(StateEffect.reconfigure)))) reconfigures += 1;
        return dispatch(...specs);
      };
      act(() => { view.dispatch({ selection: { anchor: lineStart(doc, 5) + 10 } }); });
      await act(async () => { await new Promise((r) => setTimeout(r, 1200)); }); // let load-time work settle
      // Deterministic counts for the report: 20 keystrokes, each committed by React before the next...
      globalThis.__markdownRenders = 0;
      reconfigures = 0;
      const t = performance.now();
      for (let i = 0; i < 20; i += 1) typeKey(view);
      report(`${lines} lines (${doc.length} chars): keystroke (20-key loop) ms/key`, ((performance.now() - t) / 20).toFixed(2));
      report(`${lines} lines: react-markdown renders per keystroke`, (globalThis.__markdownRenders / 20).toFixed(2));
      report(`${lines} lines: CodeMirror reconfigures per keystroke`, (reconfigures / 20).toFixed(2));
      // ...then the pause after the burst (preview settle, link resolution, missing-page checks).
      globalThis.__markdownRenders = 0;
      await act(async () => { await new Promise((r) => setTimeout(r, 1200)); });
      report(`${lines} lines: react-markdown renders in the 1.2 s pause after the burst`, globalThis.__markdownRenders);
    };
    const teardown = () => { cleanup(); };

    bench(`${lines} lines: keystroke end-to-end (dispatch + React commit), preview open`, () => { typeKey(view); },
      { ...OPTS, setup, teardown });
  }
});
