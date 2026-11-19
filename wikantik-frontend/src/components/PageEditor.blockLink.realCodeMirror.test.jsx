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
import { EditorState, Prec, StateEffect } from '@codemirror/state';
import { undo } from '@codemirror/commands';

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
import { parseFully } from '../test/fullParse';

async function editorView(content, metadata = {}) {
  api.getPage.mockResolvedValue({ content, metadata, version: 1, markupSyntax: 'markdown' });
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
  localStorage.setItem('wikantik.editor.mode', 'source'); // these tests exercise source mode; the default is live
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
    expect(api.draftBlocks).toHaveBeenCalledWith('Existing', { text: '# Title\n\nhello world\n\nnext', metadata: {}, offset: 12 });
    expect(view.state.doc.toString()).toBe('# Title\n\nhello world ^aaaaaa\n\nnext');
    expect(writeText).toHaveBeenCalledWith('[[Existing#^aaaaaa]]');
    expect(toast.success).toHaveBeenCalledWith('Block link copied');
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('# Title\n\nhello world\n\nnext');
  });

  // In Rich a grid table rewrites local input made at a selection range (clipped to the cell, pipes escaped, line
  // breaks turned into spaces). The server's edit is none of that, even when the selection reaches where it goes.
  // The edits are the server's own shapes for a block that takes a standalone marker (BlockEdits.withStandaloneMarker):
  // behind a blank line at the text end, or `\n^id\n` at the start of the blank line after the table.
  describe('on a table the grid draws (Rich), the server\'s edit is written byte for byte, whatever is selected', () => {
    const table = '| A | B |\n| --- | --- |\n| one | two |';
    async function richView(doc) {
      localStorage.setItem('wikantik.editor.mode', 'live');
      const view = await editorView(doc);
      await waitFor(() => expect(document.querySelector('.cm-editor.cm-live-preview')).not.toBeNull());
      act(() => { parseFully(view); });
      await waitFor(() => expect(view.contentDOM.querySelector('.cm-lp-table-row')).not.toBeNull());
      return view;
    }

    it('a table that ends the document, selected from a cell to the end', async () => {
      const doc = `Intro\n\n${table}`;
      api.draftBlocks.mockResolvedValue({ id: 'abc123', change: { from: doc.length, to: doc.length, insert: '\n\n^abc123' }, block: { index: 1, from: 7, to: doc.length } });
      const view = await richView(doc);
      // Anchored in a cell, ending after the row's closing pipe: the selection touches the place the edit goes.
      act(() => { view.dispatch({ selection: { anchor: doc.indexOf('one'), head: doc.length } }); });
      await run();
      expect(view.state.doc.toString()).toBe(`${doc}\n\n^abc123`);
      expect(writeText).toHaveBeenCalledWith('[[Existing#^abc123]]');
      act(() => { undo(view); });
      expect(view.state.doc.toString()).toBe(doc);
    });

    it('a table with text after it, the caret in a cell', async () => {
      const doc = `Intro\n\n${table}\n\nAfter`;
      const tableEnd = doc.indexOf(table) + table.length;
      api.draftBlocks.mockResolvedValue({ id: 'abc123', change: { from: tableEnd + 1, to: tableEnd + 1, insert: '\n^abc123\n' }, block: { index: 1, from: 7, to: tableEnd } });
      const view = await richView(doc);
      act(() => { view.dispatch({ selection: { anchor: doc.indexOf('one'), head: tableEnd } }); });
      await run();
      expect(view.state.doc.toString()).toBe(`Intro\n\n${table}\n\n^abc123\n\nAfter`);
      expect(writeText).toHaveBeenCalledWith('[[Existing#^abc123]]');
    });
  });

  // view.dispatch does not check readOnly. A block that already has an id can still be linked; one that needs the
  // edit cannot be given it, so nothing is written and nothing is copied.
  describe('on a read-only document', () => {
    const lock = (view) => act(() => { view.dispatch({ effects: StateEffect.appendConfig.of(Prec.highest(EditorState.readOnly.of(true))) }); });

    it('a block that needs an id is not given one: the text is unchanged, nothing is copied, and a toast says why', async () => {
      api.draftBlocks.mockResolvedValue({ id: 'aaaaaa', change: { from: 20, to: 20, insert: ' ^aaaaaa' }, block: { index: 0, from: 9, to: 20 } });
      const view = await editorView('# Title\n\nhello world\n\nnext');
      caretAt(view, 12);
      lock(view);
      expect(view.state.readOnly).toBe(true);
      await run();
      expect(view.state.doc.toString()).toBe('# Title\n\nhello world\n\nnext');
      expect(writeText).not.toHaveBeenCalled();
      expect(toast.info).toHaveBeenCalledWith('This page is read-only right now — a block without an id cannot be linked');
      expect(toast.success).not.toHaveBeenCalled();
    });

    it('a block that has an id is still copied', async () => {
      api.draftBlocks.mockResolvedValue({ id: 'abc', change: null, block: { index: 0, from: 0, to: 11 } });
      const view = await editorView('- item ^abc\n- other');
      caretAt(view, 3);
      lock(view);
      await run();
      expect(writeText).toHaveBeenCalledWith('[[Existing#^abc]]');
      expect(view.state.doc.toString()).toBe('- item ^abc\n- other');
    });
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

  it('sends the page\'s metadata, so the server reads the text as the body it is saved behind', async () => {
    api.draftBlocks.mockResolvedValue({ id: 'x', change: null, block: { index: 1, from: 4, to: 12 } });
    const view = await editorView('---\nIntro ^x\n\n---\n', { title: 'T' });
    caretAt(view, 5);
    await run();
    expect(api.draftBlocks).toHaveBeenCalledWith('Existing', { text: '---\nIntro ^x\n\n---\n', metadata: { title: 'T' }, offset: 5 });
    expect(writeText).toHaveBeenCalledWith('[[Existing#^x]]');
  });

  it('a page the server does not know yet says to save it first; nothing is copied', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.draftBlocks.mockRejectedValue(Object.assign(new Error('404'), { status: 404 }));
    const view = await editorView('para');
    caretAt(view, 1);
    await run();
    expect(toast.info).toHaveBeenCalledWith('Save the page first to link its blocks');
    expect(toast.error).not.toHaveBeenCalled();
    expect(writeText).not.toHaveBeenCalled();
    expect(warn).not.toHaveBeenCalled();
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
