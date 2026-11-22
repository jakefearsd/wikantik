import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../../utils/math', () => ({ renderMath: vi.fn(() => Promise.resolve()) }));
vi.mock('../../utils/fences/renderFences', () => ({ renderFences: vi.fn(() => Promise.resolve()) }));
vi.mock('../../api/work', () => ({ uploadAttachment: vi.fn() }));
import { uploadAttachment } from '../../api/work';
import WorkBodyEditor from './WorkBodyEditor';

const HTML = '<p>Saved <strong>text</strong></p>';
function setup(props = {}) {
  const handlers = { onSave: vi.fn(() => Promise.resolve(true)), onOpen: vi.fn(), onDraft: vi.fn(), onClose: vi.fn() };
  const utils = render(<WorkBodyEditor html={HTML} body="Saved **text**" canEdit {...handlers} {...props} />);
  return { ...handlers, ...utils };
}
const openEditor = () => fireEvent.click(screen.getByRole('button', { name: 'Edit text' }));
const box = () => screen.getByLabelText('Item text');

describe('WorkBodyEditor', () => {
  it('shows the rendered text with no edit button to someone who cannot edit', () => {
    setup({ canEdit: false });
    expect(screen.getByText('text')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit text' })).not.toBeInTheDocument();
  });

  it('opens a textarea holding the markdown and tells the page which text it opened against', () => {
    const { onOpen } = setup();
    openEditor();
    expect(box()).toHaveValue('Saved **text**');
    expect(onOpen).toHaveBeenCalledWith('Saved **text**');
  });

  it('opens an empty textarea for an item that has no text yet', () => {
    const { onOpen } = setup({ body: null, html: '' });
    expect(screen.getByText('No description.')).toBeInTheDocument();
    openEditor();
    expect(box()).toHaveValue('');
    expect(onOpen).toHaveBeenCalledWith('');
  });

  it('disables the edit button and explains why while another editor is open', () => {
    setup({ locked: true });
    const button = screen.getByRole('button', { name: 'Edit text' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-describedby');
    expect(button).toHaveAttribute('title');
    expect(document.getElementById(button.getAttribute('aria-describedby'))).toHaveTextContent(button.getAttribute('title'));
  });

  it('reports every keystroke as the draft', () => {
    const { onDraft } = setup();
    openEditor();
    fireEvent.change(box(), { target: { value: 'New words' } });
    expect(onDraft).toHaveBeenCalledWith('New words');
    expect(box()).toHaveValue('New words');
  });

  it('closes without saving when nothing was changed', () => {
    const { onSave, onClose } = setup();
    openEditor();
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    expect(onSave).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText('Item text')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit text' })).toBeInTheDocument();
  });

  it('saves the draft, closes on success and returns focus to the edit button', async () => {
    const { onSave, onClose } = setup();
    openEditor();
    fireEvent.change(box(), { target: { value: 'Changed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    expect(onSave).toHaveBeenCalledWith('Changed');
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const edit = await screen.findByRole('button', { name: 'Edit text' });
    await waitFor(() => expect(edit).toHaveFocus());
  });

  it('ignores a second save while the first is still in flight and disables the form', async () => {
    let finish;
    const onSave = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
    setup({ onSave });
    openEditor();
    fireEvent.change(box(), { target: { value: 'Changed' } });
    const saveButton = screen.getByRole('button', { name: 'Save text' });
    fireEvent.click(saveButton);
    await waitFor(() => expect(saveButton).toBeDisabled());
    expect(box()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    fireEvent.click(saveButton);
    expect(onSave).toHaveBeenCalledTimes(1);
    finish(false);
    await waitFor(() => expect(saveButton).toBeEnabled());
  });

  it('keeps the editor and the typed text open when the page does not accept the save', async () => {
    const onSave = vi.fn(() => Promise.resolve(false));
    const { onClose } = setup({ onSave });
    openEditor();
    fireEvent.change(box(), { target: { value: 'Changed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save text' })).toBeEnabled());
    expect(box()).toHaveValue('Changed');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('keeps the typed text and logs when the save call throws', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onSave = vi.fn(() => Promise.reject(new Error('boom')));
    const { onClose } = setup({ onSave });
    openEditor();
    fireEvent.change(box(), { target: { value: 'Changed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save text' }));
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkBodyEditor: saving the text failed', expect.any(Error)));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save text' })).toBeEnabled());
    expect(box()).toHaveValue('Changed');
    expect(onClose).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it('shows the server message beside the editor', () => {
    setup({ error: 'Text is too long.' });
    openEditor();
    expect(screen.getByRole('alert')).toHaveTextContent('Text is too long.');
  });

  it('cancel discards the draft, closes and tells the page', () => {
    const { onClose } = setup();
    openEditor();
    fireEvent.change(box(), { target: { value: 'Changed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    openEditor();
    expect(box()).toHaveValue('Saved **text**');
  });

  it('shows the last saved version, not the draft, and keeps the draft for when the person goes back', () => {
    setup();
    openEditor();
    fireEvent.change(box(), { target: { value: 'Unsaved draft' } });
    fireEvent.click(screen.getByRole('button', { name: 'Saved version' }));
    expect(screen.getByText(/last saved version, not your draft/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Item text')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Saved version' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Write' }));
    expect(box()).toHaveValue('Unsaved draft');
  });

  it('pastingAnImageReportsTheNewDraft', async () => {
    uploadAttachment.mockResolvedValue({ id: 4, markdown: '![shot](work-attachment:4)' });
    const attach = { itemKey: 'PLAT-1', toast: { error: vi.fn() }, onUploaded: vi.fn() };
    const { onDraft } = setup({ attach });
    openEditor();
    box().setSelectionRange(5, 5);
    const file = new File(['x'], 'image.png', { type: 'image/png' });
    fireEvent.paste(box(), { clipboardData: { files: [file], getData: () => '' } });
    await waitFor(() => expect(box()).toHaveValue('Saved![shot](work-attachment:4) **text**'));
    expect(onDraft).toHaveBeenLastCalledWith('Saved![shot](work-attachment:4) **text**');
    expect(attach.onUploaded).toHaveBeenCalled();
  });

  it('anUploadThatFinishesAfterTheEditorClosedChangesNothing', async () => {
    let finish;
    uploadAttachment.mockReturnValue(new Promise((r) => { finish = r; }));
    const { onDraft } = setup({ attach: { itemKey: 'PLAT-1', toast: { error: vi.fn() } } });
    openEditor();
    const file = new File(['x'], 'image.png', { type: 'image/png' });
    fireEvent.paste(box(), { clipboardData: { files: [file], getData: () => '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    onDraft.mockClear();
    finish({ id: 4, markdown: '![shot](work-attachment:4)' });
    await Promise.resolve(); await Promise.resolve();
    expect(onDraft).not.toHaveBeenCalled();
  });
});
