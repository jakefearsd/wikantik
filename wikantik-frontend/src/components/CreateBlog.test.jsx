import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../api/client', () => ({
  api: { blog: { create: vi.fn() } },
}));

import CreateBlog from './CreateBlog';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice' } });
  api.blog.create.mockResolvedValue({ success: true, username: 'alice' });
});

function renderCreate() {
  return render(
    <MemoryRouter initialEntries={['/blog/create']}>
      <Routes>
        <Route path="/blog/create" element={<CreateBlog />} />
        <Route path="/blog/:username/Blog" element={<div>BLOG HOME PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CreateBlog', () => {
  it('prompts to log in when anonymous', () => {
    useAuth.mockReturnValue({ user: { authenticated: false } });
    renderCreate();
    expect(screen.getByText(/must be logged in to create a blog/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create Blog/i })).toBeNull();
  });

  it('creates the blog and navigates to the new blog home', async () => {
    renderCreate();
    fireEvent.click(screen.getByRole('button', { name: /Create Blog/i }));
    await waitFor(() => expect(api.blog.create).toHaveBeenCalled());
    await screen.findByText('BLOG HOME PAGE');
  });

  it('shows an error and stays put when creation fails', async () => {
    api.blog.create.mockRejectedValue(Object.assign(new Error('x'), { body: { message: 'Blog already exists' } }));
    renderCreate();
    fireEvent.click(screen.getByRole('button', { name: /Create Blog/i }));
    await screen.findByText('Blog already exists');
    expect(screen.queryByText('BLOG HOME PAGE')).toBeNull();
  });
});
