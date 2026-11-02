import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, waitFor, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditorView } from '@codemirror/view';

vi.mock('../api/client', () => ({
  api: {
    getProfile: vi.fn().mockResolvedValue({ editorMode: null }),
    updateProfile: vi.fn().mockResolvedValue({}),
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(),
    listAttachments: vi.fn(() => Promise.resolve({ attachments: [] })),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    scanMentions: vi.fn(() => Promise.resolve({ mentions: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
  },
}));
vi.mock('../hooks/usePagePreview', () => ({ loadPreview: vi.fn(), evictPreview: vi.fn() }));
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
import { getCommand } from '../commands/registry';

/**
 * The palette's Highlight and Toggle comment run through PageEditor's applyFormat (the same toggleWrap the
 * Mod-Shift-H / Mod-/ keys use): they wrap the selection, and a second run unwraps it.
 */
async function editorView(content = 'hello world') {
  api.getPage.mockResolvedValue({ content, metadata: {}, version: 1, markupSyntax: 'markdown' });
  render(
    <MemoryRouter initialEntries={['/edit/Existing']}>
      <NavigationGuardProvider>
        <Routes><Route path="/edit/:name" element={<PageEditor />} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
  await waitFor(() => expect(document.querySelector('.cm-editor')).not.toBeNull());
  const view = EditorView.findFromDOM(document.querySelector('.cm-editor'));
  await waitFor(() => expect(view.state.doc.toString()).toBe(content));
  return view;
}

const select = (view, from, to) => act(() => { view.dispatch({ selection: { anchor: from, head: to } }); });
const run = (id) => act(async () => { await getCommand(id).run(); });

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  localStorage.setItem('wikantik.editor.mode', 'source');
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: 'hello world', metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockResolvedValue({ version: 2 });
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

// One run per mounted editor: happy-dom delivers the selectionchange of applyEdit's focus() inside the next update.
describe('PageEditor palette: Highlight and Toggle comment', () => {
  it('Highlight wraps the selection in ==', async () => {
    const view = await editorView();
    select(view, 6, 11);
    await run('format-highlight');
    expect(view.state.doc.toString()).toBe('hello ==world==');
    expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe('world');
  });

  it('Highlight on a highlighted selection unwraps it', async () => {
    const view = await editorView('hello ==world==');
    select(view, 8, 13);
    await run('format-highlight');
    expect(view.state.doc.toString()).toBe('hello world');
  });

  it('Toggle comment wraps the selection in %%', async () => {
    const view = await editorView();
    select(view, 0, 5);
    await run('toggle-comment');
    expect(view.state.doc.toString()).toBe('%%hello%% world');
  });

  it('Toggle comment on a commented selection unwraps it', async () => {
    const view = await editorView('%%hello%% world');
    select(view, 2, 7);
    await run('toggle-comment');
    expect(view.state.doc.toString()).toBe('hello world');
  });
});
