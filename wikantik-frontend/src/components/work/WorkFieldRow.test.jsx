import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import WorkFieldRow from './WorkFieldRow';

const OPTIONS = [{ value: '1', label: 'P1' }, { value: '2', label: 'P2' }];

describe('WorkFieldRow', () => {
  it('shows the value with an Edit button only when editable', () => {
    const { rerender } = render(<WorkFieldRow label="Priority" value="1" display="P1" canEdit type="select" options={OPTIONS} onSave={vi.fn()} />);
    expect(screen.getByText('P1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Priority' })).toBeInTheDocument();
    rerender(<WorkFieldRow label="Priority" value="1" display="P1" canEdit={false} type="select" options={OPTIONS} onSave={vi.fn()} />);
    expect(screen.queryByRole('button', { name: 'Edit Priority' })).toBeNull();
  });

  it('saves the parsed value and closes on success', async () => {
    const onSave = vi.fn().mockResolvedValue(true);
    render(<WorkFieldRow label="Priority" value="1" display="P1" canEdit type="select" options={OPTIONS} parse={Number} onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Priority' }));
    fireEvent.change(screen.getByLabelText('Priority'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Priority' }));
    expect(onSave).toHaveBeenCalledWith(2);
    await waitFor(() => expect(screen.queryByLabelText('Priority')).toBeNull());
  });

  it('keeps what was typed when the save does not go through', async () => {
    const onSave = vi.fn().mockResolvedValue(false);
    render(<WorkFieldRow label="Title" name="title" value="Old" display="Old" canEdit onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Typed text' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    await waitFor(() => expect(onSave).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save Title' })).not.toBeDisabled());
    expect(screen.getByLabelText('Title').value).toBe('Typed text');
  });

  it('Enter saves and Escape cancels, without saving', () => {
    const onSave = vi.fn().mockResolvedValue(true);
    render(<WorkFieldRow label="Title" value="Old" display="Old" canEdit onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.keyDown(screen.getByLabelText('Title'), { key: 'Escape' });
    expect(screen.queryByLabelText('Title')).toBeNull();
    expect(onSave).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New' } });
    fireEvent.keyDown(screen.getByLabelText('Title'), { key: 'Enter' });
    expect(onSave).toHaveBeenCalledWith('New');
  });

  it('does not send a request for an unchanged value', () => {
    const onSave = vi.fn();
    render(<WorkFieldRow label="Title" value="Same" display="Same" canEdit onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    expect(onSave).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Title')).toBeNull();
  });

  it('shows helper text', () => {
    render(<WorkFieldRow label="Autonomy" value="" display="Project default" helper="Takes effect in a later release" canEdit={false} onSave={vi.fn()} />);
    expect(screen.getByText('Takes effect in a later release')).toBeInTheDocument();
  });

  it('Enter on the Cancel or Save button does not trigger a save from the key handler', () => {
    const onSave = vi.fn().mockResolvedValue(true);
    render(<WorkFieldRow label="Title" value="Old" display="Old" canEdit onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New' } });
    fireEvent.keyDown(screen.getByRole('button', { name: 'Cancel editing Title' }), { key: 'Enter' });
    expect(onSave).not.toHaveBeenCalled();
  });

  it('moves focus into the editor, and back to the Edit button when it closes', async () => {
    render(<WorkFieldRow label="Title" value="Old" display="Old" canEdit onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    await waitFor(() => expect(screen.getByLabelText('Title')).toHaveFocus());
    fireEvent.click(screen.getByRole('button', { name: 'Cancel editing Title' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit Title' })).toHaveFocus());
  });

  it('returns focus to the Edit button after a successful save', async () => {
    render(<WorkFieldRow label="Title" value="Old" display="Old" canEdit onSave={vi.fn().mockResolvedValue(true)} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Title' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit Title' })).toHaveFocus());
  });

  it('is locked, with an accessible hint, while the page says another editor is open', () => {
    const onOpen = vi.fn();
    render(<WorkFieldRow label="Title" value="Old" display="Old" canEdit locked onOpen={onOpen} onSave={vi.fn()} />);
    const edit = screen.getByRole('button', { name: 'Edit Title' });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAccessibleDescription('Finish or cancel the open edit first');
    fireEvent.click(edit);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('reports open, draft and close to the page', () => {
    const h = { onOpen: vi.fn(), onDraft: vi.fn(), onClose: vi.fn() };
    render(<WorkFieldRow label="Title" value="Old" display="Old" canEdit onSave={vi.fn()} {...h} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Title' }));
    expect(h.onOpen).toHaveBeenCalledWith('Old');
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New' } });
    expect(h.onDraft).toHaveBeenCalledWith('New');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel editing Title' }));
    expect(h.onClose).toHaveBeenCalled();
  });
});
