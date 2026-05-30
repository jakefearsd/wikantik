/**
 * PageEditor tests — one file, grouped by feature.
 * The component is always mounted with a MemoryRouter so react-router hooks work.
 * api.getPage is mocked to return a simple page; api.savePage defaults to resolving.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// ── Module mocks (hoisted) ──────────────────────────────────────────────────
vi.mock('../api/client', () => ({
  api: {
    getPage: vi.fn(),
    savePage: vi.fn(),
    listAttachments: vi.fn(),
  },
}));
vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useDraft', () => ({ useDraft: vi.fn() }));
vi.mock('../hooks/useAttachments', () => ({ useAttachments: vi.fn() }));
vi.mock('../hooks/useEditorDrop', () => ({ useEditorDrop: vi.fn() }));
vi.mock('../components/ui/ToastProvider', () => ({
  ToastContext: { _currentValue: null },
}));
vi.mock('../hooks/useToast', () => ({
  useToast: vi.fn(),
}));

import PageEditor from './PageEditor';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useEditorDrop } from '../hooks/useEditorDrop';
import { useToast } from '../hooks/useToast';

// ── Shared helpers ──────────────────────────────────────────────────────────

const PAGE_CONTENT = '# Test Page\n\nSome content here.';

function renderEditor(pageName = 'TestPage') {
  return render(
    <MemoryRouter initialEntries={[`/edit/${pageName}`]}>
      <Routes>
        <Route path="/edit/:name" element={<PageEditor />} />
        <Route path="/wiki/:name" element={<div data-testid="wiki-view">WIKI VIEW</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function waitForEditor() {
  await waitFor(() => expect(screen.getByTestId('editor-textarea')).toBeInTheDocument());
}

let mockToastSuccess;
let mockToastError;
let mockToastInfo;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();

  useAuth.mockReturnValue({
    user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] },
  });

  useDraft.mockReturnValue({
    draft: null,
    saveDraft: vi.fn(),
    clearDraft: vi.fn(),
  });

  useAttachments.mockReturnValue({
    list: [],
    uploadAttachment: vi.fn(),
    renameAttachment: vi.fn(),
    deleteAttachment: vi.fn(),
  });

  useEditorDrop.mockImplementation(() => {});

  mockToastSuccess = vi.fn();
  mockToastError = vi.fn();
  mockToastInfo = vi.fn();
  useToast.mockReturnValue({
    success: mockToastSuccess,
    error: mockToastError,
    info: mockToastInfo,
  });

  api.getPage.mockResolvedValue({
    content: PAGE_CONTENT,
    metadata: {},
    version: 1,
    markupSyntax: 'markdown',
  });
  api.savePage.mockResolvedValue({ success: true });
  api.listAttachments.mockResolvedValue({ attachments: [] });
});

// ── #4 Cmd/Ctrl+S keyboard save ─────────────────────────────────────────────
describe('#4 Cmd/Ctrl+S save', () => {
  it('Cmd+S triggers savePage', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.keyDown(window, { key: 's', metaKey: true });
    await waitFor(() => expect(api.savePage).toHaveBeenCalledTimes(1));
  });

  it('Ctrl+S triggers savePage', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    await waitFor(() => expect(api.savePage).toHaveBeenCalledTimes(1));
  });

  it('does not call savePage twice when already saving', async () => {
    let resolveSave;
    api.savePage.mockReturnValue(new Promise(resolve => { resolveSave = resolve; }));

    renderEditor();
    await waitForEditor();

    fireEvent.keyDown(window, { key: 's', metaKey: true });
    await act(async () => {});

    fireEvent.keyDown(window, { key: 's', metaKey: true });
    await act(async () => {});

    expect(api.savePage).toHaveBeenCalledTimes(1);

    resolveSave({ success: true });
  });

  it('shows success toast on save', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.keyDown(window, { key: 's', metaKey: true });
    await waitFor(() => expect(mockToastSuccess).toHaveBeenCalledWith('Saved'));
  });
});
