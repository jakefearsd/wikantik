/**
 * "Copy block link" inside the full PageEditor with the REAL CodeMirror: the block at the caret gets a marker when it
 * has none (one undo step) and `[[Page#^id]]` is copied; without a block at the caret the toast says where to put it.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, waitFor, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditorView } from '@codemirror/view';
import { undo } from '@codemirror/commands';

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
import { getCommand } from '../commands/registry';

async function editorView(content) {
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

const caretAt = (view, pos) => act(() => { view.dispatch({ selection: { anchor: pos } }); });
const run = () => act(async () => { await getCommand('copy-block-link').run(); });

let toast;
let writeText;
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  useToast.mockReturnValue(toast);
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  writeText = vi.fn(() => Promise.resolve());
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
});

afterEach(async () => {
  vi.restoreAllMocks();
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

describe('PageEditor "Copy block link"', () => {
  it('adds a marker to an id-less block as one undo step and copies [[Page#^id]]', async () => {
    vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation((arr) => arr.fill(0));
    const view = await editorView('# Title\n\nhello world\n\nnext');
    caretAt(view, 12);
    await run();
    expect(view.state.doc.toString()).toBe('# Title\n\nhello world ^aaaaaa\n\nnext');
    expect(writeText).toHaveBeenCalledWith('[[Existing#^aaaaaa]]');
    expect(toast.success).toHaveBeenCalledWith('Block link copied');
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('# Title\n\nhello world\n\nnext');
  });

  it('copies the existing id of a block without editing the text', async () => {
    const view = await editorView('- item ^abc\n- other');
    caretAt(view, 3);
    await run();
    expect(view.state.doc.toString()).toBe('- item ^abc\n- other');
    expect(writeText).toHaveBeenCalledWith('[[Existing#^abc]]');
  });

  it('with no block at the caret, says where to put it and copies nothing', async () => {
    const view = await editorView('# Only a heading');
    caretAt(view, 4);
    await run();
    expect(toast.info).toHaveBeenCalledWith('Put the cursor in a paragraph, list item or table');
    expect(writeText).not.toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe('# Only a heading');
  });

  it('a clipboard failure is reported (the new id stays in the text)', async () => {
    vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation((arr) => arr.fill(1));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    writeText.mockRejectedValueOnce(new Error('denied'));
    const view = await editorView('para');
    caretAt(view, 1);
    await run();
    expect(toast.error).toHaveBeenCalledWith("Couldn't copy link");
    expect(warn).toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe('para ^bbbbbb');
  });

  it('a block that cannot carry a marker is reported and the text is left alone', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const view = await editorView('- ^x');
    caretAt(view, 0);
    await run();
    expect(toast.error).toHaveBeenCalledWith("Couldn't add a block id here");
    expect(writeText).not.toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe('- ^x');
    expect(warn).toHaveBeenCalled();
  });
  it('a block holding access rules is refused with a toast, nothing copied and the text left alone', async () => {
    const view = await editorView('Para ^id [{ ALLOW view Admin}]\n\nnext');
    caretAt(view, 2);
    await run();
    expect(toast.error).toHaveBeenCalledWith('This block contains access rules — save, then copy its link from the page view');
    expect(writeText).not.toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe('Para ^id [{ ALLOW view Admin}]\n\nnext');
  });
});
