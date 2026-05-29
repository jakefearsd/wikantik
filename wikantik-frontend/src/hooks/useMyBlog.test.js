import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMyBlog } from './useMyBlog';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: { blog: { listEntries: vi.fn() } },
}));

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useMyBlog', () => {
  it('loads entries from the bare array the endpoint returns', async () => {
    // GET /api/blog/{login}/entries returns a bare JSON array, not { entries: [...] }.
    // Reading d.entries (the old behaviour) left this perpetually empty.
    api.blog.listEntries.mockResolvedValue([
      { name: '20260529First', title: 'First' },
      { name: '20260528Second', title: 'Second' },
    ]);
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: true }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.entries.map((e) => e.name)).toEqual(['20260529First', '20260528Second']);
    expect(api.blog.listEntries).toHaveBeenCalledWith('alice');
  });

  it('still tolerates a { entries: [...] } envelope', async () => {
    api.blog.listEntries.mockResolvedValue({ entries: [{ name: 'X', title: 'X' }] });
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: true }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.entries.map((e) => e.name)).toEqual(['X']);
  });

  it('does not fetch when disabled', () => {
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: false }));
    expect(api.blog.listEntries).not.toHaveBeenCalled();
    expect(result.current.entries).toEqual([]);
    expect(result.current.loading).toBe(false);
  });

  it('does not fetch when login is missing', () => {
    const { result } = renderHook(() => useMyBlog({ login: null, enabled: true }));
    expect(api.blog.listEntries).not.toHaveBeenCalled();
    expect(result.current.entries).toEqual([]);
  });

  it('fails closed to an empty list on error', async () => {
    api.blog.listEntries.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useMyBlog({ login: 'alice', enabled: true }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.entries).toEqual([]);
  });
});
