import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import GroupFormModal from './GroupFormModal';

// Contract: { group, isOpen, onClose, onSave }. onSave receives
// { name, members } and the parent owns persistence.

const memberInput = (container) =>
  container.querySelector('input[placeholder="Enter member name…"]');
const nameInput = (container) =>
  container.querySelectorAll('input[type="text"]')[0];

describe('GroupFormModal — gating and modes', () => {
  it('renders nothing when closed', () => {
    const { container } = render(<GroupFormModal isOpen={false} onClose={vi.fn()} onSave={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it('create mode: empty form, "No members yet", enabled name', () => {
    const { container } = render(<GroupFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'Create Group' })).toBeTruthy();
    expect(screen.getByText('No members yet')).toBeTruthy();
    expect(nameInput(container).disabled).toBe(false);
  });

  it('edit mode: prefilled name (disabled) and member list', () => {
    const { container } = render(
      <GroupFormModal isOpen group={{ name: 'editors', members: ['alice', 'bob'] }} onClose={vi.fn()} onSave={vi.fn()} />
    );
    expect(screen.getByText('Edit Group')).toBeTruthy();
    expect(nameInput(container).value).toBe('editors');
    expect(nameInput(container).disabled).toBe(true);
    expect(screen.getByText('alice')).toBeTruthy();
    expect(screen.getByText('bob')).toBeTruthy();
  });
});

describe('GroupFormModal — member add/remove', () => {
  it('adds a member via the Add button', () => {
    const { container } = render(<GroupFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    fireEvent.change(memberInput(container), { target: { value: 'carol' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByText('carol')).toBeTruthy();
    // input clears after add
    expect(memberInput(container).value).toBe('');
  });

  it('adds a member via Enter key', () => {
    const { container } = render(<GroupFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    const input = memberInput(container);
    fireEvent.change(input, { target: { value: 'dave' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('dave')).toBeTruthy();
  });

  it('rejects a duplicate member with an inline error', () => {
    const { container } = render(
      <GroupFormModal isOpen group={{ name: 'g', members: ['alice'] }} onClose={vi.fn()} onSave={vi.fn()} />
    );
    fireEvent.change(memberInput(container), { target: { value: 'alice' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByText('"alice" is already a member')).toBeTruthy();
  });

  it('ignores blank member additions', () => {
    const { container } = render(<GroupFormModal isOpen onClose={vi.fn()} onSave={vi.fn()} />);
    fireEvent.change(memberInput(container), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByText('No members yet')).toBeTruthy();
  });

  it('removes a member', () => {
    render(<GroupFormModal isOpen group={{ name: 'g', members: ['alice', 'bob'] }} onClose={vi.fn()} onSave={vi.fn()} />);
    // Each member row has its own Remove button; remove alice (first).
    const removeButtons = screen.getAllByRole('button', { name: 'Remove' });
    fireEvent.click(removeButtons[0]);
    expect(screen.queryByText('alice')).toBeNull();
    expect(screen.getByText('bob')).toBeTruthy();
  });
});

describe('GroupFormModal — submit and cancel', () => {
  it('submits { name, members } and closes on success', async () => {
    const onSave = vi.fn().mockResolvedValue();
    const onClose = vi.fn();
    const { container } = render(<GroupFormModal isOpen onClose={onClose} onSave={onSave} />);
    fireEvent.change(nameInput(container), { target: { value: 'newgroup' } });
    fireEvent.change(memberInput(container), { target: { value: 'alice' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    fireEvent.click(screen.getByRole('button', { name: 'Create Group' }));
    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({ name: 'newgroup', members: ['alice'] })
    );
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('shows an error and stays open when onSave rejects', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('group exists'));
    const onClose = vi.fn();
    const { container } = render(<GroupFormModal isOpen onClose={onClose} onSave={onSave} />);
    fireEvent.change(nameInput(container), { target: { value: 'g' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create Group' }));
    await screen.findByText('group exists');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('Cancel calls onClose without saving', () => {
    const onSave = vi.fn();
    const onClose = vi.fn();
    render(<GroupFormModal isOpen onClose={onClose} onSave={onSave} />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(onSave).not.toHaveBeenCalled();
  });
});
