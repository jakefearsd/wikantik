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
    renderPanel();
    const deleteBtn = screen.getAllByTitle('Delete')[0];
    fireEvent.click(deleteBtn);

    const confirmBtn = screen.getByRole('button', { name: /^Delete$/ });
    fireEvent.click(confirmBtn);

    expect(confirmSpy).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('shows usage-count warning when file is referenced in editor content', () => {
    renderPanel({
      editorContent: '![photo](photo.png) and some text',
    });
    const deleteBtn = screen.getAllByTitle('Delete')[0];
    fireEvent.click(deleteBtn);

    expect(screen.getByText(/referenced/i)).toBeInTheDocument();
  });
});

vi.mock('../api/client', () => ({
  api: {
    ingestDocument: vi.fn(),
  },
}));

import { api } from '../api/client';

function makeFile(name, content = 'x', type = 'text/plain') {
  return new File([content], name, { type });
}

describe('AttachmentPanel — panel chrome', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('applies the open class when open=true and omits it when open=false', () => {
    const { container, rerender } = render(
      <AttachmentPanel open={true} onClose={() => {}} pageName="P" attachments={[]}
        onUpload={vi.fn()} onRename={vi.fn()} onDelete={vi.fn()} editorContent="" />,
    );
    expect(container.querySelector('.attachment-panel').className).toContain('open');

    rerender(
      <AttachmentPanel open={false} onClose={() => {}} pageName="P" attachments={[]}
        onUpload={vi.fn()} onRename={vi.fn()} onDelete={vi.fn()} editorContent="" />,
    );
    expect(container.querySelector('.attachment-panel').className).not.toContain('open');
  });

  it('clicking the header close button calls onClose', () => {
    const onClose = vi.fn();
    renderPanel({ onClose, attachments: [] });
    fireEvent.click(screen.getByText('X'));
    expect(onClose).toHaveBeenCalled();
  });

  it('shows the empty state when there are no attachments', () => {
    renderPanel({ attachments: [] });
    expect(screen.getByText('No attachments yet')).toBeInTheDocument();
  });

  it('formats sizes in B / KB / MB', () => {
    renderPanel({
      attachments: [
        { fileName: 'tiny.txt', size: 500, isImage: false },
        { fileName: 'mid.png', size: 4096, isImage: true },
        { fileName: 'big.zip', size: 5 * 1024 * 1024, isImage: false },
      ],
    });
    expect(screen.getByText('500 B')).toBeInTheDocument();
    expect(screen.getByText('4.0 KB')).toBeInTheDocument();
    expect(screen.getByText('5.0 MB')).toBeInTheDocument();
  });
});

