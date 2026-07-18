/**
 * Tests for derived-page UI affordances:
 *   1. AttachmentPanel — "Ingest as derived page" action (IngestForm)
 *   2. PageEditor — machine-owned-body banner shown when metadata.derived_from is set
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- see the afterEach rationale below
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// ── CodeMirror stub (mirrors PageEditor.test.jsx) ───────────────────────────
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

// ── Module mocks ─────────────────────────────────────────────────────────────
vi.mock('../api/client', () => ({
  api: {
    getPage: vi.fn(),
    savePage: vi.fn(),
    listAttachments: vi.fn(),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    ingestDocument: vi.fn(),
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
    upsertEntity: vi.fn(() => Promise.resolve({ ok: true, nodeId: 'x' })),
    confirmEntity: vi.fn(() => Promise.resolve(null)),
    deleteEntity: vi.fn(() => Promise.resolve(null)),
    upsertEdge: vi.fn(() => Promise.resolve({ ok: true, edgeId: 'x' })),
    confirmEdge: vi.fn(() => Promise.resolve(null)),
    deleteEdge: vi.fn(() => Promise.resolve(null)),
    rejectEdge: vi.fn(() => Promise.resolve(null)),
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

import AttachmentPanel from './AttachmentPanel';
import PageEditor from './PageEditor';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useEditorDrop } from '../hooks/useEditorDrop';
import { useToast } from '../hooks/useToast';

// ── AttachmentPanel helpers ──────────────────────────────────────────────────
function renderPanel(overrides = {}) {
  const props = {
    open: true,
    onClose: vi.fn(),
    pageName: 'TestPage',
    attachments: [],
    onUpload: vi.fn(),
    onRename: vi.fn(),
    onDelete: vi.fn(),
    editorContent: '',
    ...overrides,
  };
  return { ...render(<AttachmentPanel {...props} />), props };
}

// ── PageEditor helpers ───────────────────────────────────────────────────────
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
  expect(await screen.findByTestId('cm-stub-textarea')).toBeInTheDocument();
}

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
  useToast.mockReturnValue({
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
  });

  api.getPage.mockResolvedValue({
    content: '# Test Page\n\nSome content here.',
    metadata: {},
    version: 1,
    markupSyntax: 'markdown',
  });
  api.savePage.mockResolvedValue({ success: true });
  api.listAttachments.mockResolvedValue({ attachments: [] });
  api.validateFrontmatter.mockResolvedValue({ metadata: {}, violations: [] });
});

afterEach(async () => {
  // eslint-disable-next-line testing-library/no-manual-cleanup -- ordering vs the promise flush below is deliberate (see comment above)
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

// ── 1. AttachmentPanel: ingest action ───────────────────────────────────────
describe('AttachmentPanel — ingest as derived page', () => {
  it('renders the ingest section with a file input', () => {
    renderPanel();
    // The ingest section heading should be visible
    expect(screen.getByText(/ingest as derived page/i)).toBeInTheDocument();
    // At minimum the panel has a close button; ingest button should also be present
    expect(screen.getByRole('button', { name: /ingest/i })).toBeInTheDocument();
  });

  it('ingest button is disabled when no file is selected', () => {
    renderPanel();
    const ingestBtn = screen.getByRole('button', { name: /ingest/i });
    expect(ingestBtn).toBeDisabled();
  });

  it('selecting a file enables the ingest button', () => {
    renderPanel();
    // Find the ingest file input (second file input in the panel, after the upload one)
    const fileInputs = document.querySelectorAll('input[type="file"]');
    // The ingest input is the second one (after the upload input)
    const ingestFileInput = fileInputs[fileInputs.length - 1];
    const file = new File(['hello world'], 'report.pdf', { type: 'application/pdf' });
    fireEvent.change(ingestFileInput, { target: { files: [file] } });
    expect(screen.getByRole('button', { name: /ingest/i })).not.toBeDisabled();
  });

  it('submitting calls api.ingestDocument and shows the result page name', async () => {
    api.ingestDocument.mockResolvedValue({ page: 'report', status: 'created' });
    renderPanel();

    const fileInputs = document.querySelectorAll('input[type="file"]');
    const ingestFileInput = fileInputs[fileInputs.length - 1];
    const file = new File(['hello world'], 'report.pdf', { type: 'application/pdf' });
    fireEvent.change(ingestFileInput, { target: { files: [file] } });

    fireEvent.click(screen.getByRole('button', { name: /ingest/i }));

    await waitFor(() => expect(api.ingestDocument).toHaveBeenCalledTimes(1));
    // Should have been called with the file
    const [calledFile] = api.ingestDocument.mock.calls[0];
    expect(calledFile.name).toBe('report.pdf');

    // Result should be surfaced: page name visible
    expect(await screen.findByText(/report/i)).toBeInTheDocument();
    // Status badge/text (created) should show
    expect(await screen.findByText(/created/i)).toBeInTheDocument();
  });

  it('surfaces an error message when ingestDocument rejects', async () => {
    api.ingestDocument.mockRejectedValue(new Error('Server error'));
    renderPanel();

    const fileInputs = document.querySelectorAll('input[type="file"]');
    const ingestFileInput = fileInputs[fileInputs.length - 1];
    const file = new File(['hello'], 'doc.txt', { type: 'text/plain' });
    fireEvent.change(ingestFileInput, { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: /ingest/i }));

    expect(await screen.findByText(/server error/i)).toBeInTheDocument();
  });

  it('result contains a link to /wiki/<page> when status is created', async () => {
    api.ingestDocument.mockResolvedValue({ page: 'my-report', status: 'created' });
    renderPanel();

    const fileInputs = document.querySelectorAll('input[type="file"]');
    const ingestFileInput = fileInputs[fileInputs.length - 1];
    const file = new File(['x'], 'my-report.pdf', { type: 'application/pdf' });
    fireEvent.change(ingestFileInput, { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: /ingest/i }));

    await waitFor(() => {
      const link = screen.getByRole('link', { name: /my-report/i });
      expect(link).toHaveAttribute('href', '/wiki/my-report');
    });
  });
});

// ── 2. PageEditor: machine-owned-body banner ─────────────────────────────────
describe('PageEditor — machine-owned-body banner', () => {
  it('does NOT show the banner for a hand-authored page (no derived_from)', async () => {
    api.getPage.mockResolvedValue({
      content: '# Normal page',
      metadata: { title: 'Normal page', type: 'article' },
      version: 1,
      markupSyntax: 'markdown',
    });
    renderEditor();
    await waitForEditor();

    expect(screen.queryByText(/machine-generated/i)).not.toBeInTheDocument();
  });

  it('shows the machine-owned-body banner when metadata.derived_from is set', async () => {
    api.getPage.mockResolvedValue({
      content: '# Derived page\n\nExtracted body.',
      metadata: {
        title: 'Derived page',
        type: 'reference',
        derived_from: 'report.pdf',
      },
      version: 1,
      markupSyntax: 'markdown',
    });
    renderEditor();
    await waitForEditor();

    expect(screen.getByText(/machine-generated/i)).toBeInTheDocument();
    expect(screen.getByText(/reflow will overwrite/i)).toBeInTheDocument();
  });

  it('banner mentions frontmatter, tags, and Knowledge Graph as safe curation targets', async () => {
    api.getPage.mockResolvedValue({
      content: '# Derived',
      metadata: { derived_from: 'source.docx' },
      version: 1,
      markupSyntax: 'markdown',
    });
    renderEditor();
    await waitForEditor();

    const banner = screen.getByRole('status', { hidden: true });
    expect(banner).toBeInTheDocument();
    // Banner should advise curating in frontmatter, tags, and KG
    expect(banner.textContent).toMatch(/frontmatter/i);
    expect(banner.textContent).toMatch(/knowledge graph/i);
  });
});
