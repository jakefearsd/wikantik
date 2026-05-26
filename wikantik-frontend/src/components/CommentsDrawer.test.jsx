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
    onReopen: vi.fn(), onFocusThread: vi.fn(), onClose: vi.fn(), ...extra,
  };
  render(<CommentsDrawer {...props} />);
  return props;
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
});