describe('AttachmentPanel — upload form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('selecting a file defaults the name field to the filename stem and shows the extension badge', () => {
    renderPanel({ attachments: [] });
    const [fileInput] = screen.getAllByDisplayValue('');
    fireEvent.change(fileInput, { target: { files: [makeFile('report.pdf')] } });
    expect(screen.getByDisplayValue('report')).toBeInTheDocument();
    expect(screen.getByText('.pdf')).toBeInTheDocument();
  });

  it('selecting a file with no extension keeps the whole name as the stem', () => {
    renderPanel({ attachments: [] });
    const [fileInput] = screen.getAllByDisplayValue('');
    fireEvent.change(fileInput, { target: { files: [makeFile('README')] } });
    expect(screen.getByDisplayValue('README')).toBeInTheDocument();
  });

  it('shows a validation error and disables Upload for an invalid name', () => {
    renderPanel({ attachments: [] });
    const [fileInput] = screen.getAllByDisplayValue('');
    fireEvent.change(fileInput, { target: { files: [makeFile('ok.txt')] } });
    const nameInput = screen.getByPlaceholderText('filename');
    fireEvent.change(nameInput, { target: { value: 'bad name!' } });
    expect(screen.getByText(/Only a-z, 0-9, hyphens, underscores/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Upload' })).toBeDisabled();
  });

  it('uploading a valid file calls onUpload with the file + full name and resets the form on success', async () => {
    const onUpload = vi.fn().mockResolvedValue();
    renderPanel({ attachments: [], onUpload });
    const [fileInput] = screen.getAllByDisplayValue('');
    const file = makeFile('ok.txt');
    fireEvent.change(fileInput, { target: { files: [file] } });

    const uploadBtn = screen.getByRole('button', { name: 'Upload' });
    expect(uploadBtn).not.toBeDisabled();
    fireEvent.click(uploadBtn);
    expect(screen.getByText('Uploading...')).toBeInTheDocument();

    expect(onUpload).toHaveBeenCalledWith(file, 'ok.txt');
    // Form resets on success: the file is cleared, so the name input
    // (and its extension badge) disappear along with the whole sub-form.
    await vi.waitFor(() => expect(screen.queryByPlaceholderText('filename')).not.toBeInTheDocument());
  });

  it('shows the server error message when the upload rejects', async () => {
    const onUpload = vi.fn().mockRejectedValue(new Error('name taken'));
    renderPanel({ attachments: [], onUpload });
    const [fileInput] = screen.getAllByDisplayValue('');
    fireEvent.change(fileInput, { target: { files: [makeFile('ok.txt')] } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }));

    expect(await screen.findByText('name taken')).toBeInTheDocument();
  });

  it('falls back to a generic message when the rejected error has no message', async () => {
    const onUpload = vi.fn().mockRejectedValue(new Error());
    renderPanel({ attachments: [], onUpload });
    const [fileInput] = screen.getAllByDisplayValue('');
    fireEvent.change(fileInput, { target: { files: [makeFile('ok.txt')] } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }));

    expect(await screen.findByText('Upload failed')).toBeInTheDocument();
  });
});

describe('AttachmentPanel — ingest form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('Ingest button is disabled until a file is chosen', () => {
    renderPanel({ attachments: [] });
    expect(screen.getByRole('button', { name: 'Ingest' })).toBeDisabled();
  });

  it('ingesting a file calls api.ingestDocument and shows the resulting status + page link', async () => {
    api.ingestDocument.mockResolvedValue({ status: 'created', page: 'NewPage' });
    renderPanel({ attachments: [] });
    const fileInputs = screen.getAllByDisplayValue('');
    const ingestInput = fileInputs[1];
    const file = makeFile('doc.pdf');
    fireEvent.change(ingestInput, { target: { files: [file] } });

    fireEvent.click(screen.getByRole('button', { name: 'Ingest' }));
    expect(api.ingestDocument).toHaveBeenCalledWith(file);

    expect(await screen.findByText('created')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'NewPage' })).toHaveAttribute('href', '/wiki/NewPage');
  });

  it('shows the server error message when ingestion rejects', async () => {
    api.ingestDocument.mockRejectedValue(new Error('bad file'));
    renderPanel({ attachments: [] });
    const fileInputs = screen.getAllByDisplayValue('');
    fireEvent.change(fileInputs[1], { target: { files: [makeFile('doc.pdf')] } });
    fireEvent.click(screen.getByRole('button', { name: 'Ingest' }));

    expect(await screen.findByText('bad file')).toBeInTheDocument();
  });

  it('falls back to a generic message when the rejected error has no message', async () => {
    api.ingestDocument.mockRejectedValue(new Error());
    renderPanel({ attachments: [] });
    const fileInputs = screen.getAllByDisplayValue('');
    fireEvent.change(fileInputs[1], { target: { files: [makeFile('doc.pdf')] } });
    fireEvent.click(screen.getByRole('button', { name: 'Ingest' }));

    expect(await screen.findByText('Ingest failed')).toBeInTheDocument();
  });
});

