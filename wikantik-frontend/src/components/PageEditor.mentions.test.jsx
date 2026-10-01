/** PageEditor unlinked-mentions flow: Link / Ignore / stale-text rescan. CodeEditor is stubbed to record replaceRange. */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

const replaceRange = vi.hoisted(() => vi.fn(() => true));
vi.mock('./CodeEditor', async () => {
  const React = (await vi.importActual('react')).default;
  return {
    default: React.forwardRef(function CodeEditorStub({ value, onChange }, ref) {
      React.useImperativeHandle(ref, () => ({
        getSelection: () => ({ selStart: 0, selEnd: 0 }),
        setSelection() {}, focus() {}, getViewport: () => null, scrollToLine() {},
        getScrollerRect: () => null, jumpToLineAligned() {}, replaceRange,
      }));
      return React.createElement('div', null,
        React.createElement('pre', { 'data-testid': 'body-value' }, value),
        React.createElement('button', { type: 'button', 'data-testid': 'edit-body', onClick: () => onChange('totally different') }, 'edit'));
    }),
  };
});

vi.mock('../api/client', () => ({
  api: {
    getPage: vi.fn(),
    getBacklinks: vi.fn(() => Promise.resolve({ backlinks: [] })),
    savePage: vi.fn(),
    uploadAttachment: vi.fn(),
    listAttachments: vi.fn(),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    listTags: vi.fn(() => Promise.resolve({ tags: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
    scanMentions: vi.fn(),
  },
}));
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

const BODY = 'An index fund is cheap.';
const MENTION = { target: 'IndexFundsHub', title: 'Index Funds', phrase: 'index fund', from: 3, to: 13, line: 1, context: BODY, more: 0 };
let toast;

function renderEditor() {
  return render(
    <MemoryRouter initialEntries={['/edit/Existing']}>
      <NavigationGuardProvider>
        <Routes><Route path="/edit/:name" element={<PageEditor />} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
}

async function scanned() {
  renderEditor();
  await screen.findByTestId('body-value');
  await act(async () => { await vi.advanceTimersByTimeAsync(1600); });
  return screen.findByTestId('mention-row');
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ shouldAdvanceTime: true });
  localStorage.clear();
  window.matchMedia = vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} })); // wide: rail open
  toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  useToast.mockReturnValue(toast);
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation(() => ({ list: [], uploadAttachment: vi.fn(), renameAttachment: vi.fn(), deleteAttachment: vi.fn() }));
  api.getPage.mockResolvedValue({ content: BODY, metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.listAttachments.mockResolvedValue({ attachments: [] });
  api.scanMentions.mockResolvedValue({ mentions: [MENTION] });
});

afterEach(async () => {
  cleanup();
  vi.useRealTimers();
  await act(async () => { await Promise.resolve(); });
});

describe('PageEditor unlinked mentions', () => {
  it('shows the scanned mention in the rail', async () => {
    const row = await scanned();
    expect(row).toHaveTextContent('index fund');
    expect(api.scanMentions.mock.calls[0][0]).toMatchObject({ page: 'Existing', text: BODY });
  });

  it('Link replaces exactly the phrase with link markup', async () => {
    await scanned();
    fireEvent.click(screen.getByTestId('mention-link'));
    expect(replaceRange).toHaveBeenCalledWith(3, 13, '[index fund](IndexFundsHub)');
    expect(screen.queryByTestId('mention-row')).toBeNull();
  });

  it('Link on changed text toasts and rescans', async () => {
    await scanned();
    fireEvent.click(screen.getByTestId('edit-body'));
    fireEvent.click(screen.getByTestId('mention-link'));
    expect(replaceRange).not.toHaveBeenCalled();
    expect(toast.info).toHaveBeenCalledWith('Text changed — rescanned');
    await waitFor(() => expect(api.scanMentions.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('Ignore hides the row', async () => {
    await scanned();
    fireEvent.click(screen.getByTestId('mention-ignore'));
    expect(screen.queryByTestId('mention-row')).toBeNull();
  });
});
