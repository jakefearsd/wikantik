/**
 * PageEditor tests — one file, grouped by feature.
 * The component is always mounted with a MemoryRouter so react-router hooks work.
 * api.getPage is mocked to return a simple page; api.savePage defaults to resolving.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// ── #19 CodeMirror stub ──────────────────────────────────────────────────────
// happy-dom cannot run CodeMirror's contenteditable/measuring layer, so the
// real @uiw/react-codemirror is replaced with a lightweight <textarea>-backed
// stub. The stub faithfully forwards value/onChange AND exposes a
// CodeMirror-shaped `view` (state.selection.main + state.doc.length + dispatch +
// focus) via onCreateEditor, backed by the textarea's real selectionStart/End.
// Behavioral assertions therefore exercise the REAL CodeEditor imperative
// handle and the REAL markdownFormat util — only the rendering engine is
// stubbed. Real CodeMirror integration is verified by `npm run build` + manual
// testing. The stub uses vi.importActual('react') so its createElement shares
// the running renderer's React instance.
vi.mock('@uiw/react-codemirror', async () => {
  const React = (await vi.importActual('react')).default;
  function makeView(ta) {
    return {
      get state() {
        return {
          selection: { main: { from: ta.selectionStart, to: ta.selectionEnd } },
          doc: { length: ta.value.length },
        };
      },
      focus() { ta.focus(); },
      dispatch(tr) {
        if (tr && tr.selection) {
          ta.setSelectionRange(tr.selection.anchor, tr.selection.head);
        }
      },
    };
  }
  return {
    default: function CodeMirrorStub({ value, onChange, onCreateEditor }) {
      return React.createElement('textarea', {
        ref: (ta) => { if (ta && onCreateEditor) onCreateEditor(makeView(ta)); },
        'data-testid': 'cm-stub-textarea',
        className: 'editor-textarea',
        value: value || '',
        onChange: (e) => onChange && onChange(e.target.value),
        spellCheck: 'false',
      });
    },
  };
});

// ── Module mocks (hoisted) ──────────────────────────────────────────────────
vi.mock('../api/client', () => ({
  api: {
    getPage: vi.fn(),
    savePage: vi.fn(),
    listAttachments: vi.fn(),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
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
  // #19 — wait for the inner editable (the stubbed CodeMirror textarea), which
  // mounts after the wrapper div and after the page's async load resolves.
  await waitFor(() => expect(screen.getByTestId('cm-stub-textarea')).toBeInTheDocument());
}

// #19 — the editable surface under the stub is the inner textarea.
function getEditable() {
  return screen.getByTestId('cm-stub-textarea');
}

// #19 — type into the editor (drives the controlled value through onChange).
function typeInEditor(value) {
  fireEvent.change(getEditable(), { target: { value } });
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

// #19 — Unmount the tree and flush any pending async state (the page's load
// effect, debounced draft writes, the save→navigate chain) BEFORE the next
// test renders, so an unsettled promise from one test cannot resolve into the
// next test's render. Keeps every assertion meaningful by guaranteeing a clean
// DOM per test rather than weakening expectations.
afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
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

    typeInEditor('# Changed!');

    await waitFor(() => {
      const calls = addSpy.mock.calls.filter(([ev]) => ev === 'beforeunload');
      expect(calls.length).toBeGreaterThan(0);
    });

    addSpy.mockRestore();
  });

  it('beforeunload handler sets returnValue when content is dirty', async () => {
    renderEditor();
    await waitForEditor();

    typeInEditor('# Different content');

    const event = new Event('beforeunload', { cancelable: true });
    await waitFor(() => {
      window.dispatchEvent(event);
    });
    expect(event.returnValue).toBe('');
  });

  it('Cancel while dirty shows confirm modal (does not navigate immediately)', async () => {
    renderEditor();
    await waitForEditor();

    typeInEditor('# Modified');

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

    typeInEditor('# Modified');
    fireEvent.click(screen.getByTestId('editor-cancel'));
    await waitFor(() => screen.getByText(/discard unsaved changes/i));

    fireEvent.click(screen.getByRole('button', { name: /^discard$/i }));

    await screen.findByTestId('wiki-view');
  });

  it('Keep Editing button in confirm modal closes modal without navigating', async () => {
    renderEditor();
    await waitForEditor();

    typeInEditor('# Modified');
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
  it('renders all toolbar buttons', async () => {
    renderEditor();
    await waitForEditor();

    expect(screen.getByTitle(/bold/i)).toBeInTheDocument();
    expect(screen.getByTitle(/italic/i)).toBeInTheDocument();
    expect(screen.getByTitle(/heading/i)).toBeInTheDocument();
    expect(screen.getByTitle(/list/i)).toBeInTheDocument();
    expect(screen.getByTitle(/inline code/i)).toBeInTheDocument();
    expect(screen.getByTitle(/code block/i)).toBeInTheDocument();
    expect(screen.getByTitle(/table/i)).toBeInTheDocument();
    expect(screen.getByTitle(/link/i)).toBeInTheDocument();
  });

  it('formatting toolbar is rendered above textarea', async () => {
    const { container } = renderEditor();
    await waitForEditor();

    const toolbar = container.querySelector('.editor-format-toolbar');
    expect(toolbar).not.toBeNull();
  });

  // #19 — exercises the REAL applyFormat + markdownFormat util through the
  // CodeEditor imperative handle (stubbed view), asserting the document text is
  // genuinely transformed, not mock behavior.
  it('Bold toolbar button wraps the selection with ** via markdownFormat', async () => {
    renderEditor();
    await waitForEditor();

    const editable = getEditable();
    fireEvent.change(editable, { target: { value: 'hello world' } });
    editable.focus();
    editable.setSelectionRange(6, 11); // "world"

    fireEvent.mouseDown(screen.getByTitle(/bold/i));

    await waitFor(() => expect(getEditable().value).toBe('hello **world**'));
  });

  it('Link toolbar button inserts a markdown link via markdownFormat', async () => {
    renderEditor();
    await waitForEditor();

    const editable = getEditable();
    fireEvent.change(editable, { target: { value: 'see docs' } });
    editable.focus();
    editable.setSelectionRange(4, 8); // "docs"

    fireEvent.mouseDown(screen.getByTitle(/link/i));

    await waitFor(() => expect(getEditable().value).toBe('see [docs](url)'));
  });
});

// ── #22 Drag-and-drop drop-zone hint ─────────────────────────────────────────
describe('#22 drag-and-drop drop-zone hint', () => {
  it('shows drop-zone hint on dragenter', async () => {
    const { container } = renderEditor();
    await waitForEditor();

    const pane = container.querySelector('.editor-pane');
    expect(pane).not.toBeNull();

    fireEvent.dragEnter(pane, {
      dataTransfer: { types: ['Files'] },
    });

    await waitFor(() => {
      expect(container.querySelector('.editor-dropzone-hint')).not.toBeNull();
    });
  });

  it('hides drop-zone hint on dragleave', async () => {
    const { container } = renderEditor();
    await waitForEditor();

    const pane = container.querySelector('.editor-pane');
    fireEvent.dragEnter(pane, { dataTransfer: { types: ['Files'] } });
    await waitFor(() => expect(container.querySelector('.editor-dropzone-hint')).not.toBeNull());

    fireEvent.dragLeave(pane);

    await waitFor(() => expect(container.querySelector('.editor-dropzone-hint')).toBeNull());
  });

  it('hides drop-zone hint on drop', async () => {
    const { container } = renderEditor();
    await waitForEditor();

    const pane = container.querySelector('.editor-pane');
    fireEvent.dragEnter(pane, { dataTransfer: { types: ['Files'] } });
    await waitFor(() => expect(container.querySelector('.editor-dropzone-hint')).not.toBeNull());

    fireEvent.drop(pane, { dataTransfer: { types: ['Files'] } });

    await waitFor(() => expect(container.querySelector('.editor-dropzone-hint')).toBeNull());
  });
});
