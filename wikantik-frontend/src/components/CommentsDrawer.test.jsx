import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CommentsDrawer from './CommentsDrawer';

const threads = [
  { id: 'T1', status: 'open', anchor: { exact: 'alpha' }, createdBy: 'alice',
    comments: [{ id: 'C1', author: 'alice', body: 'first note' }] },
  { id: 'T2', status: 'resolved', anchor: { exact: 'beta' }, createdBy: 'bob',
    comments: [{ id: 'C2', author: 'bob', body: 'done note' }] },
];

function setup(extra = {}) {
  const props = {
    open: true, threads, detachedIds: [], statusFilter: 'open',
    onStatusFilter: vi.fn(), onReply: vi.fn(), onResolve: vi.fn(),
    onReopen: vi.fn(), onDeleteThread: vi.fn(), onFocusThread: vi.fn(),
    onClose: vi.fn(), canModerate: false, ...extra,
  };
  const utils = render(<CommentsDrawer {...props} />);
  return { ...utils, ...props };
}

describe('CommentsDrawer', () => {
  it('shows only open threads when filter is open', () => {
    setup({ statusFilter: 'open' });
    expect(screen.getByText('first note')).toBeTruthy();
    expect(screen.queryByText('done note')).toBeNull();
  });

  it('shows resolved threads when filter is resolved', () => {
    setup({ statusFilter: 'resolved' });
    expect(screen.getByText('done note')).toBeTruthy();
    expect(screen.queryByText('first note')).toBeNull();
  });

  it('fires onResolve for an open thread', () => {
    const props = setup({ statusFilter: 'open' });
    fireEvent.click(screen.getByRole('button', { name: /resolve/i }));
    expect(props.onResolve).toHaveBeenCalledWith('T1');
  });

  it('fires onReply with thread id and text', () => {
    const props = setup({ statusFilter: 'open' });
    fireEvent.change(screen.getByPlaceholderText(/reply/i), { target: { value: 'me too' } });
    fireEvent.click(screen.getByRole('button', { name: /^reply$/i }));
    expect(props.onReply).toHaveBeenCalledWith('T1', 'me too');
  });

  it('renders a Detached group for orphaned threads', () => {
    setup({ statusFilter: 'all', detachedIds: ['T1'] });
    expect(screen.getByText(/detached/i)).toBeTruthy();
  });

  it('hides the Delete-thread control by default (non-moderator)', () => {
    setup({ statusFilter: 'open' });
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull();
  });

  it('shows Delete on every thread for a moderator and reveals an in-app confirm', () => {
    const props = setup({ statusFilter: 'open', canModerate: true });
    const deleteBtn = screen.getByRole('button', { name: /delete/i });
    fireEvent.click(deleteBtn);
    // Two-step confirm replaces the action row with an in-app prompt — NOT a native dialog.
    expect(screen.getByText(/delete this thread permanently/i)).toBeTruthy();
    // Reply/Resolve are hidden while confirming.
    expect(screen.queryByPlaceholderText(/reply/i)).toBeNull();
    // Callback is NOT fired yet.
    expect(props.onDeleteThread).not.toHaveBeenCalled();
  });

  it('Cancel reverts the confirm without firing onDeleteThread', () => {
    const props = setup({ statusFilter: 'open', canModerate: true });
    fireEvent.click(screen.getByRole('button', { name: /^🗑 Delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /^cancel$/i }));
    expect(props.onDeleteThread).not.toHaveBeenCalled();
    // Reply/Resolve come back.
    expect(screen.getByPlaceholderText(/reply/i)).toBeTruthy();
  });

  it('confirming the delete fires onDeleteThread with the thread id', () => {
    const props = setup({ statusFilter: 'open', canModerate: true });
    fireEvent.click(screen.getByRole('button', { name: /^🗑 Delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    expect(props.onDeleteThread).toHaveBeenCalledWith('T1');
  });

  it('moderator can also delete a resolved thread', () => {
    const props = setup({ statusFilter: 'resolved', canModerate: true });
    fireEvent.click(screen.getByRole('button', { name: /^🗑 Delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    expect(props.onDeleteThread).toHaveBeenCalledWith('T2');
  });

  it('marks the focused thread card with the "focused" class', () => {
    const { container } = setup({ statusFilter: 'open', focusedThreadId: 'T1' });
    const cards = container.querySelectorAll('.comment-thread');
    expect(cards.length).toBeGreaterThanOrEqual(1);
    const focused = container.querySelector('.comment-thread.focused');
    expect(focused).not.toBeNull();
    // Sanity: only ONE focused card.
    expect(container.querySelectorAll('.comment-thread.focused').length).toBe(1);
  });

  it('no .focused class when focusedThreadId is null', () => {
    const { container } = setup({ statusFilter: 'open', focusedThreadId: null });
    expect(container.querySelector('.comment-thread.focused')).toBeNull();
  });
});
