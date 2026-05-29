import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../utils/math', () => ({ renderMath: vi.fn() }));
vi.mock('../api/client', () => ({
  api: { blog: { getEntry: vi.fn(), deleteEntry: vi.fn() } },
}));

import BlogEntry from './BlogEntry';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

const ENTRY = {
  name: '20260529MyPost',
  title: 'My Post',
  date: '2026-05-29',
  contentHtml: '<p>Entry body here</p>',
  content: '',
};

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: [] } });
  api.blog.getEntry.mockResolvedValue(ENTRY);
  api.blog.deleteEntry.mockResolvedValue({});
});

function renderEntry(username = 'alice', entryName = '20260529MyPost') {
  return render(
    <MemoryRouter initialEntries={[`/blog/${username}/${entryName}`]}>
      <Routes>
        <Route path="/blog/:username/Blog" element={<div>BLOG HOME PAGE</div>} />
        <Route path="/blog/:username/:entryName" element={<BlogEntry />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('BlogEntry', () => {
  it('renders the human title (not the slug) and the body', async () => {
    renderEntry();
    await screen.findByText('Entry body here');
    expect(screen.getByRole('heading', { name: 'My Post' })).toBeInTheDocument();
    expect(api.blog.getEntry).toHaveBeenCalledWith('alice', '20260529MyPost', expect.objectContaining({ render: true }));
  });

  it('falls back to the slug when no title is provided', async () => {
    api.blog.getEntry.mockResolvedValue({ ...ENTRY, title: undefined });
    renderEntry();
    await screen.findByText('Entry body here');
    expect(screen.getByRole('heading', { name: '20260529MyPost' })).toBeInTheDocument();
  });

  it('shows a not-found message on 404', async () => {
    api.blog.getEntry.mockRejectedValue(Object.assign(new Error('Not Found'), { status: 404 }));
    renderEntry();
    await screen.findByText(/Entry not found/i);
  });

  it('hides owner controls for a non-owner', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'bob', roles: [] } });
    renderEntry('alice');
    await screen.findByText('Entry body here');
    expect(screen.queryByRole('link', { name: /Edit Entry/i })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });

  it('two-step delete confirm calls api.blog.deleteEntry then navigates back to the blog', async () => {
    renderEntry();
    await screen.findByText('Entry body here');

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(api.blog.deleteEntry).not.toHaveBeenCalled();

    const modal = screen.getByRole('heading', { name: 'Delete Entry' }).closest('.modal-content');
    fireEvent.click(within(modal).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(api.blog.deleteEntry).toHaveBeenCalledWith('alice', '20260529MyPost'));
    await screen.findByText('BLOG HOME PAGE');
  });
});
