import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../utils/math', () => ({ renderMath: vi.fn() }));
vi.mock('../api/client', () => ({
  api: { blog: { get: vi.fn(), remove: vi.fn() } },
}));

import BlogHome from './BlogHome';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

const PAGE = { contentHtml: '<p>Hello blog world</p>', content: '' };

beforeEach(() => {
  vi.clearAllMocks();
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: [] } });
  api.blog.get.mockResolvedValue(PAGE);
  api.blog.remove.mockResolvedValue({});
});

function renderBlogHome(username = 'alice') {
  return render(
    <MemoryRouter initialEntries={[`/blog/${username}`]}>
      <Routes>
        <Route path="/blog" element={<div>BLOG LIST PAGE</div>} />
        <Route path="/blog/:username" element={<BlogHome />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('BlogHome', () => {
  it('renders the rendered blog HTML after loading', async () => {
    renderBlogHome();
    expect(screen.getByText(/Loading/i)).toBeInTheDocument();
    await screen.findByText('Hello blog world');
    expect(api.blog.get).toHaveBeenCalledWith('alice', expect.objectContaining({ render: true }));
  });

  it('shows a not-found message on 404', async () => {
    api.blog.get.mockRejectedValue(Object.assign(new Error('Not Found'), { status: 404 }));
    renderBlogHome('nobody');
    await screen.findByText(/Blog not found/i);
  });

  it('shows owner controls for the blog owner', async () => {
    renderBlogHome();
    await screen.findByText('Hello blog world');
    expect(screen.getByRole('link', { name: /New Entry/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Edit Blog Page/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete Blog' })).toBeInTheDocument();
  });

  it('hides owner controls for a non-owner', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'bob', roles: [] } });
    renderBlogHome('alice');
    await screen.findByText('Hello blog world');
    expect(screen.queryByRole('link', { name: /New Entry/i })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete Blog' })).toBeNull();
  });

  it('two-step delete confirm calls api.blog.remove then navigates to the blog list', async () => {
    renderBlogHome();
    await screen.findByText('Hello blog world');

    fireEvent.click(screen.getByRole('button', { name: 'Delete Blog' }));
    expect(api.blog.remove).not.toHaveBeenCalled();

    const heading = screen.getByRole('heading', { name: 'Delete Blog' });
    const modal = heading.closest('.modal-content');
    fireEvent.click(within(modal).getByRole('button', { name: 'Delete Blog' }));

    await waitFor(() => expect(api.blog.remove).toHaveBeenCalledWith('alice'));
    await screen.findByText('BLOG LIST PAGE');
  });

  it('surfaces a delete error without navigating', async () => {
    api.blog.remove.mockRejectedValue(Object.assign(new Error('nope'), { body: { message: 'Forbidden' } }));
    renderBlogHome();
    await screen.findByText('Hello blog world');

    fireEvent.click(screen.getByRole('button', { name: 'Delete Blog' }));
    const modal = screen.getByRole('heading', { name: 'Delete Blog' }).closest('.modal-content');
    fireEvent.click(within(modal).getByRole('button', { name: 'Delete Blog' }));

    await screen.findByText('Forbidden');
    expect(screen.queryByText('BLOG LIST PAGE')).toBeNull();
  });
});
