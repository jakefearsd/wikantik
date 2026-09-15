import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMyPages } from './useMyPages';
import { api } from '../api/client';

vi.mock('../api/client', () => ({ api: { getMyPages: vi.fn() } }));

beforeEach(() => vi.clearAllMocks());

describe('useMyPages', () => {
  it('loads pages when enabled', async () => {
    api.getMyPages.mockResolvedValue({ pages: [{ slug: 'Foo', title: 'Foo' }] });
    const { result } = renderHook(() => useMyPages({ enabled: true }));
    await waitFor(() => expect(result.current.pages).toHaveLength(1));
    expect(result.current.pages[0].slug).toBe('Foo');
  });

  it('does not fetch when disabled', () => {
    renderHook(() => useMyPages({ enabled: false }));
    expect(api.getMyPages).not.toHaveBeenCalled();
  });

  it('fails closed to an empty list on error', async () => {
    api.getMyPages.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useMyPages({ enabled: true }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.pages).toEqual([]);
  });

  it('clears pages and loading when toggled from enabled to disabled', async () => {
    api.getMyPages.mockResolvedValue({ pages: [{ slug: 'Foo', title: 'Foo' }] });
    const { result, rerender } = renderHook(({ enabled }) => useMyPages({ enabled }), {
      initialProps: { enabled: true },
    });
    await waitFor(() => expect(result.current.pages).toHaveLength(1));

    rerender({ enabled: false });

    expect(result.current.pages).toEqual([]);
    expect(result.current.loading).toBe(false);
  });

  it('fetches again when toggled from disabled to enabled', async () => {
    api.getMyPages.mockResolvedValue({ pages: [{ slug: 'Foo', title: 'Foo' }] });
    const { result, rerender } = renderHook(({ enabled }) => useMyPages({ enabled }), {
      initialProps: { enabled: false },
    });
    expect(api.getMyPages).not.toHaveBeenCalled();

    rerender({ enabled: true });

    await waitFor(() => expect(result.current.pages).toHaveLength(1));
    expect(api.getMyPages).toHaveBeenCalledTimes(1);
  });
});
