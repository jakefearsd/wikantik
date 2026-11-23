import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getInbox: vi.fn(), markAllRead: vi.fn(), getNotifyPrefs: vi.fn(), setNotifyPrefs: vi.fn(),
}));
vi.mock('../../hooks/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }) }));
vi.mock('../../hooks/useWorkUnread', () => ({ announceUnread: vi.fn() }));
import { announceUnread } from '../../hooks/useWorkUnread';
import { getInbox, markAllRead, getNotifyPrefs } from '../../api/work';
import WorkInbox from './WorkInbox';

const notice = (id, over = {}) => ({
  id, reason: 'assigned', createdAt: '2026-11-20T10:00:00Z', readAt: null, projectKey: 'PLAT', itemKey: `PLAT-${id}`,
  itemTitle: `Item ${id}`, event: { type: 'assigned', actorName: 'Ana Alvarez', toValue: 'Bob Brown', toValueName: 'Bob Brown' }, ...over,
});

function renderInbox() {
  return render(<MemoryRouter><WorkInbox /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  getNotifyPrefs.mockResolvedValue({ email: 'off', emailAvailable: false, webhooksAvailable: false });
});

describe('WorkInbox', () => {
  it('reads an actor-less status move without inventing an actor', async () => {
    getInbox.mockResolvedValue({
      notices: [notice(5, { reason: 'watching', event: { type: 'status', actor: null, actorName: null, actorKind: null, fromValue: 'in_progress', toValue: 'ready' } })],
      unread: 1,
    });
    renderInbox();
    expect(await screen.findByText('This moved to Ready')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Someone|The code host/);
  });

  it('listsNoticesWithReasonAndLink', async () => {
    getInbox.mockResolvedValue({
      notices: [notice(9), notice(8, { reason: 'mentioned', readAt: '2026-11-20T11:00:00Z' }),
        notice(7, { reason: 'webhook_disabled', itemKey: null, itemTitle: null,
          event: { type: 'settings', note: 'webhook', fromValue: 'hooks.example.com', toValue: 'failed', actorName: 'Ana Alvarez' } })],
      unread: 2,
    });
    renderInbox();
    const link = await screen.findByRole('link', { name: 'PLAT-9 Item 9' });
    expect(link).toHaveAttribute('href', '/work/item/PLAT-9');
    const first = link.closest('li');
    expect(within(first).getByText('Assigned')).toBeInTheDocument();
    expect(first).toHaveAttribute('data-unread', 'true');
    expect(within(first).getByText('Ana Alvarez assigned this to Bob Brown')).toBeInTheDocument();
    const second = screen.getByRole('link', { name: 'PLAT-8 Item 8' }).closest('li');
    expect(within(second).getByText('Mentioned')).toBeInTheDocument();
    expect(second).toHaveAttribute('data-unread', 'false');
    const third = screen.getByText('Webhook switched off').closest('li');
    expect(within(third).getByText('The webhook to hooks.example.com was switched off after failing for 24 hours.')).toBeInTheDocument();
    expect(screen.getByText('2 unread')).toBeInTheDocument();
  });

  it('markAllReadClearsTheBadge', async () => {
    getInbox.mockResolvedValue({ notices: [notice(2), notice(1)], unread: 2 });
    markAllRead.mockResolvedValue(undefined);
    renderInbox();
    fireEvent.click(await screen.findByRole('button', { name: 'Mark all read' }));
    expect(await screen.findByText('All read')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').every((li) => li.getAttribute('data-unread') === 'false')).toBe(true);
    expect(announceUnread).toHaveBeenCalledWith(0);
  });

  it('loadMoreAsksForOlder', async () => {
    const page = Array.from({ length: 30 }, (_, i) => notice(100 - i));
    getInbox.mockResolvedValueOnce({ notices: page, unread: 0 });
    getInbox.mockResolvedValueOnce({ notices: [notice(50)], unread: 3 });
    renderInbox();
    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(getInbox).toHaveBeenLastCalledWith({ before: 71, limit: 30 }));
    expect(await screen.findByRole('link', { name: 'PLAT-50 Item 50' })).toBeInTheDocument();
    expect(announceUnread).toHaveBeenCalledWith(3);
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('aFailedLoadMoreKeepsTheListAndSaysSo', async () => {
    const page = Array.from({ length: 30 }, (_, i) => notice(100 - i));
    getInbox.mockResolvedValueOnce({ notices: page, unread: 0 });
    getInbox.mockRejectedValueOnce(new Error('down'));
    renderInbox();
    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('down')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'PLAT-100 Item 100' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Load more' })).toBeInTheDocument();
  });

  it('aFailedFirstLoadShowsAnErrorWithRetry', async () => {
    getInbox.mockRejectedValueOnce(new Error('down'));
    renderInbox();
    getInbox.mockResolvedValue({ notices: [notice(1)], unread: 0 });
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('link', { name: 'PLAT-1 Item 1' })).toBeInTheDocument();
  });

  it('labels a reporter\'s request for a lead', async () => {
    getInbox.mockResolvedValue({ notices: [notice(4, { reason: 'requested' })], unread: 1 });
    renderInbox();
    const row = (await screen.findByRole('link', { name: 'PLAT-4 Item 4' })).closest('li');
    expect(within(row).getByText('Request')).toBeInTheDocument();
  });
});
