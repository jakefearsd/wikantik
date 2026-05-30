import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AttachmentPanel from './AttachmentPanel';

const SAMPLE_ATTACHMENTS = [
  { fileName: 'photo.png', size: 4096, isImage: true },
  { fileName: 'data.csv', size: 1024, isImage: false },
];

function renderPanel(overrides = {}) {
  const props = {
    open: true,
    onClose: vi.fn(),
    pageName: 'TestPage',
    attachments: SAMPLE_ATTACHMENTS,
    onUpload: vi.fn(),
    onRename: vi.fn(),
    onDelete: vi.fn(),
    editorContent: '',
    ...overrides,
  };
  return { ...render(<AttachmentPanel {...props} />), props };
}

describe('AttachmentPanel — inline delete confirm (#2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('clicking delete shows inline confirm prompt, does NOT call onDelete yet', () => {
    const { props } = renderPanel();
    const deleteBtn = screen.getAllByTitle('Delete')[0];
    fireEvent.click(deleteBtn);

    // Inline prompt should appear — the confirm Delete button should be visible
    expect(screen.getByRole('button', { name: /^Delete$/ })).toBeInTheDocument();
    // onDelete must not have been called
    expect(props.onDelete).not.toHaveBeenCalled();
  });

  it('confirming inline prompt calls onDelete with the filename', () => {
    const { props } = renderPanel();
    const deleteBtn = screen.getAllByTitle('Delete')[0];
    fireEvent.click(deleteBtn);

    // Find and click the confirm button (the "Delete" confirm action)
    const confirmBtn = screen.getByRole('button', { name: /^Delete$/ });
    fireEvent.click(confirmBtn);

    expect(props.onDelete).toHaveBeenCalledWith('photo.png');
  });

  it('cancelling inline prompt does not call onDelete', () => {
    const { props } = renderPanel();
    const deleteBtn = screen.getAllByTitle('Delete')[0];
    fireEvent.click(deleteBtn);

    fireEvent.click(screen.getByRole('button', { name: /Cancel/ }));
    expect(props.onDelete).not.toHaveBeenCalled();
  });

  it('window.confirm is never invoked', () => {
    const confirmSpy = vi.spyOn(window, 'confirm');
    const { props } = renderPanel();
    const deleteBtn = screen.getAllByTitle('Delete')[0];
    fireEvent.click(deleteBtn);

    const confirmBtn = screen.getByRole('button', { name: /^Delete$/ });
    fireEvent.click(confirmBtn);

    expect(confirmSpy).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('shows usage-count warning when file is referenced in editor content', () => {
    const { props } = renderPanel({
      editorContent: '![photo](photo.png) and some text',
    });
    const deleteBtn = screen.getAllByTitle('Delete')[0];
    fireEvent.click(deleteBtn);

    expect(screen.getByText(/referenced/i)).toBeInTheDocument();
  });
});
