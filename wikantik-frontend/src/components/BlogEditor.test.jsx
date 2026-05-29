import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../api/client', () => ({
  api: {
    blog: { get: vi.fn(), getEntry: vi.fn(), update: vi.fn(), updateEntry: vi.fn() },
    listAttachments: vi.fn(),
  },
}));

import BlogEditor from './BlogEditor';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'alice', roles: [] } });
  api.listAttachments.mockResolvedValue({ attachments: [] });
  api.blog.getEntry.mockResolvedValue({ metadata: { title: 'My Post' }, content: 'Body text', version: 3 });
  api.blog.get.mockResolvedValue({ metadata: { title: "Alice's Blog" }, content: 'Home body', version: 1 });
  api.blog.update.mockResolvedValue({ success: true });
  api.blog.updateEntry.mockResolvedValue({ success: true });
});

function renderEditor(username, pageName) {
  return render(
    <MemoryRouter initialEntries={[`/edit/blog/${username}/${pageName}`]}>
      <Routes>
        <Route path="/edit/blog/:username/:pageName" element={<BlogEditor />} />
        <Route path="/blog/:username/:pageName" element={<div>BLOG VIEW PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

// The editor renders both a "Change note" <input> and the main <textarea>, both
// role=textbox — wait for loading to finish, then grab the textarea by class.
async function findTextarea(container) {
  await waitFor(() => expect(container.querySelector('textarea.editor-textarea')).not.toBeNull());
  return container.querySelector('textarea.editor-textarea');
}

describe('BlogEditor', () => {
  it('loads an entry, reconstructs its content, and saves via updateEntry', async () => {
    const { container } = renderEditor('alice', '20260529MyPost');
    const textarea = await findTextarea(container);
    expect(textarea.value).toContain('title: My Post');
    expect(textarea.value).toContain('Body text');

    fireEvent.click(screen.getByRole('button', { name: /^Save$/i }));
    await waitFor(() => expect(api.blog.updateEntry).toHaveBeenCalledWith(
      'alice', '20260529MyPost', expect.stringContaining('Body text')));
    await screen.findByText('BLOG VIEW PAGE');
    expect(api.blog.update).not.toHaveBeenCalled();
  });

  it('saves the blog home via update when editing Blog', async () => {
    const { container } = renderEditor('alice', 'Blog');
    await findTextarea(container);
    expect(api.blog.get).toHaveBeenCalledWith('alice');

    fireEvent.click(screen.getByRole('button', { name: /^Save$/i }));
    await waitFor(() => expect(api.blog.update).toHaveBeenCalledWith(
      'alice', expect.stringContaining('Home body')));
    await screen.findByText('BLOG VIEW PAGE');
    expect(api.blog.updateEntry).not.toHaveBeenCalled();
  });

  it('surfaces a save error without navigating', async () => {
    api.blog.updateEntry.mockRejectedValue(new Error('Save blew up'));
    const { container } = renderEditor('alice', '20260529MyPost');
    await findTextarea(container);
    fireEvent.click(screen.getByRole('button', { name: /^Save$/i }));
    await screen.findByText('Save blew up');
    expect(screen.queryByText('BLOG VIEW PAGE')).toBeNull();
  });
});
