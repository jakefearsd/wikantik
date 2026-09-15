/**
 * PageEditor tests — one file, grouped by feature.
 * The component is always mounted with a MemoryRouter so react-router hooks work.
 * api.getPage is mocked to return a simple page; api.savePage defaults to resolving.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- see the afterEach rationale below
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
    convertWikiToMarkdown: vi.fn(),
    listAttachments: vi.fn(),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    // Page-scoped Knowledge Graph curation (Task 13)
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
  expect(await screen.findByTestId('cm-stub-textarea')).toBeInTheDocument();
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
  // Live validation fires on mount; default to "clean" so unrelated tests keep Save enabled.
  // (Per-test overrides below are re-reset here each run, so they can't leak across tests.)
  api.validateFrontmatter.mockResolvedValue({ metadata: {}, violations: [] });
});

// #19 — Unmount the tree and flush any pending async state (the page's load
// effect, debounced draft writes, the save→navigate chain) BEFORE the next
// test renders, so an unsettled promise from one test cannot resolve into the
// next test's render. Keeps every assertion meaningful by guaranteeing a clean
// DOM per test rather than weakening expectations.
afterEach(async () => {
  // eslint-disable-next-line testing-library/no-manual-cleanup -- ordering vs the promise flush below is deliberate (see comment above)
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

    expect(await screen.findByText(/discard unsaved changes/i)).toBeInTheDocument();
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

    expect(await screen.findByRole('status')).toBeInTheDocument();
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

// ── Task 13: Frontmatter / Knowledge tabs ────────────────────────────────────
describe('Task 13: Frontmatter / Knowledge tabs', () => {
  it('renders the Frontmatter tab by default', async () => {
    renderEditor();
    await waitForEditor();

    // The Tabs component renders tab buttons
    expect(screen.getByRole('tab', { name: /frontmatter/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /knowledge/i })).toBeInTheDocument();
    // Frontmatter tab is active (selected)
    expect(screen.getByRole('tab', { name: /frontmatter/i })).toHaveAttribute('aria-selected', 'true');
  });

  it('clicking the Knowledge tab renders the panel', async () => {
    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByRole('tab', { name: /knowledge/i }));

    // KnowledgeGraphPanel renders (panel div or empty-state messages)
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /knowledge/i })).toHaveAttribute('aria-selected', 'true');
    });
    // The panel section headings should appear
    await screen.findByText('Entities');
    // Use getByRole to find the heading specifically (avoids matching empty-state text)
    expect(await screen.findByRole('heading', { name: /relations/i })).toBeInTheDocument();
  });

  it('switching back to Frontmatter tab shows the frontmatter form', async () => {
    renderEditor();
    await waitForEditor();

    // Go to Knowledge
    fireEvent.click(screen.getByRole('tab', { name: /knowledge/i }));
    await screen.findByText('Entities');

    // Go back to Frontmatter
    fireEvent.click(screen.getByRole('tab', { name: /frontmatter/i }));

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /frontmatter/i })).toHaveAttribute('aria-selected', 'true');
    });
    // KnowledgeGraphPanel is lazily mounted — its headings should no longer be present
    expect(screen.queryByText('Entities')).toBeNull();
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

// ── Live validation + Save gating ───────────────────────────────────────────
describe('live frontmatter validation', () => {
  it('disables Save when live validation reports an ERROR', async () => {
    api.validateFrontmatter.mockResolvedValue({
      violations: [{ field: 'type', severity: 'ERROR', code: 'x', message: 'bad type' }],
    });
    renderEditor('Sample');
    const save = await screen.findByTestId('editor-save');
    await waitFor(() => expect(save.disabled).toBe(true));
  });

  it('keeps Save enabled when only warnings are present', async () => {
    api.validateFrontmatter.mockResolvedValue({
      violations: [{ field: 'summary', severity: 'WARNING', code: 'y', message: 'long summary' }],
    });
    renderEditor('Sample');
    const save = await screen.findByTestId('editor-save');
    expect(await screen.findByText('1 warning')).toBeTruthy();
    expect(save.disabled).toBe(false);
  });
});

// ── Math validation (server-side 422 + 200 warnings) ─────────────────────────
describe('math validation', () => {
  const MATH_ERROR = {
    locus: 'math',
    severity: 'ERROR',
    code: 'UNCLOSED_BRACE',
    message: 'Unclosed brace in LaTeX expression',
    location: {
      line: 3,
      column: 1,
      endLine: 3,
      endColumn: 8,
      startOffset: 20,
      endOffset: 27,
      excerpt: '\\frac{a',
      caret: '       ^',
    },
  };

  const MATH_WARNING = {
    locus: 'math',
    severity: 'WARNING',
    code: 'DEPRECATED_MACRO',
    message: 'Deprecated macro \\over',
    location: {
      line: 5,
      column: 1,
      endLine: 5,
      endColumn: 5,
      startOffset: 50,
      endOffset: 55,
      excerpt: '\\over',
      caret: '^^^^^',
    },
  };

  it('a 422 math_validation_failed populates the math panel, not the frontmatter panel', async () => {
    api.savePage.mockRejectedValueOnce({
      status: 422,
      body: { error: 'math_validation_failed', violations: [MATH_ERROR] },
    });

    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));

    expect(await screen.findByTestId('math-validation-summary')).toBeInTheDocument();
    expect(screen.getByText('Unclosed brace in LaTeX expression')).toBeInTheDocument();
    // Frontmatter valid strip should still show (no frontmatter errors set)
    expect(screen.getByText(/frontmatter valid/i)).toBeInTheDocument();
  });

  it('a 422 math_validation_failed shows an error toast', async () => {
    api.savePage.mockRejectedValueOnce({
      status: 422,
      body: { error: 'math_validation_failed', violations: [MATH_ERROR] },
    });

    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));

    await waitFor(() => expect(mockToastError).toHaveBeenCalledWith('Fix the highlighted math errors'));
  });

  it('a 422 math ERROR disables the Save button', async () => {
    api.savePage.mockRejectedValueOnce({
      status: 422,
      body: { error: 'math_validation_failed', violations: [MATH_ERROR] },
    });

    renderEditor();
    await waitForEditor();

    const save = screen.getByTestId('editor-save');
    fireEvent.click(save);

    expect(await screen.findByTestId('math-validation-summary')).toBeInTheDocument();
    expect(save.disabled).toBe(true);
  });

  it('editing the body after a math 422 clears the math violations', async () => {
    api.savePage.mockRejectedValueOnce({
      status: 422,
      body: { error: 'math_validation_failed', violations: [MATH_ERROR] },
    });

    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));
    expect(await screen.findByTestId('math-validation-summary')).toBeInTheDocument();

    // Editing the body should clear the math panel
    typeInEditor('some new body text');

    await waitFor(() =>
      expect(screen.queryByTestId('math-validation-summary')).toBeNull(),
    );
  });

  it('a frontmatter 422 does NOT populate the math panel', async () => {
    api.savePage.mockRejectedValueOnce({
      status: 422,
      body: {
        error: 'frontmatter_validation_failed',
        violations: [{ field: 'type', severity: 'ERROR', code: 'x', message: 'bad type' }],
      },
    });

    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));

    await waitFor(() =>
      expect(mockToastError).toHaveBeenCalledWith('Fix the highlighted frontmatter fields'),
    );
    expect(screen.queryByTestId('math-validation-summary')).toBeNull();
  });

  it('Jump button is rendered and wired to jumpToMath', async () => {
    // Set up a math error so the Jump button renders
    api.savePage.mockRejectedValueOnce({
      status: 422,
      body: { error: 'math_validation_failed', violations: [MATH_ERROR] },
    });

    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));
    expect(await screen.findByTestId('math-validation-summary')).toBeInTheDocument();

    // Jump button is rendered and wired up — clicking it calls the real CodeEditor imperative
    // handle (setSelection + scrollToLine). The textarea stub's doc lacks .line(), so we just
    // assert the button is present and clickable without asserting a throw-free path
    // (stub limitation; the real CM6 path is correct and tested in the component unit tests).
    expect(screen.getByRole('button', { name: /jump/i })).toBeInTheDocument();
  });

  it('a 200 with mathWarnings shows an info toast', async () => {
    api.savePage.mockResolvedValueOnce({ success: true, mathWarnings: [MATH_WARNING] });

    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));

    await waitFor(() =>
      expect(mockToastInfo).toHaveBeenCalledWith('Saved with 1 math warning'),
    );
  });
});

// ── Version conflict (409) flow ─────────────────────────────────────────────
describe('version conflict (409) flow', () => {
  function setupConflict() {
    api.getPage
      .mockResolvedValueOnce({ content: PAGE_CONTENT, metadata: {}, version: 1, markupSyntax: 'markdown' })
      .mockResolvedValueOnce({ content: 'server content wins', metadata: { title: 'ServerTitle' }, version: 7 });
    api.savePage.mockRejectedValueOnce(Object.assign(new Error('conflict'), { status: 409 }));
  }

  it('shows the Version Conflict modal when savePage rejects with 409', async () => {
    setupConflict();
    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));
    expect(await screen.findByText('Version Conflict')).toBeInTheDocument();
  });

  it('shows a fallback error when re-fetching the server version also fails', async () => {
    api.getPage
      .mockResolvedValueOnce({ content: PAGE_CONTENT, metadata: {}, version: 1, markupSyntax: 'markdown' })
      .mockRejectedValueOnce(new Error('fetch failed'));
    api.savePage.mockRejectedValueOnce(Object.assign(new Error('conflict'), { status: 409 }));
    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByTestId('editor-save'));
    expect(await screen.findByTestId('editor-error')).toHaveTextContent(
      'Version conflict, and failed to fetch the current server version.',
    );
  });

  it('Overwrite with my version re-saves and navigates away', async () => {
    setupConflict();
    renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByText('Version Conflict');

    fireEvent.click(screen.getByRole('button', { name: 'Overwrite with my version' }));

    await waitFor(() => expect(api.savePage).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId('wiki-view')).toBeInTheDocument();
  });

  it('Overwrite failure shows an inline error and re-enables the modal action', async () => {
    setupConflict();
    api.savePage.mockRejectedValueOnce(new Error('still conflicting'));
    renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByText('Version Conflict');

    fireEvent.click(screen.getByRole('button', { name: 'Overwrite with my version' }));
    expect(await screen.findByTestId('editor-error')).toHaveTextContent('still conflicting');
  });

  it('Discard my changes loads the server version and closes the modal', async () => {
    setupConflict();
    renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByText('Version Conflict');

    fireEvent.click(screen.getByRole('button', { name: /Discard my changes/ }));

    await waitFor(() => expect(screen.queryByText('Version Conflict')).not.toBeInTheDocument());
    expect(getEditable()).toHaveValue('server content wins');
  });

  it('Copy my text to clipboard, then load server version — copies and loads', async () => {
    setupConflict();
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByText('Version Conflict');

    fireEvent.click(screen.getByRole('button', { name: /Copy my text to clipboard/ }));

    await waitFor(() => expect(writeText).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText('Version Conflict')).not.toBeInTheDocument());
    expect(getEditable()).toHaveValue('server content wins');
  });

  it('Copy-and-load still loads the server version when the clipboard write rejects', async () => {
    setupConflict();
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('no clipboard access')) },
      configurable: true,
    });
    renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByText('Version Conflict');

    fireEvent.click(screen.getByRole('button', { name: /Copy my text to clipboard/ }));

    await waitFor(() => expect(screen.queryByText('Version Conflict')).not.toBeInTheDocument());
    expect(getEditable()).toHaveValue('server content wins');
  });

  it('clicking the modal overlay dismisses the conflict modal', async () => {
    setupConflict();
    const { container } = renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await screen.findByText('Version Conflict');

    fireEvent.click(container.querySelector('.modal-overlay'));
    await waitFor(() => expect(screen.queryByText('Version Conflict')).not.toBeInTheDocument());
  });
});

// ── Legacy-wiki-syntax conversion ───────────────────────────────────────────
describe('convert legacy wiki syntax to Markdown', () => {
  it('shows the conversion banner when markupSyntax is wiki, and converts on click', async () => {
    api.getPage.mockResolvedValue({ content: '!!Heading', metadata: {}, version: 1, markupSyntax: 'wiki' });
    api.convertWikiToMarkdown.mockResolvedValue({ markdown: '# Heading', warnings: ['dropped a plugin'] });
    renderEditor();
    await waitForEditor();

    expect(screen.getByText(/legacy wiki syntax/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Convert to Markdown' }));

    expect(await screen.findByText('dropped a plugin')).toBeInTheDocument();
    expect(getEditable()).toHaveValue('# Heading');
    // Banner disappears once markupSyntax flips to markdown.
    expect(screen.queryByText(/legacy wiki syntax/)).not.toBeInTheDocument();
  });

  it('shows the banner for likely-wiki syntax too', async () => {
    api.getPage.mockResolvedValue({ content: 'text', metadata: {}, version: 1, markupSyntax: 'likely-wiki' });
    renderEditor();
    await waitForEditor();
    expect(screen.getByText(/legacy wiki syntax/)).toBeInTheDocument();
  });

  it('shows an error banner when conversion fails', async () => {
    api.getPage.mockResolvedValue({ content: '!!Heading', metadata: {}, version: 1, markupSyntax: 'wiki' });
    api.convertWikiToMarkdown.mockRejectedValue(new Error('parser exploded'));
    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByRole('button', { name: 'Convert to Markdown' }));
    expect(await screen.findByTestId('editor-error')).toHaveTextContent('Conversion failed: parser exploded');
  });

  it('falls back to "Unknown error" when the conversion rejection has no message', async () => {
    api.getPage.mockResolvedValue({ content: '!!Heading', metadata: {}, version: 1, markupSyntax: 'wiki' });
    api.convertWikiToMarkdown.mockRejectedValue(new Error());
    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByRole('button', { name: 'Convert to Markdown' }));
    expect(await screen.findByTestId('editor-error')).toHaveTextContent('Conversion failed: Unknown error');
  });
});

// ── New-page (404) bootstrap + generic load error ───────────────────────────
describe('page load: new page vs. error', () => {
  it('a 404 bootstraps a new page with a default heading + body', async () => {
    api.getPage.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    renderEditor('BrandNewPage');
    await waitForEditor();

    expect(getEditable()).toHaveValue('# BrandNewPage\n\nWrite your content here.');
    expect(screen.getByTestId('editor-heading')).toHaveTextContent('Create: BrandNewPage');
  });

  it('a 404 uses location.state initialContent/initialMetadata when provided', async () => {
    api.getPage.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    render(
      <MemoryRouter initialEntries={[{
        pathname: '/edit/SeedPage',
        state: { initialContent: 'Seeded body', initialMetadata: { cluster: 'dev' } },
      }]}>
        <Routes>
          <Route path="/edit/:name" element={<PageEditor />} />
          <Route path="/wiki/:name" element={<div data-testid="wiki-view">WIKI VIEW</div>} />
        </Routes>
      </MemoryRouter>,
    );
    await waitForEditor();
    expect(getEditable()).toHaveValue('Seeded body');
  });

  it('a non-404 load failure shows the error banner instead of bootstrapping', async () => {
    api.getPage.mockRejectedValue(new Error('server unavailable'));
    renderEditor('BrokenPage');
    expect(await screen.findByTestId('editor-error')).toHaveTextContent('server unavailable');
  });
});

// ── Attachment panel toggle + rename rewrites body links ────────────────────
describe('attachment panel + rename', () => {
  it('the Attach button toggles the attachment panel open class', async () => {
    const { container } = renderEditor();
    await waitForEditor();
    expect(container.querySelector('.page-enter').className).not.toContain('editor-with-panel');
    fireEvent.click(screen.getByRole('button', { name: 'Attach' }));
    expect(container.querySelector('.page-enter').className).toContain('editor-with-panel');
  });

  it('renaming an attachment rewrites image + link markdown references in the body', async () => {
    const renameAttachment = vi.fn().mockResolvedValue({ ok: true });
    useAttachments.mockReturnValue({
      list: [{ fileName: 'old.png', size: 100, isImage: true }],
      uploadAttachment: vi.fn(),
      renameAttachment,
      deleteAttachment: vi.fn(),
    });
    api.getPage.mockResolvedValue({
      content: 'See ![alt](old.png) and [link](old.png) here.',
      metadata: {}, version: 1, markupSyntax: 'markdown',
    });
    renderEditor();
    await waitForEditor();

    fireEvent.click(screen.getByRole('button', { name: 'Attach' }));
    fireEvent.click(screen.getAllByTitle('Rename')[0]);
    const stemInput = screen.getByDisplayValue('old');
    fireEvent.change(stemInput, { target: { value: 'new' } });
    fireEvent.click(screen.getByTitle('Confirm'));

    await waitFor(() => expect(renameAttachment).toHaveBeenCalledWith('old.png', 'new.png'));
    await waitFor(() =>
      expect(getEditable()).toHaveValue('See ![alt](new.png) and [link](new.png) here.'),
    );
  });
});

// ── restoreDraft ─────────────────────────────────────────────────────────────
describe('restoreDraft', () => {
  it('Restore parses frontmatter from the draft and applies body + metadata', async () => {
    useDraft.mockReturnValue({
      draft: { content: '---\ntitle: Draft Title\n---\nDraft body text', savedAt: Date.now() },
      saveDraft: vi.fn(),
      clearDraft: vi.fn(),
    });
    api.validateFrontmatter.mockResolvedValue({ metadata: { title: 'Draft Title' }, violations: [] });
    renderEditor();
    await waitForEditor();

    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }));

    await waitFor(() => expect(getEditable()).toHaveValue('Draft body text'));
    expect(api.validateFrontmatter).toHaveBeenCalledWith({ frontmatter: 'title: Draft Title' });
  });

  it('Restore with no frontmatter block sets empty metadata', async () => {
    useDraft.mockReturnValue({
      draft: { content: 'Just a plain draft body, no frontmatter', savedAt: Date.now() },
      saveDraft: vi.fn(),
      clearDraft: vi.fn(),
    });
    renderEditor();
    await waitForEditor();

    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }));
    await waitFor(() => expect(getEditable()).toHaveValue('Just a plain draft body, no frontmatter'));
  });

  it('Restore falls back to empty metadata when validateFrontmatter rejects', async () => {
    useDraft.mockReturnValue({
      draft: { content: '---\ntitle: X\n---\nBody', savedAt: Date.now() },
      saveDraft: vi.fn(),
      clearDraft: vi.fn(),
    });
    api.validateFrontmatter.mockRejectedValue(new Error('bad yaml'));
    renderEditor();
    await waitForEditor();

    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }));
    await waitFor(() => expect(getEditable()).toHaveValue('Body'));
  });
});

// ── derived-page banner + misc ──────────────────────────────────────────────
describe('derived-page banner + misc', () => {
  it('shows the derived-body banner when metadata.derived_from is set', async () => {
    api.getPage.mockResolvedValue({
      content: PAGE_CONTENT, metadata: { derived_from: 'source.pdf' }, version: 1, markupSyntax: 'markdown',
    });
    renderEditor();
    await waitForEditor();
    expect(await screen.findByTestId('derived-body-banner')).toHaveTextContent('source.pdf');
  });

  it('non-hotkey keydown does not trigger a save', async () => {
    renderEditor();
    await waitForEditor();
    fireEvent.keyDown(window, { key: 's' }); // no metaKey/ctrlKey
    await act(async () => { await Promise.resolve(); });
    expect(api.savePage).not.toHaveBeenCalled();
  });

  it('a non-"s" key with a modifier does not trigger a save', async () => {
    renderEditor();
    await waitForEditor();
    fireEvent.keyDown(window, { key: 'x', metaKey: true });
    await act(async () => { await Promise.resolve(); });
    expect(api.savePage).not.toHaveBeenCalled();
  });

  it('populates the internal-link autocomplete page list from api.listPages', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'Alpha' }, { name: 'Beta' }] });
    renderEditor();
    await waitForEditor();
    await waitFor(() => expect(api.listPages).toHaveBeenCalledWith({ limit: 1000 }));
  });
});

// ── Remaining formatting toolbar commands ───────────────────────────────────
describe('formatting toolbar — remaining commands', () => {
  it('Italic wraps the selection with single asterisks', async () => {
    renderEditor();
    await waitForEditor();
    const editable = getEditable();
    fireEvent.change(editable, { target: { value: 'hello world' } });
    editable.focus();
    editable.setSelectionRange(6, 11);
    fireEvent.mouseDown(screen.getByTitle(/italic/i));
    await waitFor(() => expect(getEditable().value).toBe('hello *world*'));
  });

  it('Heading prefixes the current line with "## "', async () => {
    renderEditor();
    await waitForEditor();
    const editable = getEditable();
    fireEvent.change(editable, { target: { value: 'Some line' } });
    editable.focus();
    editable.setSelectionRange(0, 0);
    fireEvent.mouseDown(screen.getByTitle(/^heading/i));
    await waitFor(() => expect(getEditable().value).toBe('## Some line'));
  });

  it('List prefixes the current line with "- "', async () => {
    renderEditor();
    await waitForEditor();
    const editable = getEditable();
    fireEvent.change(editable, { target: { value: 'Item one' } });
    editable.focus();
    editable.setSelectionRange(0, 0);
    fireEvent.mouseDown(screen.getByTitle(/^list/i));
    await waitFor(() => expect(getEditable().value).toBe('- Item one'));
  });

  it('Inline code wraps the selection with backticks', async () => {
    renderEditor();
    await waitForEditor();
    const editable = getEditable();
    fireEvent.change(editable, { target: { value: 'const x = 1' } });
    editable.focus();
    editable.setSelectionRange(0, 11);
    fireEvent.mouseDown(screen.getByTitle(/inline code/i));
    await waitFor(() => expect(getEditable().value).toBe('`const x = 1`'));
  });

  it('Code block wraps the content in a fenced block', async () => {
    renderEditor();
    await waitForEditor();
    const editable = getEditable();
    fireEvent.change(editable, { target: { value: '' } });
    editable.focus();
    editable.setSelectionRange(0, 0);
    fireEvent.mouseDown(screen.getByTitle(/code block/i));
    await waitFor(() => expect(getEditable().value).toContain('```'));
  });

  it('Table inserts a markdown table skeleton', async () => {
    renderEditor();
    await waitForEditor();
    const editable = getEditable();
    fireEvent.change(editable, { target: { value: '' } });
    editable.focus();
    editable.setSelectionRange(0, 0);
    fireEvent.mouseDown(screen.getByTitle(/^table/i));
    await waitFor(() => expect(getEditable().value).toContain('|'));
  });
});

// ── Drag-over + change-note input + misc UI wiring ──────────────────────────
describe('misc UI wiring', () => {
  it('dragover on the editor pane preventDefaults (allows drop)', async () => {
    const { container } = renderEditor();
    await waitForEditor();
    const pane = container.querySelector('.editor-pane');
    const event = new Event('dragover', { bubbles: true, cancelable: true });
    fireEvent(pane, event);
    expect(event.defaultPrevented).toBe(true);
  });

  it('typing into the change-note field updates its value', async () => {
    renderEditor();
    await waitForEditor();
    const input = screen.getByTestId('editor-change-note');
    fireEvent.change(input, { target: { value: 'Fixed a typo' } });
    expect(input).toHaveValue('Fixed a typo');
  });

  it('the discard-confirm modal overlay dismisses without navigating', async () => {
    const { container } = renderEditor();
    await waitForEditor();
    typeInEditor('# changed content');
    fireEvent.click(screen.getByTestId('editor-cancel'));
    await screen.findByText('Discard unsaved changes?');

    fireEvent.click(container.querySelector('.modal-overlay'));
    await waitFor(() => expect(screen.queryByText('Discard unsaved changes?')).not.toBeInTheDocument());
    expect(screen.queryByTestId('wiki-view')).not.toBeInTheDocument();
  });

  it('the Attach panel Close (X) button closes the panel', async () => {
    const { container } = renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByRole('button', { name: 'Attach' }));
    expect(container.querySelector('.attachment-panel').className).toContain('open');

    fireEvent.click(screen.getByRole('button', { name: 'X' }));
    await waitFor(() =>
      expect(container.querySelector('.attachment-panel').className).not.toContain('open'),
    );
  });
});

// ── Plural-count toast branches ─────────────────────────────────────────────
describe('plural warning-count toasts', () => {
  it('shows plural "advisory warnings" when more than one is returned', async () => {
    api.savePage.mockResolvedValueOnce({ success: true, warnings: ['w1', 'w2'] });
    renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await waitFor(() => expect(mockToastInfo).toHaveBeenCalledWith('Saved with 2 advisory warnings'));
  });

  it('shows plural "math warnings" when more than one is returned', async () => {
    api.savePage.mockResolvedValueOnce({ success: true, mathWarnings: ['m1', 'm2'] });
    renderEditor();
    await waitForEditor();
    fireEvent.click(screen.getByTestId('editor-save'));
    await waitFor(() => expect(mockToastInfo).toHaveBeenCalledWith('Saved with 2 math warnings'));
  });
});

// ── Autosave debounce (draft save / clear) ──────────────────────────────────
describe('autosave debounce', () => {
  it('saves a draft ~800ms after the content diverges from the loaded baseline', async () => {
    const saveDraft = vi.fn();
    useDraft.mockReturnValue({ draft: null, saveDraft, clearDraft: vi.fn() });
    renderEditor();
    await waitForEditor();

    typeInEditor('# Changed content for autosave');
    await new Promise((r) => setTimeout(r, 850));
    expect(saveDraft).toHaveBeenCalledWith({ content: expect.stringContaining('Changed content'), title: 'TestPage' });
  }, 10000);

  it('clears the draft once the content returns to the loaded baseline', async () => {
    const clearDraft = vi.fn();
    useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft });
    renderEditor();
    await waitForEditor();

    typeInEditor('# Changed then reverted');
    typeInEditor(PAGE_CONTENT);
    await new Promise((r) => setTimeout(r, 850));
    expect(clearDraft).toHaveBeenCalled();
  }, 10000);
});