describe('AttachmentPanel — row rename + drag', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('Rename shows a pre-filled stem input; Enter confirms via onRename', async () => {
    const onRename = vi.fn().mockResolvedValue();
    renderPanel({ onRename });
    fireEvent.click(screen.getAllByTitle('Rename')[0]);
    const input = screen.getByDisplayValue('photo');
    fireEvent.change(input, { target: { value: 'newphoto' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onRename).toHaveBeenCalledWith('photo.png', 'newphoto.png');
  });

  it('Escape cancels the rename without calling onRename', () => {
    const onRename = vi.fn();
    renderPanel({ onRename });
    fireEvent.click(screen.getAllByTitle('Rename')[0]);
    const input = screen.getByDisplayValue('photo');
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByDisplayValue('photo')).not.toBeInTheDocument();
    expect(onRename).not.toHaveBeenCalled();
  });

  it('the Cancel (X) rename button dismisses without calling onRename', () => {
    const onRename = vi.fn();
    renderPanel({ onRename });
    fireEvent.click(screen.getAllByTitle('Rename')[0]);
    fireEvent.click(screen.getByTitle('Cancel'));
    expect(onRename).not.toHaveBeenCalled();
  });

  it('confirming with the same name as the original just closes the rename UI (no call)', () => {
    const onRename = vi.fn();
    renderPanel({ onRename });
    fireEvent.click(screen.getAllByTitle('Rename')[0]);
    fireEvent.click(screen.getByTitle('Confirm'));
    expect(onRename).not.toHaveBeenCalled();
    expect(screen.queryByDisplayValue('photo')).not.toBeInTheDocument();
  });

  it('an invalid new name is silently rejected — stays in rename mode, no call', () => {
    const onRename = vi.fn();
    renderPanel({ onRename });
    fireEvent.click(screen.getAllByTitle('Rename')[0]);
    const input = screen.getByDisplayValue('photo');
    fireEvent.change(input, { target: { value: 'bad name!' } });
    fireEvent.click(screen.getByTitle('Confirm'));
    expect(onRename).not.toHaveBeenCalled();
    // Still in rename mode
    expect(screen.getByDisplayValue('bad name!')).toBeInTheDocument();
  });

  it('a rejected onRename is swallowed — the row stays in rename mode without throwing', async () => {
    const onRename = vi.fn().mockRejectedValue(new Error('server error'));
    renderPanel({ onRename });
    fireEvent.click(screen.getAllByTitle('Rename')[0]);
    const input = screen.getByDisplayValue('photo');
    fireEvent.change(input, { target: { value: 'newphoto' } });
    fireEvent.click(screen.getByTitle('Confirm'));
    await vi.waitFor(() => expect(onRename).toHaveBeenCalled());
    // Still rendered, no crash — rename mode persists since the catch swallows the error.
    expect(screen.getByDisplayValue('newphoto')).toBeInTheDocument();
  });

  it('dragstart sets the markdown payload on the dataTransfer and toggles the dragging class', () => {
    renderPanel();
    const rows = document.querySelectorAll('.attachment-row');
    const imageRow = rows[0]; // photo.png, isImage: true
    const setData = vi.fn();
    const dataTransfer = { setData, effectAllowed: null };
    fireEvent.dragStart(imageRow, { dataTransfer });
    expect(setData).toHaveBeenCalledWith('text/plain', '![photo](photo.png)');
    expect(imageRow.classList.contains('dragging')).toBe(true);

    fireEvent.dragEnd(imageRow, { dataTransfer });
    expect(imageRow.classList.contains('dragging')).toBe(false);
  });

  it('dragstart on a non-image attachment uses plain link syntax', () => {
    renderPanel();
    const rows = document.querySelectorAll('.attachment-row');
    const csvRow = rows[1]; // data.csv, isImage: false
    const setData = vi.fn();
    fireEvent.dragStart(csvRow, { dataTransfer: { setData, effectAllowed: null } });
    expect(setData).toHaveBeenCalledWith('text/plain', '[data](data.csv)');
  });

  it('renders an image thumbnail for image attachments and a file-icon badge for others', () => {
    renderPanel();
    expect(screen.getByAltText('photo.png')).toHaveAttribute('src', '/attach/TestPage/photo.png');
    expect(screen.getByText('csv')).toBeInTheDocument();
  });
});
