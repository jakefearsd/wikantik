/**
 * PageEditor link-completion wiring (heading cache). CodeEditor is stubbed to expose its
 * `linkCompletion` prop, because CodeMirror cannot run under happy-dom.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, screen, act, cleanup } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('./CodeEditor', async () => {
  const React = (await vi.importActual('react')).default;
  return {
    default: React.forwardRef(function CodeEditorStub({ linkCompletion, value }, ref) {
      React.useImperativeHandle(ref, () => ({
        getSelection: () => ({ selStart: 0, selEnd: 0 }),
        setSelection() {}, focus() {}, getViewport: () => null, scrollToLine: (...args) => { (globalThis.__scrollCalls ||= []).push(args); },
        getScrollerRect: () => null, jumpToLineAligned() {},
      }));
      globalThis.__linkCompletion = linkCompletion;
      return React.createElement('pre', { 'data-testid': 'body-value' }, value);
    }),
  };
});

vi.mock('../api/client', () => ({
  api: {
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(),
    uploadAttachment: vi.fn(),
    listAttachments: vi.fn(),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    scanMentions: vi.fn(() => Promise.resolve({ mentions: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
  },
}));
vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useDraft', () => ({ useDraft: vi.fn() }));
vi.mock('../hooks/useAttachments', () => ({ useAttachments: vi.fn() }));
vi.mock('../hooks/useEditorDrop', () => ({ useEditorDrop: vi.fn() }));
vi.mock('../hooks/useToast', () => ({ useToast: vi.fn() }));

import PageEditor from './PageEditor';
import { NavigationGuardProvider } from '../navigation/NavigationGuardProvider';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';

function renderEditor(pageName) {
  return render(
    <MemoryRouter initialEntries={[`/edit/${pageName}`]}>
      <NavigationGuardProvider>
      <Routes>
        <Route path="/edit/:name" element={<PageEditor />} />
        <Route path="/wiki/:name" element={<div data-testid="wiki-view">WIKI VIEW</div>} />
      </Routes>
</NavigationGuardProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation((pageName) => ({
    list: [],
    uploadAttachment: (f, n) => api.uploadAttachment(pageName, f, n),
    renameAttachment: vi.fn(),
    deleteAttachment: vi.fn(),
  }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  api.getPage.mockResolvedValue({ content: '# P', metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockResolvedValue({ success: true, version: 1 });
  api.uploadAttachment.mockResolvedValue({ success: true });
  api.listAttachments.mockResolvedValue({ attachments: [] });
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

describe('link completion heading cache', () => {
  it('M14: a missing target page is memoised as no headings (one lookup, not one per keystroke)', async () => {
    api.getPage.mockImplementation((n) => (n === 'Ghost'
      ? Promise.reject(Object.assign(new Error('not found'), { status: 404 }))
      : Promise.resolve({ content: '# P', metadata: {}, version: 1, markupSyntax: 'markdown' })));
    renderEditor('Existing');
    await screen.findByTestId('body-value');
    const lc = globalThis.__linkCompletion;
    expect(await lc.getHeadings('Ghost')).toEqual([]);
    expect(await lc.getHeadings('Ghost')).toEqual([]);
    expect(api.getPage.mock.calls.filter((c) => c[0] === 'Ghost')).toHaveLength(1);
  });

  it('other failures are not cached (retried on the next lookup)', async () => {
    api.getPage.mockImplementation((n) => (n === 'Flaky'
      ? Promise.reject(Object.assign(new Error('boom'), { status: 500 }))
      : Promise.resolve({ content: '# P', metadata: {}, version: 1, markupSyntax: 'markdown' })));
    renderEditor('Existing');
    await screen.findByTestId('body-value');
    const lc = globalThis.__linkCompletion;
    await expect(lc.getHeadings('Flaky')).rejects.toThrow('boom');
    await expect(lc.getHeadings('Flaky')).rejects.toThrow('boom');
    expect(api.getPage.mock.calls.filter((c) => c[0] === 'Flaky')).toHaveLength(2);
  });
});

describe('preview scroll sync', () => {
  it('syncEditor scrolls the editor without revealing folds (passive sync must not unfold)', async () => {
    globalThis.__scrollCalls = [];
    const { container } = renderEditor('Existing');
    await screen.findByTestId('body-value');
    const preview = container.querySelector('.editor-preview');
    expect(preview).not.toBeNull();
    fireEvent.scroll(preview);
    await act(async () => { await new Promise((r) => setTimeout(r, 50)); });
    expect(globalThis.__scrollCalls.length).toBeGreaterThan(0);
    expect(globalThis.__scrollCalls[0][1]).toEqual({ reveal: false });
  });
});
