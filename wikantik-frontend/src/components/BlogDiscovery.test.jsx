import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../api/client', () => ({
  api: { blog: { list: vi.fn() } },
}));

import BlogDiscovery from './BlogDiscovery';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: false } });
  api.blog.list.mockResolvedValue([]);
});

function renderDiscovery() {
  return render(<MemoryRouter><BlogDiscovery /></MemoryRouter>);
}

describe('BlogDiscovery', () => {
  it('shows the empty-state message when there are no blogs', async () => {
    renderDiscovery();
    await screen.findByText(/No blogs yet/i);
  });

  it('lists blogs from the bare array the endpoint returns', async () => {
    api.blog.list.mockResolvedValue([
      { username: 'alice', title: "Alice's Notes", authorFullName: 'Alice A', entryCount: 2 },
      { username: 'bob', title: "Bob's Blog", authorFullName: 'Bob B', entryCount: 1 },
    ]);
    renderDiscovery();
    await screen.findByText("Alice's Notes");
    expect(screen.getByText("Bob's Blog")).toBeInTheDocument();
    expect(screen.getByText(/by Alice A/)).toBeInTheDocument();
    expect(screen.getByText('2 entries')).toBeInTheDocument();
    expect(screen.getByText('1 entry')).toBeInTheDocument();
  });

  it('hides the create-blog action for anonymous users', async () => {
    renderDiscovery();
    await screen.findByText(/No blogs yet/i);
    expect(screen.queryByRole('link', { name: /Create My Blog/i })).toBeNull();
  });

  it('shows the create-blog action when authenticated', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice' } });
    renderDiscovery();
    await screen.findByText(/No blogs yet/i);
    expect(screen.getByRole('link', { name: /Create My Blog/i })).toBeInTheDocument();
  });
});
