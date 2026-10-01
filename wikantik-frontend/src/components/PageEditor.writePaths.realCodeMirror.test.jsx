/**
 * PageEditor write paths that run at an unpredictable moment relative to typing — attachment rename, wiki→Markdown
 * conversion, draft restore and the conflict reload — against the REAL CodeMirror editor.
 *
 * react-codemirror defers a `value` change that arrives within its typing latch and later replays it as a
 * whole-document replacement (no onChange): keystrokes typed meanwhile vanish and the React body diverges from
 * what the editor shows, so a save can persist text the user never saw. Each path must instead apply its edit
 * through the view as one normal transaction. The latch clock is faked (see realEditorHarness), so "the latch
 * is still armed" is a deterministic state rather than a timing accident.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent, screen } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: {
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
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';
import {
  mountRealEditor, fakeLatchClock, releaseLatchClock, advanceLatch, until, flush, typeAtCaret, placeCaret,
  docOf, caretOf, deferred, startRename, saveAndExpectVisibleText,
} from '../test/realEditorHarness';

const mount = (text) => mountRealEditor(PageEditor, NavigationGuardProvider, text);

let renameAttachment;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  renameAttachment = vi.fn();
  useAttachments.mockImplementation(() => ({
    list: [{ fileName: 'old.png', size: 10, isImage: true }],
    uploadAttachment: vi.fn(), renameAttachment, deleteAttachment: vi.fn(),
  }));
  api.getPage.mockResolvedValue({ content: 'hello world', metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockResolvedValue({ version: 2 });
});

afterEach(async () => {
  cleanup();
  releaseLatchClock();
  await act(async () => { await Promise.resolve(); });
});

describe('attachment rename while typing', () => {
  it('(a) rewrites both links in the live text; keystrokes before and after it survive; caret stays after them', async () => {
    const doc = '![x](old.png) and [y](old.png)\n';
    api.getPage.mockResolvedValue({ content: doc, metadata: {}, version: 1, markupSyntax: 'markdown' });
    const rename = deferred();
    renameAttachment.mockReturnValue(rename.promise);
    const view = await mount(doc);
    fakeLatchClock();

    startRename('new');
    expect(renameAttachment).toHaveBeenCalledWith('old.png', 'new.png');
    placeCaret(view, doc.length);
    typeAtCaret(view, 'abc');                                       // arms the typing latch
    rename.resolve({ ok: true });
    await flush();                                                  // handleRename's continuation runs now
    typeAtCaret(view, 'def');
    advanceLatch();                                                 // > 1 s: the latch expires

    expect(docOf(view)).toBe('![x](new.png) and [y](new.png)\nabcdef');
    expect(caretOf(view)).toBe(docOf(view).length);
    saveAndExpectVisibleText(api, view);
  });

  it('(b) rename resolving mid-word with keystrokes spread over 900 ms; the caret between the links is mapped', async () => {
    const doc = '![x](old.png) middle [y](old.png)';
    api.getPage.mockResolvedValue({ content: doc, metadata: {}, version: 1, markupSyntax: 'markdown' });
    const rename = deferred();
    renameAttachment.mockReturnValue(rename.promise);
    const view = await mount(doc);
    fakeLatchClock();

    startRename('renamed');
    placeCaret(view, doc.indexOf(' [y]'));                          // right after "middle"
    typeAtCaret(view, ' wo');
    rename.resolve({ ok: true });
    await flush();
    // The rewrite is in the editor at once, not parked behind the latch.
    expect(docOf(view)).toBe('![x](renamed.png) middle wo [y](renamed.png)');
    for (const [ch, wait] of [['r', 120], ['l', 180], ['d', 250], ['!', 350]]) {
      typeAtCaret(view, ch);
      advanceLatch(wait);                                           // 900 ms in total, some gaps outlive the latch
    }
    advanceLatch();

    const expected = '![x](renamed.png) middle world! [y](renamed.png)';
    expect(docOf(view)).toBe(expected);
    expect(caretOf(view)).toBe(expected.indexOf(' [y]'));
    saveAndExpectVisibleText(api, view);
  });
});

describe('attachment rename with the caret elsewhere', () => {
  it('a caret parked inside the renamed name stays inside the new name (it does not jump to the start)', async () => {
    const doc = 'intro\n![x](old.png) tail';
    api.getPage.mockResolvedValue({ content: doc, metadata: {}, version: 1, markupSyntax: 'markdown' });
    const rename = deferred();
    renameAttachment.mockReturnValue(rename.promise);
    const view = await mount(doc);
    fakeLatchClock();

    startRename('renamed');
    const nameAt = doc.indexOf('old.png');
    placeCaret(view, nameAt + 3);                                   // clicked into "old|.png", not typing
    rename.resolve({ ok: true });
    await flush();

    expect(docOf(view)).toBe('intro\n![x](renamed.png) tail');
    expect(caretOf(view)).toBeGreaterThanOrEqual(nameAt);
    expect(caretOf(view)).toBeLessThanOrEqual(nameAt + 'renamed.png'.length);
    advanceLatch();
    saveAndExpectVisibleText(api, view);
  });

  it('a rename with no links in the text leaves the text and the caret alone', async () => {
    const rename = deferred();
    renameAttachment.mockReturnValue(rename.promise);
    const view = await mount('hello world');
    fakeLatchClock();

    startRename('new');
    placeCaret(view, 5);
    typeAtCaret(view, ',');
    rename.resolve({ ok: true });
    await flush();
    typeAtCaret(view, '!');
    advanceLatch();

    expect(docOf(view)).toBe('hello,! world');
    expect(caretOf(view)).toBe(7);
    saveAndExpectVisibleText(api, view);
  });
});

describe('Convert to Markdown', () => {
  const WIKI = '!!Heading\n\n__bold__';
  const MD = '# Heading\n\n**bold**';

  beforeEach(() => {
    api.getPage.mockResolvedValue({ content: WIKI, metadata: {}, version: 1, markupSyntax: 'wiki' });
  });

  it('(c) unchanged text: applies the Markdown at once (even inside the latch) and one undo restores the wiki text', async () => {
    const convert = deferred();
    api.convertWikiToMarkdown.mockReturnValue(convert.promise);
    const view = await mount(WIKI);
    fakeLatchClock();

    placeCaret(view, WIKI.length);
    typeAtCaret(view, '.');                                         // a last keystroke right before Convert
    fireEvent.click(screen.getByRole('button', { name: 'Convert to Markdown' }));
    expect(api.convertWikiToMarkdown).toHaveBeenCalledWith(`${WIKI}.`);
    expect(screen.getByRole('button', { name: 'Converting…' })).toBeDisabled();

    convert.resolve({ markdown: MD, warnings: ['dropped a plugin'] });
    await flush();
    expect(docOf(view)).toBe(MD);                                   // not deferred behind the latch
    expect(screen.getByText('dropped a plugin')).toBeInTheDocument();
    expect(screen.queryByText(/legacy wiki syntax/)).not.toBeInTheDocument();

    fireEvent.keyDown(view.contentDOM, { key: 'z', ctrlKey: true }); // Mod-z
    expect(docOf(view)).toBe(`${WIKI}.`);
    fireEvent.keyDown(view.contentDOM, { key: 'y', ctrlKey: true }); // redo
    expect(docOf(view)).toBe(MD);
    advanceLatch();
    expect(docOf(view)).toBe(MD);
    saveAndExpectVisibleText(api, view);
  });

  it('(d) typing during the request: the result is NOT applied, the text is intact and an error explains why', async () => {
    const convert = deferred();
    api.convertWikiToMarkdown.mockReturnValue(convert.promise);
    const view = await mount(WIKI);
    fakeLatchClock();

    fireEvent.click(screen.getByRole('button', { name: 'Convert to Markdown' }));
    expect(screen.getByRole('button', { name: 'Converting…' })).toBeDisabled();
    placeCaret(view, WIKI.length);
    typeAtCaret(view, ' more');
    convert.resolve({ markdown: MD, warnings: ['dropped a plugin'] });
    await flush();
    typeAtCaret(view, '!');
    advanceLatch();

    expect(docOf(view)).toBe(`${WIKI} more!`);
    expect(screen.getByTestId('editor-error')).toHaveTextContent('The page changed while converting — run Convert again.');
    expect(screen.queryByText('dropped a plugin')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Convert to Markdown' })).toBeEnabled(); // still offered
    saveAndExpectVisibleText(api, view);
  });
});

describe('draft restore', () => {
  it('(e) Restore lands in the editor at once and typing straight after it survives', async () => {
    useDraft.mockReturnValue({
      draft: { content: '---\ntitle: T\n---\nDraft body', savedAt: Date.now() },
      saveDraft: vi.fn(), clearDraft: vi.fn(),
    });
    api.validateFrontmatter.mockResolvedValue({ metadata: { title: 'T' }, violations: [] });
    const view = await mount('hello world');
    fakeLatchClock();

    placeCaret(view, 11);
    typeAtCaret(view, 'q');                                          // arms the latch
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }));
    expect(docOf(view)).toBe('Draft body');
    typeAtCaret(view, 'zz');
    await flush();
    advanceLatch();

    expect(docOf(view)).toBe('Draft bodyzz');
    const payload = saveAndExpectVisibleText(api, view);
    expect(payload.metadata).toEqual({ title: 'T' });
  });
});

describe('save conflict reload', () => {
  async function reachConflict() {
    api.getPage
      .mockResolvedValueOnce({ content: 'hello world', metadata: {}, version: 1, markupSyntax: 'markdown' })
      .mockResolvedValueOnce({ content: 'server text', metadata: { title: 'S' }, version: 5 });
    api.savePage.mockRejectedValueOnce(Object.assign(new Error('conflict'), { status: 409 }));
    const view = await mount('hello world');
    fakeLatchClock();
    placeCaret(view, 11);
    typeAtCaret(view, 'q');                                          // arms the latch
    fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    await until(() => screen.queryByText('Version Conflict'));
    return view;
  }

  it('(e) Discard loads the server text in the editor at once; typing straight after it survives', async () => {
    const view = await reachConflict();
    fireEvent.click(screen.getByRole('button', { name: /Discard my changes/ }));
    expect(docOf(view)).toBe('server text');
    typeAtCaret(view, 'zz');
    advanceLatch();

    expect(docOf(view)).toBe('server textzz');
    const payload = saveAndExpectVisibleText(api, view);
    expect(payload.expectedVersion).toBe(5);
  });

  it('(e) Copy-and-load copies my text, then loads the server text at once; typing straight after it survives', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const view = await reachConflict();
    fireEvent.click(screen.getByRole('button', { name: /Copy my text to clipboard/ }));
    await flush();
    expect(writeText).toHaveBeenCalledWith('hello worldq');
    expect(docOf(view)).toBe('server text');
    typeAtCaret(view, 'zz');
    advanceLatch();

    expect(docOf(view)).toBe('server textzz');
    const payload = saveAndExpectVisibleText(api, view);
    expect(payload.expectedVersion).toBe(5);
  });
});
