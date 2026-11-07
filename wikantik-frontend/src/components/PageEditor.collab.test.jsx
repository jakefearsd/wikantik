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
    getFrontmatterSchema: vi.fn(() => Promise.resolve({
      fields: [
        { key: 'type', label: 'Type', widget: 'TEXT' },
        { key: 'summary', label: 'Summary', widget: 'TEXT' },
        { key: 'tags', label: 'Tags', widget: 'TAGS' },
      ],
    })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
    draftBlocks: vi.fn(),
    getDiff: vi.fn(),
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
  const { container } = render(
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
  return { view: currentView(), transport, container };
}

/** A second editor (another user) in its own router, in its own container. */
async function mountSecond(user) {
  const { container } = render(
    <MemoryRouter initialEntries={[{ pathname: '/edit/Existing' }]}>
      <CapabilitiesProvider>
        <NavigationGuardProvider>
          <Routes>
            <Route path="/edit/:name" element={<PageEditor collabTransport={server.transportFor(user)} />} />
          </Routes>
        </NavigationGuardProvider>
      </CapabilitiesProvider>
    </MemoryRouter>,
  );
  await until(() => container.querySelector('.cm-editor') !== null);
  return container;
}

/** Another participant driven straight over the wire. */
async function joinAs(user) {
  const t = server.transportFor(user);
  const j = await t.join({});
  return { t, j };
}
async function pushMeta({ t, j }, metaOps) {
  await t.push({ sessionId: j.sessionId, participantId: j.participantId, version: server.version, updates: [], metaOps });
}
/** Every meta op the editors pushed (and the server accepted). */
function pushedMetaOps() {
  return server.calls.filter((c) => c.method === 'POST' && c.path === '/updates' && c.status === 200)
    .flatMap((c) => c.body.metaOps || []);
}
const tagsIn = (root) => [...root.querySelectorAll('[data-field="tags"] .chip span')].map((el) => el.textContent);
const wait = (ms) => act(async () => { await new Promise((r) => setTimeout(r, ms)); });

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
  api.validateFrontmatter.mockImplementation(() => Promise.resolve({ metadata: {}, violations: [] }));
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

  it('a422KeepsCollabModeAndMapsViolations: a refused publish shows the violations on their fields and stays together', async () => {
    server.config.publishInvalid = true;
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.click(screen.getByTestId('editor-save'));
    // exactly as a refused save: the violation lands in the frontmatter summary and on its field, the toast names it
    await until(() => toast.error.mock.calls.length > 0);
    expect(toast.error).toHaveBeenCalledWith('Fix the highlighted frontmatter fields');
    expect(document.querySelector('.fm-summary')).toHaveTextContent('1 error');
    expect(document.querySelector('[data-field="tags"]')).toHaveTextContent('tags must be a list');
    await wait(600);
    expect(screen.queryByTestId('wiki-view')).toBeNull();
    // still editing together: no single-author fallback, the participant stays
    expect(screen.queryByTestId('collab-frontmatter-blocked')).toBeNull();
    expect(screen.getByTestId('editor-save')).toHaveTextContent('Publish');
    expect(screen.getByTestId('collab-status')).toHaveTextContent(/live/i);
    expect(server.liveParticipantIds()).toHaveLength(1);
    expect(currentView()).toBe(view);
    // fixing the field clears its refused-publish violation, so Publish is available again
    expect(screen.getByTestId('editor-save')).toBeDisabled();
    server.config.publishInvalid = false;
    const tags = screen.getByLabelText('Tags');
    fireEvent.change(tags, { target: { value: 'fixed' } });
    fireEvent.keyDown(tags, { key: 'Enter' });
    await until(() => !screen.getByTestId('editor-save').disabled);
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByTestId('wiki-view');
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
    // the participant is gone and the feature is switched off: the rejoin is refused and the editor falls back
    server.config.disabled = true;
    server.expireParticipant(server.liveParticipantIds()[0]);
    placeCaret(view, 3); // a presence push meets the expired participant
    await until(() => screen.getByTestId('editor-save').textContent === 'Save');
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

  it('aBlockingFrontmatterErrorIsFixableInCollabMode: the error only disables Publish; fixing the field enables it', async () => {
    api.validateFrontmatter.mockImplementation(({ frontmatter } = {}) => Promise.resolve(/bogus/.test(frontmatter || '')
      ? { metadata: {}, violations: [{ field: 'type', severity: 'ERROR', code: 'type.bad', message: 'bad type' }] }
      : { metadata: {}, violations: [] }));
    server = createFakeCollabServer({ initialText: 'Hello world', initialMeta: { type: 'bogus' }, pollTimeoutMs: 2000 });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    await until(() => screen.getByTestId('editor-save').disabled);
    expect(screen.getByTestId('editor-save')).toHaveTextContent('Publish');
    expect(screen.queryByTestId('collab-frontmatter-blocked')).toBeNull();
    expect(server.liveParticipantIds()).toHaveLength(1);
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'note' } });
    await until(() => server.metadata.type === 'note');
    await until(() => !screen.getByTestId('editor-save').disabled);
    expect(currentView()).toBe(view);
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByTestId('wiki-view');
    expect(server.calls.find((c) => c.path === '/publish').status).toBe(200);
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
  it('frontmatterIsEditableInCollabMode: a tag added in the form reaches the server as an add, and a second editor shows it', async () => {
    const { view, container: ada } = await mount();
    await until(() => docOf(view) === 'Hello world');
    expect(screen.queryByTestId('collab-frontmatter-locked')).toBeNull();
    const bob = await mountSecond('bob');
    const input = within(ada).getByLabelText('Tags');
    fireEvent.change(input, { target: { value: 'x' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(tagsIn(ada)).toEqual(['x']);
    await until(() => pushedMetaOps().some((op) => op.field === 'tags' && op.add === 'x'));
    expect(pushedMetaOps().filter((op) => op.field === 'tags')).toEqual([{ field: 'tags', add: 'x' }]);
    expect(server.metadata.tags).toEqual(['x']);
    await until(() => tagsIn(bob).includes('x'));
  });

  it('aRemoteOpOnMyFocusedFieldShowsTheIndicatorAndKeepsMyInput, then applies it on blur when I changed nothing', async () => {
    server = createFakeCollabServer({ initialText: 'Hello world', initialMeta: { summary: 'Original' }, pollTimeoutMs: 2000 });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    await until(() => screen.getByLabelText('Summary').value === 'Original');
    fireEvent.focus(screen.getByLabelText('Summary'));
    const bob = await joinAs('bob');
    await pushMeta(bob, [{ field: 'summary', value: 'Bobs' }]);
    await until(() => screen.queryByTestId('fm-remote-summary') !== null);
    expect(screen.getByTestId('fm-remote-summary')).toHaveTextContent('changed by bob');
    expect(screen.getByLabelText('Summary')).toHaveValue('Original');
    fireEvent.blur(screen.getByLabelText('Summary'), { relatedTarget: document.body });
    await until(() => screen.getByLabelText('Summary').value === 'Bobs');
    expect(screen.queryByTestId('fm-remote-summary')).toBeNull();
    await wait(600);
    expect(server.metadata.summary).toBe('Bobs');
  });

  it('onBlurMyValueIsSentLastWriterWins: a value I typed while focused wins over the remote one on blur', async () => {
    server = createFakeCollabServer({ initialText: 'Hello world', initialMeta: { summary: 'Original' }, pollTimeoutMs: 2000 });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.focus(screen.getByLabelText('Summary'));
    fireEvent.change(screen.getByLabelText('Summary'), { target: { value: 'Mine' } });
    await until(() => server.metadata.summary === 'Mine');
    const bob = await joinAs('bob');
    await pushMeta(bob, [{ field: 'summary', value: 'Bobs' }]);
    await until(() => screen.queryByTestId('fm-remote-summary') !== null);
    expect(screen.getByLabelText('Summary')).toHaveValue('Mine');
    expect(server.metadata.summary).toBe('Bobs');
    fireEvent.blur(screen.getByLabelText('Summary'), { relatedTarget: document.body });
    await until(() => server.metadata.summary === 'Mine');
    expect(screen.getByLabelText('Summary')).toHaveValue('Mine');
    expect(screen.queryByTestId('fm-remote-summary')).toBeNull();
  });

  it('rawYamlEditsBecomeFieldOps: a Raw YAML edit is sent as field ops, a set field as an add', async () => {
    server = createFakeCollabServer({ initialText: 'Hello world', initialMeta: { tags: ['a'], status: 'draft' }, pollTimeoutMs: 2000 });
    api.validateFrontmatter.mockImplementation(({ frontmatter } = {}) => Promise.resolve(/status: active/.test(frontmatter || '')
      ? { metadata: { tags: ['a', 'b'], status: 'active' }, violations: [] }
      : { metadata: {}, violations: [] }));
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.click(screen.getByRole('tab', { name: 'Raw YAML' }));
    const raw = screen.getByLabelText('Raw frontmatter YAML');
    fireEvent.change(raw, { target: { value: 'tags: [a, b]\nstatus: active' } });
    fireEvent.blur(raw);
    await until(() => server.metadata.status === 'active');
    expect(server.metadata.tags).toEqual(['a', 'b']);
    const ops = pushedMetaOps();
    expect(ops).toEqual(expect.arrayContaining([{ field: 'tags', add: 'b' }, { field: 'status', value: 'active' }]));
    expect(ops.some((op) => op.field === 'tags' && 'value' in op)).toBe(false);
  });

  it('externalChangeShowsAToastWithViewDiff: the diff of the outside save opens in a dialog', async () => {
    api.getDiff.mockResolvedValue({ diffHtml: '<p class="diff-probe">changed lines</p>' });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    act(() => { server.externalChange({ source: 'wiki-agent', text: 'Hello brave world' }); });
    await until(() => docOf(view) === 'Hello brave world');
    await until(() => toast.info.mock.calls.some(([m]) => m === 'Updated by wiki-agent'));
    const notice = screen.getByTestId('collab-external-change');
    expect(notice).toHaveTextContent('Updated by wiki-agent');
    fireEvent.click(within(notice).getByRole('button', { name: 'View diff' }));
    const dialog = await screen.findByRole('dialog');
    await until(() => dialog.querySelector('.diff-probe') !== null);
    expect(api.getDiff).toHaveBeenCalledWith('Existing', 7, 8);
  });

  it('conflictFieldsAreFlaggedOnTheForm: a field an outside save also changed says mine was kept', async () => {
    server = createFakeCollabServer({ initialText: 'Hello world', initialMeta: { summary: 'Original' }, pollTimeoutMs: 2000 });
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.change(screen.getByLabelText('Summary'), { target: { value: 'Mine' } });
    await until(() => server.metadata.summary === 'Mine');
    act(() => { server.externalChange({ source: 'wiki-agent', conflictFields: ['summary'] }); });
    await until(() => screen.queryByTestId('fm-conflict-summary') !== null);
    expect(screen.getByTestId('fm-conflict-summary')).toHaveTextContent('kept yours; wiki-agent also changed this');
    expect(screen.getByLabelText('Summary')).toHaveValue('Mine');
  });

  it('pageRenamedUpdatesTheUrlWithoutReloading: the editor follows the new name on the same session and view', async () => {
    const transport = server.transportFor('ada');
    const join = vi.spyOn(transport, 'join');
    const { view } = await mount({ transport });
    await until(() => docOf(view) === 'Hello world');
    act(() => { server.renamePage('Renamed'); });
    await until(() => screen.getByTestId('page-editor').dataset.pageName === 'Renamed');
    expect(screen.getByTestId('editor-heading')).toHaveTextContent('Edit: Renamed');
    await wait(300);
    expect(currentView()).toBe(view);
    expect(join).toHaveBeenCalledTimes(1);
    expect(api.getPage).not.toHaveBeenCalledWith('Renamed', expect.anything());
    expect(api.getPage.mock.calls.filter(([n]) => n === 'Renamed')).toEqual([]);
    expect(server.calls.some((c) => c.path === '/leave')).toBe(false);
    placeCaret(view, 11);
    typeAtCaret(view, '!');
    await until(() => server.doc === 'Hello world!');
  });

  it('pageDeletedShowsTheFrozenBannerAndRecreate: Publish becomes Recreate page and recreates it', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    act(() => { server.freeze(); });
    const banner = await screen.findByTestId('collab-frozen');
    expect(banner).toHaveTextContent('This page was deleted — copy your text or recreate the page');
    await until(() => currentView().state.readOnly);
    const save = screen.getByTestId('editor-save');
    expect(save).toHaveTextContent('Recreate page');
    expect(save).not.toBeDisabled();
    fireEvent.click(save);
    await screen.findByTestId('wiki-view');
    expect(server.calls.find((c) => c.path === '/publish').status).toBe(200);
  });

  it('recreateWithoutCreatePagesExplainsWhy', async () => {
    server.setCanCreate(false);
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    act(() => { server.freeze(); });
    await screen.findByTestId('collab-frozen');
    fireEvent.click(screen.getByTestId('editor-save'));
    await until(() => screen.queryByTestId('editor-error') !== null);
    expect(screen.getByTestId('editor-error')).toHaveTextContent(/permission to create pages/);
    expect(server.calls.find((c) => c.path === '/publish').reason).toBe('no-create');
    expect(screen.queryByTestId('wiki-view')).toBeNull();
  });

  it('evictedForLostEditGoesReadOnlyWithAMessage', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    act(() => { server.evict(server.liveParticipantIds()[0], 'lost-edit'); });
    const banner = await screen.findByTestId('collab-evicted');
    expect(banner).toHaveTextContent(/no longer have permission/i);
    expect(banner).toHaveTextContent(/discarded/);
    expect(currentView().state.readOnly).toBe(true);
    expect(screen.queryByTestId('collab-forbidden')).toBeNull();
    expect(screen.queryByTestId('editor-save')).toBeNull();
  });

  it('signedOutKeepsEditsAndPromptsLogin: after signing in again the kept text reaches the shared draft', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    act(() => { server.evict(server.liveParticipantIds()[0], 'signed-out'); });
    await screen.findByTestId('collab-auth-required');
    placeCaret(view, 11);
    typeAtCaret(view, ' kept');
    await wait(300);
    expect(docOf(view)).toBe('Hello world kept');
    expect(server.doc).toBe('Hello world');
    fireEvent.click(screen.getByRole('button', { name: 'Sign in again' }));
    fireEvent.change(await screen.findByTestId('login-username'), { target: { value: 'ada' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'pw' } });
    fireEvent.click(screen.getByTestId('login-submit'));
    await until(() => server.doc === 'Hello world kept');
    await until(() => screen.queryByTestId('collab-auth-required') === null);
  });

  it('restoreIsRefusedWhenAPresenceEntryArrivesBeforeTheClick: the alone check reads the live presence map', async () => {
    draftHook.draft = { content: 'Hello offline world', savedAt: Date.now() - 60000 };
    const base = server.transportFor('ada');
    let armed = false;
    let clickNow = false;
    let clicked = false;
    const transport = {
      ...base,
      pull: (q) => {
        if (clickNow) {
          // the presence map has reached the peer, the editor has not re-rendered yet: click the still-shown Restore
          clickNow = false;
          const btn = screen.queryByRole('button', { name: 'Restore into the shared draft' });
          if (btn) { fireEvent.click(btn); clicked = true; }
        }
        return base.pull(q).then((res) => {
          if (armed && Object.keys(res.presence || {}).length) { armed = false; clickNow = true; }
          return res;
        });
      },
    };
    const { view } = await mount({ transport });
    await until(() => docOf(view) === 'Hello world');
    expect(within(screen.getByTestId('collab-offline-draft')).getByRole('button', { name: 'Restore into the shared draft' })).toBeInTheDocument();
    armed = true;
    await joinAs('bob');
    await until(() => clicked);
    await wait(300);
    expect(docOf(view)).toBe('Hello world');
    expect(server.doc).toBe('Hello world');
    expect(draftHook.clearDraft).not.toHaveBeenCalled();
    expect(toast.info).toHaveBeenCalledWith(expect.stringMatching(/joined/i));
    expect(within(screen.getByTestId('collab-offline-draft')).queryByRole('button', { name: 'Restore into the shared draft' })).toBeNull();
  });

  it('thePresenceBarReplacesTheEditingCount', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    await joinAs('bob');
    await until(() => screen.queryByRole('status', { name: 'Collaborators' })?.textContent.includes('2 editing'));
    const bar = screen.getByRole('status', { name: 'Collaborators' });
    expect(bar).toHaveTextContent('ada');
    expect(bar).toHaveTextContent('bob');
    expect(screen.getByTestId('collab-status').textContent).not.toMatch(/·/);
  });

  it('theGuardCoversUnsentMetaEdits: leaving with a field edit not yet acknowledged asks first', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.change(screen.getByLabelText('Summary'), { target: { value: 'Mine' } });
    fireEvent.click(screen.getByTestId('editor-cancel'));
    expect(await screen.findByTestId('guard-stay')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('guard-stay'));
    await until(() => server.metadata.summary === 'Mine');
    await wait(100);
    fireEvent.click(screen.getByTestId('editor-cancel'));
    await screen.findByTestId('wiki-view');
  });

  it('Publish sends a field edit still inside its debounce window before publishing', async () => {
    const { view } = await mount();
    await until(() => docOf(view) === 'Hello world');
    fireEvent.change(screen.getByLabelText('Summary'), { target: { value: 'Mine' } });
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByTestId('wiki-view');
    const publish = server.calls.findIndex((c) => c.path === '/publish');
    const metaPush = server.calls.findIndex((c) => c.path === '/updates' && c.method === 'POST' && (c.body.metaOps || []).some((op) => op.field === 'summary'));
    expect(metaPush).toBeGreaterThan(-1);
    expect(metaPush).toBeLessThan(publish);
    expect(server.metadata.summary).toBe('Mine');
  });
});
