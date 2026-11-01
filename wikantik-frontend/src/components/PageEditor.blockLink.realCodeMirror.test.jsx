/**
 * "Copy block link" inside the full PageEditor with the REAL CodeMirror. The server decides the block at the caret and
 * its id (POST /blocks/draft over the buffer): the edit it answers is applied as one undo step and `[[Page#^id]]` is
 * copied; its refusals, and edits to the block while it answers, are toasts that leave the text alone.
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
    draftBlocks: vi.fn(),
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

const refusal = (reason) => Object.assign(new Error('409'), { status: 409, body: { reason } });

describe('PageEditor "Copy block link"', () => {
  it('applies the edit the server answers as one undo step and copies [[Page#^id]]', async () => {
    api.draftBlocks.mockResolvedValue({ id: 'aaaaaa', change: { from: 20, to: 20, insert: ' ^aaaaaa' }, block: { index: 0, from: 9, to: 20 } });
    const view = await editorView('# Title\n\nhello world\n\nnext');
    caretAt(view, 12);
    await run();
    expect(api.draftBlocks).toHaveBeenCalledWith('Existing', { text: '# Title\n\nhello world\n\nnext', offset: 12 });
    expect(view.state.doc.toString()).toBe('# Title\n\nhello world ^aaaaaa\n\nnext');
    expect(writeText).toHaveBeenCalledWith('[[Existing#^aaaaaa]]');
    expect(toast.success).toHaveBeenCalledWith('Block link copied');
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('# Title\n\nhello world\n\nnext');
  });

  it('copies the id the server reads for the block without editing the text', async () => {
    api.draftBlocks.mockResolvedValue({ id: 'abc', change: null, block: { index: 0, from: 0, to: 11 } });
    const view = await editorView('- item ^abc\n- other');
    caretAt(view, 3);
    await run();
    expect(view.state.doc.toString()).toBe('- item ^abc\n- other');
    expect(writeText).toHaveBeenCalledWith('[[Existing#^abc]]');
  });

  it.each([
    ['no-block', 'info', 'Put the cursor in a paragraph, list item or table'],
    ['refused', 'error', "Couldn't add a block id here"],
  ])('a %s answer is a toast; nothing is copied and the text is left alone', async (reason, kind, message) => {
    api.draftBlocks.mockRejectedValue(refusal(reason));
    const view = await editorView('# Only a heading');
    caretAt(view, 4);
    await run();
    expect(toast[kind]).toHaveBeenCalledWith(message);
    expect(writeText).not.toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe('# Only a heading');
  });

  it('a server that cannot answer is a toast, and nothing is copied', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.draftBlocks.mockRejectedValue(Object.assign(new Error('503'), { status: 503 }));
    const view = await editorView('para');
    caretAt(view, 1);
    await run();
    expect(toast.error).toHaveBeenCalledWith("Couldn't get a block link — try again");
    expect(writeText).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalled();
  });

  it('an edit to the block while the server answers is a toast, and the answer is not applied', async () => {
    let answer;
    api.draftBlocks.mockImplementation(() => new Promise((r) => { answer = r; }));
    const view = await editorView('para');
    caretAt(view, 1);
    let done;
    act(() => { done = getCommand('copy-block-link').run(); });
    await waitFor(() => expect(api.draftBlocks).toHaveBeenCalled());
    act(() => { view.dispatch({ changes: { from: 2, insert: 'X' } }); });
    await act(async () => { answer({ id: 'bbbbbb', change: { from: 4, to: 4, insert: ' ^bbbbbb' }, block: { index: 0, from: 0, to: 4 } }); await done; });
    expect(toast.error).toHaveBeenCalledWith('The text changed — copy the block link again');
    expect(view.state.doc.toString()).toBe('paXra');
    expect(writeText).not.toHaveBeenCalled();
  });

  it('a clipboard failure is reported (the new id stays in the text)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.draftBlocks.mockResolvedValue({ id: 'bbbbbb', change: { from: 4, to: 4, insert: ' ^bbbbbb' }, block: { index: 0, from: 0, to: 4 } });
    writeText.mockRejectedValueOnce(new Error('denied'));
    const view = await editorView('para');
    caretAt(view, 1);
    await run();
    expect(toast.error).toHaveBeenCalledWith("Couldn't copy link");
    expect(warn).toHaveBeenCalled();
    expect(view.state.doc.toString()).toBe('para ^bbbbbb');
  });

  it('writes through a ClipboardItem when the browser has one, so the copy keeps the user gesture', async () => {
    const write = vi.fn(async (items) => { await items[0].data['text/plain']; });
    Object.defineProperty(navigator, 'clipboard', { value: { writeText, write }, configurable: true });
    globalThis.ClipboardItem = function ClipboardItem(data) { this.data = data; };
    try {
      api.draftBlocks.mockResolvedValue({ id: 'abc', change: null, block: { index: 0, from: 0, to: 4 } });
      const view = await editorView('para ^abc');
      caretAt(view, 1);
      await run();
      expect(write).toHaveBeenCalledTimes(1);
      expect(writeText).not.toHaveBeenCalled();
      expect(toast.success).toHaveBeenCalledWith('Block link copied');
      // a refusal rejects the item's data: the write fails quietly, the refusal is what is said
      api.draftBlocks.mockRejectedValue(refusal('refused'));
      toast.success.mockClear();
      await run();
      expect(toast.error).toHaveBeenCalledWith("Couldn't add a block id here");
      expect(toast.success).not.toHaveBeenCalled();
    } finally {
      delete globalThis.ClipboardItem;
    }
  });
});
