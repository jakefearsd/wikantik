import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMyBlog } from './useMyBlog';
import { api } from '../api/client';

vi.mock('../api/client', () => ({ api: { blog: { listEntries: vi.fn() } } }));

beforeEach(() => vi.clearAllMocks());

describe('useMyBlog', () => {
  it('loads entries for the login when enabled', async () => {
    api.blog.listEntries.mockResolvedValue({ entries: [{ name: 'E1', title: 'Entry 1' }] });
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: true }));
    await waitFor(() => expect(result.current.entries).toHaveLength(1));
    expect(api.blog.listEntries).toHaveBeenCalledWith('alice');
  });

  it('does not fetch when disabled', () => {
    renderHook(() => useMyBlog({ login: 'alice', enabled: false }));
    expect(api.blog.listEntries).not.toHaveBeenCalled();
  });

  it('fails closed on error', async () => {
    api.blog.listEntries.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: true }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.entries).toEqual([]);
  });
});
