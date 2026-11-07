/**
 * PageEditor in collaborative mode (docs/MultiEdit.MD §0 A4/A10/A11/A13, §8.2) against the REAL CodeMirror editor,
 * wired through useCollabSession's transport override to the in-memory fakeCollabServer (the protocol fixture's
 * authority). Real timers: the peer's ~100 ms push debounce and the server's long-poll run as they do in a browser.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditorView } from '@codemirror/view';
import { ChangeSet } from '@codemirror/state';

vi.mock('../api/client', () => ({
  api: {
    getCapabilities: vi.fn(),
    getProfile: vi.fn().mockResolvedValue({ editorMode: null }),
    updateProfile: vi.fn().mockResolvedValue({}),
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(),
    convertWikiToMarkdown: vi.fn(),
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
import { CapabilitiesProvider } from '../hooks/useCapabilities';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';
import { createFakeCollabServer } from '../collab/fakeCollabServer';
import { until, typeAtCaret, placeCaret, docOf, caretOf, startRename } from '../test/realEditorHarness';

let server;
let toast;
let renameAttachment;
let login;

function currentView() {
  const el = document.querySelector('.cm-editor');
  return el ? EditorView.findFromDOM(el) : null;
}

async function mount({ transport = server.transportFor('ada'), state, collab = true } = {}) {
  api.getCapabilities.mockResolvedValue(collab ? { collab: { enabled: true } } : {});
  render(
    <MemoryRouter initialEntries={[{ pathname: '/edit/Existing', state }]}>
      <CapabilitiesProvider>
        <NavigationGuardProvider>
          <Routes>
            <Route path="/edit/:name" element={<PageEditor collabTransport={transport} />} />
            <Route path="/wiki/:name" element={<div data-testid="wiki-view">WIKI VIEW</div>} />
          </Routes>
        </NavigationGuardProvider>
      </CapabilitiesProvider>
    </MemoryRouter>,
  );
  await until(() => currentView() !== null);
  return { view: currentView(), transport };
}

/** Every text update the editors pushed, as ChangeSets with the length of the document they applied to. */
function pushedChanges() {
  return server.calls.filter((c) => c.method === 'POST' && c.path === '/updates' && c.status === 200)
    .flatMap((c) => (c.body.updates || []).map((u) => ChangeSet.fromJSON(u.changes)));
}
function isWholeDocumentReplacement(cs) {
  let whole = false;
  cs.iterChanges((fromA, toA) => { if (fromA === 0 && toA === cs.length && cs.length > 0) whole = true; });
  return whole;
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  localStorage.setItem('wikantik.editor.mode', 'source');
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  useToast.mockReturnValue(toast);
  login = vi.fn().mockResolvedValue({});
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'ada', roles: ['Admin'] }, login });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  renameAttachment = vi.fn().mockResolvedValue({ ok: true });
  useAttachments.mockImplementation(() => ({
    list: [{ fileName: 'old.png', size: 10, isImage: true }],
    uploadAttachment: vi.fn(), renameAttachment, deleteAttachment: vi.fn(),
  }));
  api.getPage.mockResolvedValue({ content: 'saved page text', metadata: {}, version: 7, markupSyntax: 'markdown' });
  server = createFakeCollabServer({ initialText: 'Hello world', pollTimeoutMs: 2000 });
});

afterEach(async () => {
  cleanup();
  await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
});

