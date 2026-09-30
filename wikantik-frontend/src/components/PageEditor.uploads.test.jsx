/**
 * PageEditor paste/drop upload flow. CodeEditor is stubbed with a button that calls its `onFiles`
 * prop, because the CodeMirror DOM handlers cannot run under happy-dom (their logic lives in the
 * tested editorFileEvents / useAttachmentUpload units).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- flush async state between tests
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('./CodeEditor', async () => {
  const React = (await vi.importActual('react')).default;
  return {
    default: React.forwardRef(function CodeEditorStub({ onFiles, value }, ref) {
      React.useImperativeHandle(ref, () => ({
        getSelection: () => ({ selStart: 0, selEnd: 0 }),
        setSelection() {}, focus() {}, getViewport: () => null, scrollToLine() {},
        getScrollerRect: () => null, jumpToLineAligned() {},
      }));
      return React.createElement('div', null,
        React.createElement('pre', { 'data-testid': 'body-value' }, value),
        React.createElement('button', {
          type: 'button',
          'data-testid': 'fake-paste',
          onClick: () => onFiles([new File(['x'], 'image.png', { type: 'image/png' })], 0, { pasted: true }),
        }, 'paste'));
    }),
  };
});

vi.mock('../api/client', () => ({
  api: {
    getPage: vi.fn(),
    savePage: vi.fn(),
    uploadAttachment: vi.fn(),
    listAttachments: vi.fn(),
    listPages: vi.fn(() => Promise.resolve({ pages: [] })),
    getFrontmatterSchema: vi.fn(() => Promise.resolve({ fields: [] })),
    validateFrontmatter: vi.fn(() => Promise.resolve({ metadata: {}, violations: [] })),
    search: vi.fn(() => Promise.resolve({ results: [] })),
    getPageKnowledge: vi.fn(() => Promise.resolve({ entities: [], edges: [] })),
  },
}));
vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../hooks/useDraft', () => ({ useDraft: vi.fn() }));
vi.mock('../hooks/useAttachments', () => ({ useAttachments: vi.fn() }));
vi.mock('../hooks/useEditorDrop', () => ({ useEditorDrop: vi.fn() }));
vi.mock('../hooks/useToast', () => ({ useToast: vi.fn() }));

import PageEditor from './PageEditor';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDraft } from '../hooks/useDraft';
import { useAttachments } from '../hooks/useAttachments';
import { useToast } from '../hooks/useToast';

function renderEditor(pageName) {
  return render(
    <MemoryRouter initialEntries={[`/edit/${pageName}`]}>
      <Routes>
        <Route path="/edit/:name" element={<PageEditor />} />
        <Route path="/wiki/:name" element={<div data-testid="wiki-view">WIKI VIEW</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: ['Admin'] } });
  useDraft.mockReturnValue({ draft: null, saveDraft: vi.fn(), clearDraft: vi.fn() });
  useAttachments.mockImplementation((pageName) => ({
    list: [],
    uploadAttachment: (f, n) => api.uploadAttachment(pageName, f, n),
    renameAttachment: vi.fn(),
    deleteAttachment: vi.fn(),
  }));
  useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  api.getPage.mockResolvedValue({ content: '# P', metadata: {}, version: 1, markupSyntax: 'markdown' });
  api.savePage.mockResolvedValue({ success: true, version: 1 });
  api.uploadAttachment.mockResolvedValue({ success: true });
  api.listAttachments.mockResolvedValue({ attachments: [] });
});

afterEach(async () => {
  cleanup();
  await act(async () => { await Promise.resolve(); });
});

describe('paste/drop uploads', () => {
  it('on a new page shows the save-first banner and does not upload', async () => {
    api.getPage.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    renderEditor('Fresh');
    fireEvent.click(await screen.findByTestId('fake-paste'));
    const banner = await screen.findByTestId('upload-needs-save');
    expect(banner).toHaveTextContent('Save the page once to add attachments');
    expect(api.uploadAttachment).not.toHaveBeenCalled();
  });

  it('"Save and upload" saves in place, then uploads without navigating away', async () => {
    api.getPage.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    renderEditor('Fresh');
    fireEvent.click(await screen.findByTestId('fake-paste'));
    fireEvent.click(await screen.findByText('Save and upload'));
    await waitFor(() => expect(api.uploadAttachment).toHaveBeenCalledTimes(1));
    expect(api.savePage).toHaveBeenCalledTimes(1);
    const [page, , name] = api.uploadAttachment.mock.calls[0];
    expect(page).toBe('Fresh');
    expect(name).toMatch(/^pasted-\d{8}-\d{6}\.png$/);
    expect(screen.queryByTestId('wiki-view')).toBeNull();
    expect(screen.queryByTestId('upload-needs-save')).toBeNull();
  });

  it('a second paste on an unsaved page is queued with the first, not replacing it', async () => {
    api.getPage.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    renderEditor('Fresh');
    const paste = await screen.findByTestId('fake-paste');
    fireEvent.click(paste);
    fireEvent.click(paste);
    fireEvent.click(await screen.findByText('Save and upload'));
    await waitFor(() => expect(api.uploadAttachment).toHaveBeenCalledTimes(2));
  });

  it('on an existing page uploads directly with no banner', async () => {
    renderEditor('Existing');
    fireEvent.click(await screen.findByTestId('fake-paste'));
    await waitFor(() => expect(api.uploadAttachment).toHaveBeenCalledTimes(1));
    expect(api.uploadAttachment.mock.calls[0][0]).toBe('Existing');
    expect(screen.queryByTestId('upload-needs-save')).toBeNull();
  });
});
