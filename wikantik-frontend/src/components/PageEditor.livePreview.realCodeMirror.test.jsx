/**
 * Live preview mode on the REAL CodeMirror editor: the toolbar button and Mod-e toggle it (remembered per
 * browser), the document, undo history and saved text are untouched by the mode, and the one live edit
 * (a task checkbox) goes through the view.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent, screen } from '@testing-library/react';

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
import { undo } from '@codemirror/commands';
import { mountRealEditor, saveAndExpectVisibleText, placeCaret, typeAtCaret, docOf, until, flush } from '../test/realEditorHarness';

const INITIAL = '# Title\n\n- [ ] task **b**\n\nend';

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  window.matchMedia = vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: INITIAL, metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockRejectedValue(new Error('probe'));
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

const isLive = () => document.querySelector('.cm-editor.cm-live-preview') !== null;

describe('live preview mode toggle', () => {
  it('the toolbar button toggles live mode and remembers it', async () => {
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    expect(isLive()).toBe(false);
    fireEvent.mouseDown(screen.getByRole('button', { name: /live preview/i }));
    await until(isLive);
    expect(localStorage.getItem('wikantik.editor.mode')).toBe('live');
    expect(docOf(view)).toBe(INITIAL);
  });

  it('Mod-e toggles live mode from anywhere on the page', async () => {
    await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(isLive);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(() => !isLive());
  });

  it('undo history survives a mode toggle', async () => {
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    placeCaret(view, INITIAL.length);
    typeAtCaret(view, 'Z');
    expect(docOf(view)).toBe(`${INITIAL}Z`);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(isLive);
    expect(docOf(view)).toBe(`${INITIAL}Z`);
    fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
    await until(() => !isLive());
    act(() => { undo(view); });
    expect(docOf(view)).toBe(INITIAL);
  });

  it('saving in live mode sends exactly the source text, including a checkbox toggle', async () => {
    localStorage.setItem('wikantik.editor.mode', 'live');
    const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
    placeCaret(view, INITIAL.length);
    await until(() => document.querySelector('input.cm-lp-task') !== null);
    saveAndExpectVisibleText(api, view);
    expect(api.savePage.mock.calls[0][1].content).toContain('- [ ] task **b**');
    fireEvent.mouseDown(document.querySelector('input.cm-lp-task'));
    expect(docOf(view)).toBe('# Title\n\n- [x] task **b**\n\nend');
    await flush(); // let the rejected first probe settle so the next Ctrl+S is not ignored
    const payload = saveAndExpectVisibleText(api, view);
    expect(payload.content).toContain('- [x] task **b**');
  });
});
