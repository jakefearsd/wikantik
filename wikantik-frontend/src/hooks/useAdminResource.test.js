import { describe, it, expect, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { useAdminResource } from './useAdminResource';

describe('useAdminResource', () => {
  it('starts loading, then exposes the fetched data', async () => {
    const fetcher = vi.fn().mockResolvedValue(['a']);
    const { result } = renderHook(() => useAdminResource(fetcher));
    expect(result.current.loading).toBe(true);
    expect(result.current.data).toBeNull();
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toEqual(['a']);
    expect(result.current.error).toBeNull();
  });

  it('reports a failure as the error message string', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useAdminResource(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe('boom');
    expect(result.current.data).toBeNull();
  });

  it('an error with an empty message is an empty string, so no banner shows (as the hand-rolled loaders did)', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error(''));
    const { result } = renderHook(() => useAdminResource(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe('');
  });

  it('a thrown value with no message is reported as its string form', async () => {
    const fetcher = vi.fn().mockRejectedValue('offline');
    const { result } = renderHook(() => useAdminResource(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe('offline');
  });

  it('reload refreshes the data without ever setting loading again', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(1).mockResolvedValueOnce(2);
    const { result } = renderHook(() => useAdminResource(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    const seen = [];
    let reloading;
    act(() => { reloading = result.current.reload(); });
    seen.push(result.current.loading);
    await act(async () => { await reloading; });
    seen.push(result.current.loading);
    expect(seen).toEqual([false, false]);
    expect(result.current.data).toBe(2);
  });

  it('a successful reload clears an earlier error', async () => {
    const fetcher = vi.fn().mockRejectedValueOnce(new Error('x')).mockResolvedValueOnce('ok');
    const { result } = renderHook(() => useAdminResource(fetcher));
    await waitFor(() => expect(result.current.error).toBe('x'));
    await act(async () => { await result.current.reload(); });
    expect(result.current.error).toBeNull();
    expect(result.current.data).toBe('ok');
  });

  it('setData replaces the data locally', async () => {
    const { result } = renderHook(() => useAdminResource(() => Promise.resolve(1)));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.setData(9));
    expect(result.current.data).toBe(9);
  });

  it('aborts the in-flight request on unmount', async () => {
    let signal;
    const fetcher = vi.fn((s) => { signal = s; return new Promise(() => {}); });
    const { unmount } = renderHook(() => useAdminResource(fetcher));
    expect(signal.aborted).toBe(false);
    unmount();
    expect(signal.aborted).toBe(true);
  });

  it('aborts the previous request when a new fetch starts', async () => {
    const signals = [];
    const fetcher = vi.fn((s) => { signals.push(s); return new Promise(() => {}); });
    const { result } = renderHook(() => useAdminResource(fetcher));
    act(() => { result.current.reload(); });
    expect(signals[0].aborted).toBe(true);
    expect(signals[1].aborted).toBe(false);
  });

  it('refetches when deps change', async () => {
    const fetcher = vi.fn(() => Promise.resolve('r'));
    const { rerender } = renderHook(({ d }) => useAdminResource(fetcher, [d]), { initialProps: { d: 1 } });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    rerender({ d: 2 });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });

  it('a stale aborted response never overwrites newer data or error', async () => {
    const pending = [];
    const fetcher = vi.fn(() => new Promise((resolve, reject) => pending.push({ resolve, reject })));
    const { result } = renderHook(() => useAdminResource(fetcher));
    act(() => { result.current.reload(); });
    await act(async () => { pending[1].resolve('new'); });
    await act(async () => { pending[0].resolve('old'); });
    expect(result.current.data).toBe('new');
    await act(async () => { result.current.reload(); });
    await act(async () => { pending[2].resolve('newest'); });
    await act(async () => { pending[1].reject(new Error('late')); });
    expect(result.current.error).toBeNull();
    expect(result.current.data).toBe('newest');
  });

  describe('refetchShowsLoading', () => {
    it('a deps change sets loading and clears error; reload stays silent', async () => {
      const resolvers = [];
      const fetcher = vi.fn(() => new Promise((resolve, reject) => resolvers.push({ resolve, reject })));
      const { result, rerender } = renderHook(
        ({ d }) => useAdminResource(fetcher, [d], { refetchShowsLoading: true }),
        { initialProps: { d: 1 } });
      await act(async () => { resolvers[0].reject(new Error('bad')); });
      expect(result.current.error).toBe('bad');
      expect(result.current.loading).toBe(false);
      rerender({ d: 2 });
      expect(result.current.loading).toBe(true);
      expect(result.current.error).toBeNull();
      await act(async () => { resolvers[1].resolve('ok'); });
      expect(result.current.loading).toBe(false);
      act(() => { result.current.reload(); });
      expect(result.current.loading).toBe(false);
    });

    it('does not show loading on a deps change by default', async () => {
      const fetcher = vi.fn(() => Promise.resolve('r'));
      const { result, rerender } = renderHook(({ d }) => useAdminResource(fetcher, [d]), { initialProps: { d: 1 } });
      await waitFor(() => expect(result.current.loading).toBe(false));
      rerender({ d: 2 });
      expect(result.current.loading).toBe(false);
    });
  });
});
