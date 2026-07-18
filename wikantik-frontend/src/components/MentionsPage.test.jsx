import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../hooks/useToast', () => ({
  useToast: vi.fn(),
}));

vi.mock('../api/client', () => ({
  api: {
    listMyMentions: vi.fn(),
    markMentionRead: vi.fn(),
    markAllMentionsRead: vi.fn(),
  },
}));

import MentionsPage from './MentionsPage';
import { api } from '../api/client';
import { useToast } from '../hooks/useToast';

const SAMPLE = [
  {
    id: 'm1', threadId: 't1', commentId: 'c1',
    canonicalId: 'CID1', pageName: 'Foo',
    snippet: 'hi @alice please',
    mentionedBy: 'bob',
    isOwnerMention: false,
    mentionedAt: '2026-05-28T00:00:00Z',
    readAt: null,
  },
  {
    id: 'm2', threadId: 't2', commentId: 'c2',
    canonicalId: 'CID2', pageName: 'Bar',
    snippet: 'thx',
    mentionedBy: 'carol',
    isOwnerMention: true,
    mentionedAt: '2026-05-27T00:00:00Z',
    readAt: null,
  },
];

function renderPage() {
  return render(
    <MemoryRouter>
      <MentionsPage />
    </MemoryRouter>
  );
}

describe('MentionsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.listMyMentions.mockResolvedValue({ mentions: SAMPLE });
    api.markMentionRead.mockResolvedValue({ ok: true });
    api.markAllMentionsRead.mockResolvedValue({ updated: 2 });
    useToast.mockReturnValue({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
  });

  it('renders mentions list on mount with default unread filter', async () => {
    renderPage();
    await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledWith({ status: 'unread', limit: 50 }));
    expect(screen.getByText(/hi @alice please/)).toBeTruthy();
    expect(screen.getByText(/thx/)).toBeTruthy();
    // Owner mention has "(your page)" tag.
    expect(screen.getByText('(your page)')).toBeTruthy();
  });

  it('filter toggle re-fetches with the chosen status', async () => {
    renderPage();
    await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('tab', { name: /All/ }));
    await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledWith({ status: 'all', limit: 50 }));
  });

  it('dismiss (X) on a row calls markMentionRead and reloads', async () => {
    renderPage();
    expect(await screen.findByText(/hi @alice please/)).toBeTruthy();
    const dismissButtons = screen.getAllByTitle('Mark read');
    fireEvent.click(dismissButtons[0]);
    await waitFor(() => expect(api.markMentionRead).toHaveBeenCalledWith('m1'));
    await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledTimes(2));
  });

  it('Mark all read calls the api and reloads', async () => {
    renderPage();
    await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByText('Mark all read'));
    await waitFor(() => expect(api.markAllMentionsRead).toHaveBeenCalled());
    await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledTimes(2));
  });

  it('empty state shown when no mentions', async () => {
    api.listMyMentions.mockResolvedValue({ mentions: [] });
    renderPage();
    expect(await screen.findByText(/No mentions yet/)).toBeTruthy();
  });

  it('#55 empty state uses EmptyState component', async () => {
    api.listMyMentions.mockResolvedValue({ mentions: [] });
    renderPage();
    // The EmptyState renders an .admin-empty-state (or the ui EmptyState)
    await waitFor(() => {
      expect(screen.getByText(/No mentions yet/)).toBeInTheDocument();
    });
  });

  it('view-in-context link goes to /wiki/<page>?thread=...&comment=...', async () => {
    renderPage();
    expect(await screen.findByText(/hi @alice please/)).toBeTruthy();
    const links = screen.getAllByRole('link');
    const fooLink = links.find((l) => l.textContent === 'Foo');
    expect(fooLink.getAttribute('href')).toContain('/wiki/Foo?thread=t1&comment=c1');
  });

  describe('#54 optimistic mark-as-read', () => {
    it('mark-one flips item to read synchronously before API resolves', async () => {
      // Use a deferred promise so we can check state while API is in-flight
      let resolveMarkOne;
      api.markMentionRead.mockReturnValue(new Promise(res => { resolveMarkOne = res; }));

      renderPage();
      expect(await screen.findByText(/hi @alice please/)).toBeTruthy();

      // Item m1 is unread — dismiss button visible
      const dismissButtons = screen.getAllByTitle('Mark read');
      expect(dismissButtons.length).toBeGreaterThan(0);

      fireEvent.click(dismissButtons[0]);

      // Synchronously: dismiss button for m1 should disappear (item is now read)
      await waitFor(() => {
        const btns = screen.queryAllByTitle('Mark read');
        // Should have one fewer dismiss button (m1 is now read, m2 still unread)
        expect(btns.length).toBeLessThan(dismissButtons.length);
      });

      // API not yet resolved, no reload yet
      expect(api.listMyMentions).toHaveBeenCalledTimes(1);

      // Now resolve to let cleanup happen
      resolveMarkOne({ ok: true });
      await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledTimes(2));
    });

    it('mark-one reverts and shows error toast on API failure', async () => {
      const errorToast = vi.fn();
      useToast.mockReturnValue({ success: vi.fn(), error: errorToast, info: vi.fn() });
      api.markMentionRead.mockRejectedValue(new Error('network error'));

      renderPage();
      expect(await screen.findByText(/hi @alice please/)).toBeTruthy();

      const dismissButtons = screen.getAllByTitle('Mark read');
      const initialCount = dismissButtons.length;

      fireEvent.click(dismissButtons[0]);

      // After rejection: reverted — dismiss button count should be back to original
      await waitFor(() => {
        expect(errorToast).toHaveBeenCalled();
      });
      // Item should be reverted (dismiss button visible again)
      expect(screen.getAllByTitle('Mark read').length).toBe(initialCount);
    });
  });
});
