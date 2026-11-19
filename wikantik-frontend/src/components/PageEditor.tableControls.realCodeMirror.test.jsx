/**
 * The table controls as PageEditor wires them, on the REAL CodeMirror editor: they and the table commands exist while
 * the caret is in a grid table in Rich mode, a control writes one change that one undo takes back, and Source mode
 * has neither. What each command writes is covered in useTableControls.realCodeMirror.test.jsx.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { act, cleanup, fireEvent, screen } from '@testing-library/react';

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
import '../test/asyncSelectionChange'; // a command's selection + focus: happy-dom would fire selectionchange into the update
import { getCommands } from '../commands/registry';
import { parseFully } from '../test/fullParse';
import { mountRealEditor, placeCaret, docOf, until } from '../test/realEditorHarness';

const INITIAL = 'top\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\nend';
const tableCommands = () => getCommands().filter((c) => c.section === 'Table').map((c) => c.id);
const toolbar = () => screen.queryByRole('toolbar', { name: 'Table' });
const isLive = () => document.querySelector('.cm-editor.cm-live-preview') !== null;

async function mount(mode) {
  localStorage.setItem('wikantik.editor.mode', mode);
  const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
  if (mode === 'live') await until(isLive);
  act(() => { parseFully(view); });
  return view;
}

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
  document.getSelection()?.removeAllRanges();
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

describe('PageEditor table controls', () => {
  it('Rich: the controls and the table commands come with the caret into a table and go with it', async () => {
    const view = await mount('live');
    expect(toolbar()).toBe(null);
    expect(tableCommands()).toEqual([]);
    placeCaret(view, INITIAL.indexOf('2'));
    await until(() => toolbar() !== null);
    expect(toolbar().parentElement).toBe(view.scrollDOM);
    expect(tableCommands()).toContain('table-delete-column');
    placeCaret(view, 1);
    await until(() => toolbar() === null);
    expect(tableCommands()).toEqual([]);
  });

  it('Rich: "+ col" writes the new column, and one undo restores the text exactly', async () => {
    const view = await mount('live');
    placeCaret(view, INITIAL.indexOf('2'));
    await until(() => toolbar() !== null);
    fireEvent.click(screen.getByRole('button', { name: 'Insert column right' }));
    expect(docOf(view)).toBe('top\n\n| a | b |  |\n| --- | --- | --- |\n| 1 | 2 |  |\n\nend');
    act(() => { undo(view); });
    expect(docOf(view)).toBe(INITIAL);
  });

  it('Source: no controls and no table commands in a table', async () => {
    const view = await mount('source');
    placeCaret(view, INITIAL.indexOf('2'));
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
    expect(toolbar()).toBe(null);
    expect(tableCommands()).toEqual([]);
  });
});
