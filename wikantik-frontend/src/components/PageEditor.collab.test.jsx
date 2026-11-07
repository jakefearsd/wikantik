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
import { CapabilitiesProvider } from '../hooks/useCapabilities';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';
import { createFakeCollabServer } from '../collab/fakeCollabServer';
import { until, typeAtCaret, placeCaret, docOf, caretOf, startRename, deferred } from '../test/realEditorHarness';
import { getCommand } from '../commands/registry';

let server;
let toast;
let renameAttachment;
let login;
let draftHook;

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
  draftHook = { draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() };
  useDraft.mockImplementation(() => draftHook);
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
    // no localStorage autosave: the server holds the draft
    placeCaret(view, 11);
    typeAtCaret(view, '!');
    await act(async () => { await new Promise((r) => setTimeout(r, 900)); });
    expect(draftHook.saveDraft).not.toHaveBeenCalled();
  });

  it('noFullDocumentReplacementReachesCollab: typing, a re-render and an attachment rename push only minimal changes', async () => {
    server = createFakeCollabServer({ initialText: '![x](old.png) and [y](old.png)', pollTimeoutMs: 2000 });
    const { view } = await mount();
    await until(() => docOf(view).startsWith('![x]'));

    placeCaret(view, docOf(view).length);
    typeAtCaret(view, ' typed');
    // a parent re-render (the change-note input) must not touch the document
    fireEvent.change(screen.getByTestId('editor-change-note'), { target: { value: 'a note' } });
    startRename('new');
    await until(() => docOf(view) === '![x](new.png) and [y](new.png) typed');
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

  it('422IsRenderedAsToday: a refused publish shows the frontmatter violations, then falls back to single-author to fix them', async () => {
    server.config.publishInvalid = true;
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.click(screen.getByTestId('editor-save'));
    // exactly as a refused save: the violation lands in the frontmatter summary, the toast names it, no navigation
    await until(() => toast.error.mock.calls.length > 0);
    expect(toast.error).toHaveBeenCalledWith('Fix the highlighted frontmatter fields');
    expect(document.querySelector('.fm-summary')).toHaveTextContent('1 error');
    expect(screen.queryByTestId('wiki-view')).toBeNull();
    // the frontmatter cannot be fixed while editing together: the editor falls back to single-author with a notice
    await screen.findByTestId('collab-frontmatter-blocked');
    await until(() => screen.getByTestId('editor-save').textContent === 'Save');
    expect(server.liveParticipantIds()).toEqual([]);
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

  it('Convert to Markdown is not offered while editing together (publish does not carry the markup flag yet)', async () => {
    api.getPage.mockResolvedValue({ content: 'x', metadata: {}, version: 7, markupSyntax: 'wiki' });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    expect(screen.queryByRole('button', { name: 'Convert to Markdown' })).toBeNull();
    expect(screen.getByTestId('collab-no-convert')).toBeInTheDocument();
  });

  it('Ctrl+S while a restart is joining never falls through to the single-author save', async () => {
    server = createFakeCollabServer({ initialText: 'Hello', maxPageBytes: 8, pollTimeoutMs: 2000 });
    const base = server.transportFor('ada');
    const gate = deferred();
    let joins = 0;
    const transport = { ...base, join: (b) => { joins += 1; return joins === 1 ? base.join(b) : gate.promise.then(() => base.join(b)); } };
    const { view } = await mount({ transport });
    await until(() => docOf(view) === 'Hello');
    placeCaret(view, 5);
    typeAtCaret(view, ' far too large');
    await screen.findByTestId('collab-recovery');
    fireEvent.click(screen.getByRole('button', { name: 'Continue editing' }));
    await screen.findByTestId('collab-joining');
    fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    await until(() => screen.queryByTestId('editor-error') !== null);
    expect(api.savePage).not.toHaveBeenCalled();
    expect(screen.getByTestId('editor-error')).toHaveTextContent(/Not connected/);
    gate.resolve();
    await until(() => currentView() !== null && currentView() !== view);
  });

  it('copy block link: a remote insert before the block while the server answers still puts the ^id on it', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    server = createFakeCollabServer({ initialText: 'intro\n\npara', pollTimeoutMs: 2000 });
    const answer = deferred();
    api.draftBlocks.mockReturnValue(answer.promise);
    const { view } = await mount();
    await until(() => docOf(view) === 'intro\n\npara');
    placeCaret(view, 8);
    let done;
    act(() => { done = getCommand('copy-block-link').run(); });
    await until(() => api.draftBlocks.mock.calls.length === 1);
    act(() => { server.externalReplace(0, 0, '# Title\n\n'); });
    await until(() => docOf(view) === '# Title\n\nintro\n\npara');
    await act(async () => {
      answer.resolve({ id: 'bbbbbb', change: { from: 11, to: 11, insert: ' ^bbbbbb' }, block: { index: 1, from: 7, to: 11 } });
      await done;
    });
    expect(docOf(view)).toBe('# Title\n\nintro\n\npara ^bbbbbb');
    await until(() => server.doc === '# Title\n\nintro\n\npara ^bbbbbb');
    expect(toast.success).toHaveBeenCalledWith('Block link copied');
  });

  it('copy block link: an answer arriving after a resync remount is not dispatched into the destroyed view', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    server = createFakeCollabServer({ initialText: 'para', maxPageBytes: 8, pollTimeoutMs: 2000 });
    const answer = deferred();
    api.draftBlocks.mockReturnValue(answer.promise);
    const { view } = await mount();
    await until(() => docOf(view) === 'para');
    placeCaret(view, 1);
    let done;
    act(() => { done = getCommand('copy-block-link').run(); });
    await until(() => api.draftBlocks.mock.calls.length === 1);
    placeCaret(view, 4);
    typeAtCaret(view, ' far too large');
    await screen.findByTestId('collab-recovery');
    fireEvent.click(screen.getByRole('button', { name: 'Continue editing' }));
    await until(() => currentView() !== null && currentView() !== view);
    const fresh = currentView();
    await act(async () => {
      answer.resolve({ id: 'bbbbbb', change: { from: 4, to: 4, insert: ' ^bbbbbb' }, block: { index: 0, from: 0, to: 4 } });
      await done;
    });
    expect(docOf(fresh)).toBe('para');
    expect(docOf(view)).not.toContain('^bbbbbb');
    expect(toast.error).toHaveBeenCalledWith('The text changed — copy the block link again');
    expect(toast.success).not.toHaveBeenCalledWith('Block link copied');
  });

  it('copy block link: an answer arriving after the editor was replaced (fallback to single-author) is not applied', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    server = createFakeCollabServer({ initialText: 'para', pollTimeoutMs: 2000 });
    const answer = deferred();
    api.draftBlocks.mockReturnValue(answer.promise);
    const { view } = await mount();
    await until(() => docOf(view) === 'para');
    placeCaret(view, 1);
    let done;
    act(() => { done = getCommand('copy-block-link').run(); });
    await until(() => api.draftBlocks.mock.calls.length === 1);
    server.config.publishInvalid = true;
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByTestId('collab-frontmatter-blocked');
    await until(() => currentView() !== null && currentView() !== view);
    const fresh = currentView();
    await act(async () => {
      answer.resolve({ id: 'bbbbbb', change: { from: 4, to: 4, insert: ' ^bbbbbb' }, block: { index: 0, from: 0, to: 4 } });
      await done;
    });
    expect(docOf(fresh)).toBe('para');
    expect(toast.error).toHaveBeenCalledWith('The text changed — copy the block link again');
    expect(toast.success).not.toHaveBeenCalledWith('Block link copied');
  });

  it('frontmatter with blocking errors never edits together: single-author with a notice, the session is left', async () => {
    api.validateFrontmatter.mockResolvedValue({ metadata: {}, violations: [{ field: 'type', severity: 'ERROR', code: 'x', message: 'bad type' }] });
    server = createFakeCollabServer({ initialText: 'Hello world', initialMeta: { type: 'bogus' }, pollTimeoutMs: 2000 });
    await mount();
    expect(await screen.findByTestId('collab-frontmatter-blocked')).toHaveTextContent(/reload/i);
    await until(() => screen.getByTestId('editor-save').textContent === 'Save');
    expect(screen.queryByTestId('collab-status')).toBeNull();
    await until(() => server.liveParticipantIds().length === 0);
    const view = currentView();
    expect(view.state.readOnly).toBe(false);
  });

  it('Discard asks first, naming the editors, then discards the shared draft', async () => {
    const bob = server.transportFor('bob');
    await bob.join({});
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.click(screen.getByTestId('collab-discard'));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(/for all 2 editors/);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(server.calls.some((c) => c.path === '/discard')).toBe(false);
    fireEvent.click(screen.getByTestId('collab-discard'));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Discard for everyone' }));
    await until(() => server.calls.some((c) => c.path === '/discard' && c.status === 204));
    await until(() => toast.success.mock.calls.some(([m]) => m === 'Shared draft discarded'));
  });

  it('an attachment rename while out of sync is queued and applied after the remount, not lost', async () => {
    server = createFakeCollabServer({ initialText: 'A ![x](old.png)', maxPageBytes: 20, pollTimeoutMs: 2000 });
    const { view } = await mount();
    await until(() => docOf(view) === 'A ![x](old.png)');
    placeCaret(view, docOf(view).length);
    typeAtCaret(view, ' far too large');
    await screen.findByTestId('collab-recovery');
    startRename('new');
    await until(() => renameAttachment.mock.calls.length === 1);
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
    expect(docOf(view)).toBe('A ![x](old.png) far too large'); // nothing written into the stopped editor
    fireEvent.click(screen.getByRole('button', { name: 'Continue editing' }));
    await until(() => currentView() !== null && currentView() !== view);
    await until(() => docOf(currentView()) === 'A ![x](new.png)');
    await until(() => server.doc === 'A ![x](new.png)');
  });

  it('an attachment rename while the page is frozen says the links were not updated', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    server.config.frozen = true;
    placeCaret(view, 11);
    typeAtCaret(view, '!');
    await screen.findByTestId('collab-frozen');
    startRename('new');
    await until(() => toast.error.mock.calls.some(([m]) => /read-only/.test(m)));
  });

  it('leaving is guarded while an undismissed recovery text is shown', async () => {
    server = createFakeCollabServer({ initialText: 'Hello', maxPageBytes: 8, pollTimeoutMs: 2000 });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello');
    placeCaret(view, 5);
    typeAtCaret(view, ' far too large');
    await screen.findByTestId('collab-recovery');
    fireEvent.click(screen.getByRole('button', { name: 'Continue editing' }));
    await until(() => currentView() !== null && currentView() !== view);
    await until(() => docOf(currentView()) === 'Hello');
    fireEvent.click(screen.getByTestId('editor-cancel'));
    expect(await screen.findByTestId('guard-stay')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('guard-stay'));
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    fireEvent.click(screen.getByTestId('editor-cancel'));
    await screen.findByTestId('wiki-view');
  });

  it('an offline draft is offered once and restored into the shared draft as one local edit', async () => {
    draftHook.draft = { content: 'Hello offline world', savedAt: Date.now() - 60000 };
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    const offer = screen.getByTestId('collab-offline-draft');
    fireEvent.click(within(offer).getByRole('button', { name: 'Restore into the shared draft' }));
    await until(() => docOf(view) === 'Hello offline world');
    await until(() => server.doc === 'Hello offline world');
    expect(draftHook.clearDraft).toHaveBeenCalled();
    expect(screen.queryByTestId('collab-offline-draft')).toBeNull();
    expect(pushedChanges().filter(isWholeDocumentReplacement)).toEqual([]);
  });

  it('with others in the session an offline draft is only offered as text to copy, never applied to the shared draft', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    draftHook.draft = { content: 'Hello stale offline world', savedAt: Date.now() - 60000 };
    const bob = server.transportFor('bob');
    const bj = await bob.join({});
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    const offer = screen.getByTestId('collab-offline-draft');
    expect(offer).toHaveTextContent(/others are editing/i);
    expect(within(offer).queryByRole('button', { name: 'Restore into the shared draft' })).toBeNull();
    expect(within(offer).getByTestId('collab-offline-draft-text')).toHaveTextContent('Hello stale offline world');
    fireEvent.click(within(offer).getByRole('button', { name: 'Copy my offline text' }));
    await until(() => writeText.mock.calls.length === 1);
    expect(writeText.mock.calls[0][0]).toBe('Hello stale offline world');
    await act(async () => { await new Promise((r) => setTimeout(r, 300)); });
    expect(server.doc).toBe('Hello world');
    expect(docOf(view)).toBe('Hello world');
    expect(pushedChanges()).toEqual([]);
    // once the other editor has left, it may be restored
    await bob.leave({ sessionId: bj.sessionId, participantId: bj.participantId });
    await until(() => within(screen.getByTestId('collab-offline-draft')).queryByRole('button', { name: 'Restore into the shared draft' }) !== null);
  });

  it('an upload placeholder queued while out of sync lands next to the text it was placed at, not at a stale offset', async () => {
    server = createFakeCollabServer({ initialText: 'Intro\n\nOutro', maxPageBytes: 14, pollTimeoutMs: 2000 });
    useAttachments.mockImplementation(() => ({
      list: [], uploadAttachment: vi.fn(() => new Promise(() => {})), renameAttachment, deleteAttachment: vi.fn(),
    }));
    const { view } = await mount();
    await until(() => docOf(view) === 'Intro\n\nOutro');
    placeCaret(view, 0);
    typeAtCaret(view, 'too much text');
    await screen.findByTestId('collab-recovery');
    placeCaret(view, 'too much textIntro\n\n'.length); // right before "Outro"
    const file = new File(['x'], 'pic.png', { type: 'image/png' });
    fireEvent.change(screen.getByTestId('editor-image-input'), { target: { files: [file] } });
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
    expect(docOf(view)).toBe('too much textIntro\n\nOutro'); // queued, not written into the stopped editor
    fireEvent.click(screen.getByRole('button', { name: 'Continue editing' }));
    await until(() => currentView() !== null && currentView() !== view);
    await until(() => docOf(currentView()).includes('Uploading pic.png'));
    expect(docOf(currentView())).toBe('Intro\n\n![Uploading pic.png…]()Outro');
  });

  it('a declined offline draft is cleared and the shared draft is untouched', async () => {
    draftHook.draft = { content: 'Something else', savedAt: Date.now() - 60000 };
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.click(within(screen.getByTestId('collab-offline-draft')).getByRole('button', { name: 'Discard it' }));
    expect(draftHook.clearDraft).toHaveBeenCalled();
    expect(screen.queryByTestId('collab-offline-draft')).toBeNull();
    expect(docOf(view)).toBe('Hello world');
  });
});
