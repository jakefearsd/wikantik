/**
 * PageEditor with the REAL CodeMirror editor (no @uiw/react-codemirror stub). The other PageEditor suites stub
 * the editor, which hides react-codemirror's typing latch: an external `value` change arriving within ~200 ms
 * of a keystroke is deferred and later replays a stale document. Formatting commands must therefore land in
 * the view at once and keep the saved body equal to what the user sees.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditorView } from '@codemirror/view';

vi.mock('../api/client', () => ({
  api: {
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

async function editorView() {
  render(
    <MemoryRouter initialEntries={['/edit/Existing']}>
      <NavigationGuardProvider>
        <Routes><Route path="/edit/:name" element={<PageEditor />} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
  await waitFor(() => expect(document.querySelector('.cm-editor')).not.toBeNull());
  const view = EditorView.findFromDOM(document.querySelector('.cm-editor'));
  await waitFor(() => expect(view.state.doc.toString()).toBe('hello world'));
  return view;
}

const typeAtEnd = (view, text) => act(() => {
  const end = view.state.doc.length;
  view.dispatch({ changes: { from: end, insert: text }, selection: { anchor: end + text.length } });
});

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
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

describe('PageEditor formatting on real CodeMirror', () => {
  it('Bold right after a keystroke lands at once, keeps its selection, and later typing survives into the save', async () => {
    const view = await editorView();
    typeAtEnd(view, ' x');                                         // a keystroke arms the typing latch
    act(() => { view.dispatch({ selection: { anchor: 0, head: 5 } }); });
    fireEvent.click(screen.getByRole('button', { name: /^Bold \(/ }));
    expect(view.state.doc.toString()).toBe('**hello** world x');   // not deferred behind the latch
    expect(view.state.selection.main.from).toBe(2);
    expect(view.state.selection.main.to).toBe(7);

    typeAtEnd(view, 'Z');
    await act(async () => { await new Promise((r) => setTimeout(r, 900)); }); // latch expires
    expect(view.state.doc.toString()).toBe('**hello** world xZ');

    fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    await waitFor(() => expect(api.savePage).toHaveBeenCalled());
    expect(api.savePage.mock.calls[0][1].content).toContain('**hello** world xZ');
  });
});
