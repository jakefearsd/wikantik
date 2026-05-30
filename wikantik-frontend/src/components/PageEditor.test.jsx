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

// ── #20 Unsaved-changes guard ────────────────────────────────────────────────
describe('#20 unsaved-changes guard', () => {
  it('registers beforeunload listener when editor is dirty', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    renderEditor();
    await waitForEditor();

    fireEvent.change(screen.getByTestId('editor-textarea'), { target: { value: '# Changed!' } });

    await waitFor(() => {
      const calls = addSpy.mock.calls.filter(([ev]) => ev === 'beforeunload');
      expect(calls.length).toBeGreaterThan(0);
    });

    addSpy.mockRestore();
  });

  it('beforeunload handler sets returnValue when content is dirty', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.change(screen.getByTestId('editor-textarea'), { target: { value: '# Different content' } });

    const event = new Event('beforeunload', { cancelable: true });
    await waitFor(() => {
      window.dispatchEvent(event);
    });
    expect(event.returnValue).toBe('');
  });

  it('Cancel while dirty shows confirm modal (does not navigate immediately)', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.change(screen.getByTestId('editor-textarea'), { target: { value: '# Modified' } });

    fireEvent.click(screen.getByTestId('editor-cancel'));

    await waitFor(() => expect(screen.getByText(/discard unsaved changes/i)).toBeInTheDocument());
    expect(screen.queryByTestId('wiki-view')).toBeNull();
  });

  it('Cancel while clean navigates immediately without modal', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-cancel'));

    await screen.findByTestId('wiki-view');
    expect(screen.queryByText(/discard unsaved changes/i)).toBeNull();
  });

  it('Discard button in confirm modal navigates away', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.change(screen.getByTestId('editor-textarea'), { target: { value: '# Modified' } });
    fireEvent.click(screen.getByTestId('editor-cancel'));
    await waitFor(() => screen.getByText(/discard unsaved changes/i));

    fireEvent.click(screen.getByRole('button', { name: /^discard$/i }));

    await screen.findByTestId('wiki-view');
  });

  it('Keep Editing button in confirm modal closes modal without navigating', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.change(screen.getByTestId('editor-textarea'), { target: { value: '# Modified' } });
    fireEvent.click(screen.getByTestId('editor-cancel'));
    await waitFor(() => screen.getByText(/discard unsaved changes/i));

    fireEvent.click(screen.getByRole('button', { name: /keep editing/i }));

    expect(screen.queryByText(/discard unsaved changes/i)).toBeNull();
    expect(screen.queryByTestId('wiki-view')).toBeNull();
  });
});

// ── #21 Draft banner: relative time + dismiss ────────────────────────────────
describe('#21 draft banner relative time and dismiss', () => {
  it('shows relative time in banner when draft exists', async () => {
    const twoHoursAgo = Date.now() - 2 * 60 * 60 * 1000;
    useDraft.mockReturnValue({
      draft: { content: '# Draft content (different)', savedAt: twoHoursAgo, title: 'TestPage' },
      saveDraft: vi.fn(),
      clearDraft: vi.fn(),
    });

    renderEditor();
    await waitForEditor();

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    expect(screen.getByRole('status').textContent).toContain('2h ago');
  });

  it('dismiss button hides banner but does NOT call clearDraft', async () => {
    const mockClearDraft = vi.fn();
    const twoHoursAgo = Date.now() - 2 * 60 * 60 * 1000;
    useDraft.mockReturnValue({
      draft: { content: '# Draft content (different)', savedAt: twoHoursAgo, title: 'TestPage' },
      saveDraft: vi.fn(),
      clearDraft: mockClearDraft,
    });

    renderEditor();
    await waitForEditor();
    await waitFor(() => screen.getByRole('status'));

    fireEvent.click(screen.getByLabelText('Dismiss draft notice'));

    await waitFor(() => expect(screen.queryByRole('status')).toBeNull());
    expect(mockClearDraft).not.toHaveBeenCalled();
  });

  it('Discard button in banner calls clearDraft', async () => {
    const mockClearDraft = vi.fn();
    const twoHoursAgo = Date.now() - 2 * 60 * 60 * 1000;
    useDraft.mockReturnValue({
      draft: { content: '# Draft content (different)', savedAt: twoHoursAgo, title: 'TestPage' },
      saveDraft: vi.fn(),
      clearDraft: mockClearDraft,
    });

    renderEditor();
    await waitForEditor();
    await waitFor(() => screen.getByRole('status'));

    fireEvent.click(screen.getByRole('button', { name: /^Discard$/i }));

    expect(mockClearDraft).toHaveBeenCalled();
  });
});

// ── #18 Formatting toolbar ────────────────────────────────────────────────────
describe('#18 formatting toolbar', () => {
  it('renders all six toolbar buttons', async () => {
    renderEditor();
    await waitForEditor();

    expect(screen.getByTitle(/bold/i)).toBeInTheDocument();
    expect(screen.getByTitle(/italic/i)).toBeInTheDocument();
    expect(screen.getByTitle(/heading/i)).toBeInTheDocument();
    expect(screen.getByTitle(/list/i)).toBeInTheDocument();
    expect(screen.getByTitle(/code/i)).toBeInTheDocument();
    expect(screen.getByTitle(/link/i)).toBeInTheDocument();
  });

  it('formatting toolbar is rendered above textarea', async () => {
    const { container } = renderEditor();
    await waitForEditor();

    const toolbar = container.querySelector('.editor-format-toolbar');
    expect(toolbar).not.toBeNull();
  });
});
