import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../api/client', () => ({
  api: {
    listMyMentions: vi.fn(),
    markMentionRead: vi.fn(),
    markAllMentionsRead: vi.fn(),
  },
}));

import MentionsPage from './MentionsPage';
import { api } from '../api/client';

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
    await waitFor(() => expect(screen.getByText(/hi @alice please/)).toBeTruthy());
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
    await waitFor(() => expect(screen.getByText(/No mentions/)).toBeTruthy());
  });

  it('view-in-context link goes to /wiki/<page>?thread=...&comment=...', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText(/hi @alice please/)).toBeTruthy());
    const links = screen.getAllByRole('link');
    const fooLink = links.find((l) => l.textContent === 'Foo');
    expect(fooLink.getAttribute('href')).toContain('/wiki/Foo?thread=t1&comment=c1');
  });
});
