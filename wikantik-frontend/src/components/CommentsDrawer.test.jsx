import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import CommentsDrawer from './CommentsDrawer';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: { listMentionableUsers: vi.fn() },
}));
vi.mock('../utils/caretCoords', () => ({ getCaretCoordinates: vi.fn(() => ({ top: 1, left: 2 })) }));

// The API returns display names (`author`, `createdBy`) and opaque uids (`authorId`, `createdById`).
const threads = [
  { id: 'T1', status: 'open', anchor: { exact: 'alpha' }, createdBy: 'Alice A', createdById: 'u-alice',
    comments: [{ id: 'C1', author: 'Alice A', authorId: 'u-alice', body: 'first note' }] },
  { id: 'T2', status: 'resolved', anchor: { exact: 'beta' }, createdBy: 'Bob B', createdById: 'u-bob',
    comments: [{ id: 'C2', author: 'Bob B', authorId: 'u-bob', body: 'done note' }] },
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

  // --- #55: friendly empty state -------------------------------------------

  it('[#55] renders EmptyState when there are no visible threads', () => {
    setup({ statusFilter: 'open', threads: [] });
    // EmptyState renders an admin-empty-message class element with the message.
    expect(screen.getByText('No comments yet.')).toBeTruthy();
    // The old "No comments." text should not appear.
    expect(screen.queryByText('No comments.')).toBeNull();
  });

  it('[#55] renders EmptyState when all threads are filtered out', () => {
    // Only open threads exist; filter is resolved → nothing visible.
    setup({ statusFilter: 'resolved', threads: [threads[0]] }); // only the open thread (T1)
    expect(screen.getByText('No comments yet.')).toBeTruthy();
  });

  describe('display names and "mine"', () => {
    it('shows the display name of each comment author', () => {
      setup({ statusFilter: 'all' });
      expect(screen.getByText('Alice A')).toBeTruthy();
      expect(screen.getByText('Bob B')).toBeTruthy();
    });

    it('marks the viewer\'s own comment when authorId matches user.userId', () => {
      const { container } = setup({ statusFilter: 'all', currentUserId: 'u-alice' });
      const mine = container.querySelectorAll('.comment-item.mine');
      expect(mine).toHaveLength(1);
      expect(mine[0].textContent).toContain('first note');
      expect(mine[0].querySelector('.comment-author-you').textContent).toBe('(you)');
    });

    it('marks nothing as mine for an anonymous viewer', () => {
      const { container } = setup({ statusFilter: 'all', currentUserId: null });
      expect(container.querySelector('.comment-item.mine')).toBeNull();
      expect(screen.queryByText('(you)')).toBeNull();
    });

    it('compares ids, not names: a different member with the same display name is not mine', () => {
      const lookalike = [{ id: 'T9', status: 'open', anchor: { exact: 'x' },
        comments: [{ id: 'C9', author: 'Alice A', authorId: 'u-impostor', body: 'hello' }] }];
      const { container } = setup({ statusFilter: 'open', threads: lookalike, currentUserId: 'u-alice' });
      expect(container.querySelector('.comment-item.mine')).toBeNull();
    });

    it('a comment without an authorId (legacy author) is never mine', () => {
      const legacy = [{ id: 'T8', status: 'open', anchor: { exact: 'x' },
        comments: [{ id: 'C8', author: 'Old Name', body: 'legacy' }] }];
      const { container } = setup({ statusFilter: 'open', threads: legacy, currentUserId: 'u-alice' });
      expect(container.querySelector('.comment-item.mine')).toBeNull();
    });

    it('renders mention chips with the current name from mentionNames', () => {
      const withMention = [{ id: 'T7', status: 'open', anchor: { exact: 'x' },
        comments: [{ id: 'C7', author: 'Bob B', authorId: 'u-bob', body: 'cc @[Old Name](user:u-carol)' }] }];
      const { container } = setup({
        statusFilter: 'open', threads: withMention, mentionNames: { 'u-carol': 'Carol C' },
      });
      expect(container.querySelector('.comment-mention-chip').textContent).toBe('@Carol C');
    });
  });

  describe('@-mention autocomplete (signed-in only)', () => {
    afterEach(() => {
      vi.useRealTimers();
      vi.resetAllMocks();
    });

    // Types "hi @al" with the caret at the end into the reply box, then lets the
    // picker's 150 ms debounce elapse so any candidate fetch would have fired.
    async function typeMention() {
      vi.useFakeTimers();
      api.listMentionableUsers.mockResolvedValue({ users: [{ id: 'u-alice', displayName: 'Alice A' }] });
      const reply = screen.getByPlaceholderText(/reply/i);
      fireEvent.change(reply, { target: { value: 'hi @al', selectionStart: 6, selectionEnd: 6 } });
      await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    }

    it('queries the mentionable-users endpoint when the viewer is signed in', async () => {
      setup({ statusFilter: 'open', canMention: true });
      await typeMention();
      expect(api.listMentionableUsers).toHaveBeenCalledWith('al');
    });

    it('picking a candidate with Enter inserts the stored token, not a login name', async () => {
      setup({ statusFilter: 'open', canMention: true });
      await typeMention();
      const reply = screen.getByPlaceholderText(/reply/i);
      fireEvent.keyDown(reply, { key: 'Enter' });
      await act(async () => { await vi.advanceTimersByTimeAsync(50); });
      expect(reply.value).toBe('hi @[Alice A](user:u-alice) ');
    });

    it('never calls the mentionable-users endpoint for an anonymous viewer', async () => {
      // The endpoint answers 401 to anonymous callers (login names are email addresses
      // under SSO); a 401 would also fire the global wikantik:auth-required re-auth event.
      setup({ statusFilter: 'open' });
      await typeMention();
      expect(api.listMentionableUsers).not.toHaveBeenCalled();
    });
  });
});