describe('PageEditor collaborative mode', () => {
  it('edits the shared draft: the editor shows the session document, Publish replaces Save, drafts are off', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    expect(screen.getByTestId('editor-save')).toHaveTextContent('Publish');
    expect(screen.getByTestId('collab-status')).toHaveTextContent(/live/i);
    expect(useDraft).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }));
  });

  it('noFullDocumentReplacementReachesCollab: typing, a re-render, an attachment rename and a conversion push only minimal changes', async () => {
    server = createFakeCollabServer({ initialText: '![x](old.png) and [y](old.png)', pollTimeoutMs: 2000 });
    api.getPage.mockResolvedValue({ content: 'whatever', metadata: {}, version: 7, markupSyntax: 'wiki' });
    api.convertWikiToMarkdown.mockImplementation((text) => Promise.resolve({ markdown: `${text}\n\nconverted`, warnings: [] }));
    const { view } = await mount();
    await until(() => docOf(view).startsWith('![x]'));

    placeCaret(view, docOf(view).length);
    typeAtCaret(view, ' typed');
    // a parent re-render (the change-note input) must not touch the document
    fireEvent.change(screen.getByTestId('editor-change-note'), { target: { value: 'a note' } });
    startRename('new');
    await until(() => docOf(view) === '![x](new.png) and [y](new.png) typed');

    fireEvent.click(screen.getByRole('button', { name: 'Convert to Markdown' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Convert for everyone' }));
    await until(() => docOf(view).endsWith('converted'));
    await until(() => server.doc === docOf(view));

    const pushed = pushedChanges();
    expect(pushed.length).toBeGreaterThan(0);
    expect(pushed.filter(isWholeDocumentReplacement)).toEqual([]);
  });

  it('publishWaitsForSync: text typed just before Publish is on the server before the publish, at its version', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    placeCaret(view, 11);
    typeAtCaret(view, ' more');
    fireEvent.click(screen.getByTestId('editor-save')); // inside the peer's push debounce
    await screen.findByTestId('wiki-view');

    const publish = server.calls.find((c) => c.path === '/publish');
    expect(publish.status).toBe(200);
    const lastPush = server.calls.findLastIndex((c) => c.method === 'POST' && c.path === '/updates' && c.status === 200);
    expect(lastPush).toBeGreaterThan(-1);
    expect(lastPush).toBeLessThan(server.calls.indexOf(publish));
    expect(publish.body.version).toBe(server.version);
    expect(server.doc).toBe('Hello world more');
    expect(api.savePage).not.toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('Published');
  });

  it('aRemoteEditDoesNotMoveMyCaret: remote edits before and after the caret keep it on the same text', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    placeCaret(view, 6); // before "world"
    act(() => { server.externalReplace(0, 0, '>> '); });
    await until(() => docOf(view) === '>> Hello world');
    expect(caretOf(view)).toBe(9);
    act(() => { server.externalReplace(14, 14, '!'); });
    await until(() => docOf(view) === '>> Hello world!');
    expect(caretOf(view)).toBe(9);
    expect(docOf(view).slice(caretOf(view))).toBe('world!');
    // the preview follows the shared document
    await until(() => document.querySelector('.editor-preview')?.textContent.includes('Hello world!'));
  });

  it('disabledCollabKeepsTheSingleAuthorEditor: no capability → no join, Save saves as today', async () => {
    const transport = server.transportFor('ada');
    const join = vi.spyOn(transport, 'join');
    api.savePage.mockResolvedValue({ version: 8 });
    const { view } = await mount({ transport, collab: false });
    await until(() => docOf(view) === 'saved page text');
    expect(join).not.toHaveBeenCalled();
    expect(screen.getByTestId('editor-save')).toHaveTextContent('Save');
    expect(screen.queryByTestId('collab-status')).toBeNull();
    expect(useDraft).toHaveBeenCalledWith(expect.objectContaining({ enabled: true }));
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByTestId('wiki-view');
    expect(api.savePage).toHaveBeenCalledWith('Existing', expect.objectContaining({ content: 'saved page text', expectedVersion: 7 }));
  });

  it('a join the server refuses (collab-disabled) falls back to the single-author editor', async () => {
    server.config.disabled = true;
    const { view } = await mount();
    await until(() => docOf(view) === 'saved page text');
    expect(screen.getByTestId('editor-save')).toHaveTextContent('Save');
    expect(screen.queryByTestId('collab-status')).toBeNull();
  });

  it('a join refused for lack of edit permission shows a read-only notice', async () => {
    server.config.forbidJoin = true;
    const { view } = await mount();
    await screen.findByTestId('collab-forbidden');
    expect(view.state.readOnly).toBe(true);
    expect(screen.queryByTestId('editor-save')).toBeNull();
  });

  it('422IsRenderedAsToday: a refused publish shows the frontmatter violations', async () => {
    server.config.publishInvalid = true;
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.click(screen.getByTestId('editor-save'));
    // exactly as a refused save: the violation lands in the frontmatter summary, the toast names it, no navigation
    await until(() => toast.error.mock.calls.length > 0);
    expect(toast.error).toHaveBeenCalledWith('Fix the highlighted frontmatter fields');
    expect(document.querySelector('.fm-summary')).toHaveTextContent('1 error');
    expect(screen.queryByTestId('wiki-view')).toBeNull();
  });

  it('recoveryPanelShowsUnsyncedTextAndRestartRemounts', async () => {
    server = createFakeCollabServer({ initialText: 'Hello', maxPageBytes: 8, pollTimeoutMs: 2000 });
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello');
    placeCaret(view, 5);
    typeAtCaret(view, ' far too large');

    const panel = await screen.findByTestId('collab-recovery');
    expect(screen.getByTestId('collab-recovery-text')).toHaveTextContent('far too large');
    fireEvent.click(screen.getByRole('button', { name: 'Copy my text' }));
    await until(() => writeText.mock.calls.length === 1);
    expect(writeText.mock.calls[0][0]).toContain('far too large');

    fireEvent.click(screen.getByRole('button', { name: 'Continue editing' }));
    await until(() => currentView() !== null && currentView() !== view);
    const fresh = currentView();
    await until(() => docOf(fresh) === 'Hello');
    expect(server.doc).toBe('Hello'); // never re-applied
    expect(panel.isConnected || screen.getByTestId('collab-recovery')).toBeTruthy();
    expect(screen.getByTestId('collab-recovery-text')).toHaveTextContent('far too large');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByTestId('collab-recovery')).toBeNull();
  });

  it('authRequiredKeepsEdits: an expired sign-in keeps the typed text, guards leaving, and sends it after signing in', async () => {
    let signedOut = false;
    const base = server.transportFor('ada');
    const refuse = () => Promise.reject(Object.assign(new Error('HTTP 401'), { status: 401, body: { error: 'unauthorized' } }));
    const transport = {
      ...base,
      push: (b) => (signedOut ? refuse() : base.push(b)),
      pull: (q) => (signedOut ? refuse() : base.pull(q)),
    };
    const { view } = await mount({ transport });
    await until(() => docOf(view) === 'Hello world');
    signedOut = true;
    placeCaret(view, 11);
    typeAtCaret(view, ' kept');

    await screen.findByTestId('collab-auth-required');
    expect(docOf(view)).toBe('Hello world kept');
    fireEvent.click(screen.getByTestId('editor-cancel'));
    expect(await screen.findByTestId('guard-stay')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('guard-stay'));

    signedOut = false;
    fireEvent.click(screen.getByRole('button', { name: 'Sign in again' }));
    fireEvent.change(await screen.findByTestId('login-username'), { target: { value: 'ada' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'pw' } });
    fireEvent.click(screen.getByTestId('login-submit'));
    await until(() => server.doc === 'Hello world kept');
    expect(login).toHaveBeenCalledWith('ada', 'pw');
    await until(() => screen.queryByTestId('collab-auth-required') === null);
  });

  it('a new page passes its template text and metadata to join (A11)', async () => {
    server = createFakeCollabServer({ initialText: '', pollTimeoutMs: 2000 });
    api.getPage.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    const transport = server.transportFor('ada');
    const join = vi.spyOn(transport, 'join');
    const { view } = await mount({ transport, state: { initialContent: '# Template body', initialMetadata: { type: 'note' } } });
    await until(() => docOf(view) === '# Template body');
    expect(join).toHaveBeenCalledWith(expect.objectContaining({ initialText: '# Template body', initialMeta: { type: 'note' } }));
  });

  it('a restore joins with the old version as initial text (A11)', async () => {
    server = createFakeCollabServer({ initialText: '', pollTimeoutMs: 2000 });
    api.getPage.mockImplementation((_name, opts) => Promise.resolve(opts?.version
      ? { content: 'old version text', metadata: { type: 'old' }, version: 3 }
      : { content: 'current text', metadata: {}, version: 7, markupSyntax: 'markdown' }));
    const transport = server.transportFor('ada');
    const join = vi.spyOn(transport, 'join');
    const { view } = await mount({ transport, state: { restoreVersion: 3 } });
    await until(() => docOf(view) === 'old version text');
    expect(join).toHaveBeenCalledWith(expect.objectContaining({ initialText: 'old version text', initialMeta: { type: 'old' } }));
  });

  it('a restore into a session others already hold is offered, and applied as one minimal transaction', async () => {
    api.getPage.mockImplementation((_name, opts) => Promise.resolve(opts?.version
      ? { content: 'Hello there world', metadata: {}, version: 3 }
      : { content: 'current text', metadata: {}, version: 7, markupSyntax: 'markdown' }));
    const bob = server.transportFor('bob');
    await bob.join({});
    const { view } = await mount({ state: { restoreVersion: 3 } });
    await until(() => docOf(view) === 'Hello world');
    expect(screen.getByTestId('collab-restore-offer')).toHaveTextContent('2 editors');
    fireEvent.click(screen.getByRole('button', { name: 'Apply version 3' }));
    await until(() => docOf(view) === 'Hello there world');
    await until(() => server.doc === 'Hello there world');
    expect(pushedChanges().filter(isWholeDocumentReplacement)).toEqual([]);
  });

  it('a conversion asks first, naming how many people it changes the page for', async () => {
    api.getPage.mockResolvedValue({ content: 'x', metadata: {}, version: 7, markupSyntax: 'wiki' });
    api.convertWikiToMarkdown.mockResolvedValue({ markdown: 'converted', warnings: [] });
    const bob = server.transportFor('bob');
    await bob.join({});
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.click(screen.getByRole('button', { name: 'Convert to Markdown' }));
    expect(await screen.findByText(/changes the page for all 2 editors/)).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.convertWikiToMarkdown).not.toHaveBeenCalled();
    expect(docOf(view)).toBe('Hello world');
  });
});
