import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../api/client', () => ({
  api: { blog: { createEntry: vi.fn() } },
}));

import NewBlogEntry from './NewBlogEntry';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: [] } });
  api.blog.createEntry.mockResolvedValue({ success: true, name: '20260529MyFirstPost' });
});

function renderNew(username = 'alice') {
  return render(
    <MemoryRouter initialEntries={[`/blog/${username}/new`]}>
      <Routes>
        <Route path="/blog/:username/new" element={<NewBlogEntry />} />
        <Route path="/blog/:username/:entryName" element={<div>ENTRY VIEW PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('NewBlogEntry', () => {
  it('blocks a non-owner', () => {
    useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'bob', roles: [] } });
    renderNew('alice');
    expect(screen.getByText(/not authorized to create entries/i)).toBeInTheDocument();
  });

  it('rejects a blank/punctuation-only topic before calling the API', async () => {
    renderNew();
    fireEvent.change(screen.getByPlaceholderText(/Enter blog post title/i), { target: { value: '!!!' } });
    fireEvent.click(screen.getByRole('button', { name: /Create Entry/i }));
    await screen.findByText(/must contain at least one letter or digit/i);
    expect(api.blog.createEntry).not.toHaveBeenCalled();
  });

  it('slugifies the title, creates the entry, and navigates to it', async () => {
    renderNew();
    fireEvent.change(screen.getByPlaceholderText(/Enter blog post title/i), { target: { value: 'My First Post' } });
    fireEvent.change(screen.getByPlaceholderText(/Write your blog post/i), { target: { value: 'Body text' } });
    fireEvent.click(screen.getByRole('button', { name: /Create Entry/i }));

    await waitFor(() => expect(api.blog.createEntry).toHaveBeenCalledWith('alice', 'MyFirstPost', 'Body text'));
    await screen.findByText('ENTRY VIEW PAGE');
  });

  it('surfaces a server error without navigating', async () => {
    api.blog.createEntry.mockRejectedValue(Object.assign(new Error('x'), { body: { message: 'Boom' } }));
    renderNew();
    fireEvent.change(screen.getByPlaceholderText(/Enter blog post title/i), { target: { value: 'Topic' } });
    fireEvent.click(screen.getByRole('button', { name: /Create Entry/i }));
    await screen.findByText('Boom');
    expect(screen.queryByText('ENTRY VIEW PAGE')).toBeNull();
  });
});
