/**
 * Paste-as-Markdown inside the full PageEditor with the REAL CodeMirror: the converted paste reaches the page body,
 * the first conversion of a session explains the plain-text escape hatch, dropped embedded images are reported, and
 * the "Paste as plain text" command reads the clipboard and inserts it verbatim.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, fireEvent, waitFor, act, cleanup } from '@testing-library/react';
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


function paste(view, { text = '', html = '' }) {
  const data = { 'text/plain': text, 'text/html': html };
  const event = new Event('paste', { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'clipboardData', { value: { getData: (t) => data[t] || '', files: [], types: [] } });
  act(() => { view.contentDOM.dispatchEvent(event); });
}

const caretAtEnd = (view) => act(() => { view.dispatch({ selection: { anchor: view.state.doc.length } }); });

let toast;
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
  api.getPage.mockResolvedValue({ content: 'hello world', metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockResolvedValue({ version: 2 });
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

describe('PageEditor paste as Markdown', () => {
  it('a rich paste lands in the saved body; only the first conversion shows the plain-text hint', async () => {
    const view = await editorView();
    caretAtEnd(view);
    paste(view, { text: ' bold', html: '<span> </span><b>bold</b>' });
    expect(view.state.doc.toString()).toBe('hello world **bold**');
    expect(toast.info).toHaveBeenCalledTimes(1);
    expect(toast.info.mock.calls[0][0]).toMatch(/Pasted as Markdown.*Ctrl\+Shift\+V.*plain text/);
    paste(view, { text: ' again', html: '<span> </span><i>again</i>' });
    expect(view.state.doc.toString()).toBe('hello world **bold** *again*');
    expect(toast.info).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    await waitFor(() => expect(api.savePage).toHaveBeenCalled());
    expect(api.savePage.mock.calls[0][1].content).toBe('hello world **bold** *again*');
  });

  it('reports embedded images the paste could not carry, every time', async () => {
    const view = await editorView();
    caretAtEnd(view);
    const html = '<p><b>Chart</b></p><img src="blob:https://x.test/1"><img src="data:image/svg+xml;base64,PHN2Zy8+">';
    paste(view, { text: 'Chart', html });
    paste(view, { text: 'Chart', html });
    const messages = toast.info.mock.calls.map((c) => c[0]);
    expect(messages.filter((m) => /2 embedded images could not be pasted.*attachments/.test(m))).toHaveLength(2);
  });

  it('an embedded data: image uploads in place on a saved page and becomes attachment markup', async () => {
    const uploadAttachment = vi.fn(() => Promise.resolve({}));
    useAttachments.mockImplementation(() => ({ list: [], uploadAttachment, renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
    const view = await editorView();
    caretAtEnd(view);
    const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
    paste(view, { text: 'x', html: `<h2>Pic</h2><p>above</p><img src="data:image/png;base64,${png}"><p>below</p>` });
    await waitFor(() => expect(uploadAttachment).toHaveBeenCalledTimes(1));
    const name = uploadAttachment.mock.calls[0][1];
    expect(name).toMatch(/^pasted-image-\d{8}-\d{6}-1\.png$/);
    expect(uploadAttachment.mock.calls[0][0]).toBeInstanceOf(File);
    await waitFor(() => expect(view.state.doc.toString()).toBe(
      `hello world## Pic\n\nabove\n\n![${name.replace('.png', '')}](${name})\n\nbelow`,
    ));
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('"Paste as plain text" inserts the clipboard text verbatim over the selection', async () => {
    const readText = vi.fn(() => Promise.resolve('**literal** <b>x</b>'));
    Object.defineProperty(navigator, 'clipboard', { value: { readText }, configurable: true });
    const view = await editorView();
    act(() => { view.dispatch({ selection: { anchor: 6, head: 11 } }); });
    await act(async () => { await getCommand('paste-plain').run(); });
    expect(view.state.doc.toString()).toBe('hello **literal** <b>x</b>');
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('"Paste as plain text" explains the shortcut when the browser blocks clipboard reads', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { readText: () => Promise.reject(new Error('denied')) }, configurable: true });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const view = await editorView();
    await act(async () => { await getCommand('paste-plain').run(); });
    expect(view.state.doc.toString()).toBe('hello world');
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/Ctrl\+Shift\+V/));
    expect(warn).toHaveBeenCalled();
  });
});
