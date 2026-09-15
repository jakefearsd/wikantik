import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useApi, usePaginatedQuery } from './useApi';

describe('useApi', () => {
  it('calls the fetcher on mount and exposes the resolved data', async () => {
    const fetcher = vi.fn().mockResolvedValue({ hello: 'world' });
    const { result } = renderHook(() => useApi(fetcher, []));
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toEqual({ hello: 'world' });
    expect(result.current.error).toBeNull();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('seeds data from initialData immediately, ahead of the background refetch', () => {
    const fetcher = vi.fn().mockResolvedValue({ a: 1 });
    const { result } = renderHook(() =>
      useApi(fetcher, [], { initialData: { seeded: true } }));
    // Seeded content is visible even though the effect's background refresh
    // has already flipped `loading` true by the time effects have flushed.
    expect(result.current.data).toEqual({ seeded: true });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('captures a fetcher rejection as `error`', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useApi(fetcher, []));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error.message).toBe('boom');
    expect(result.current.data).toBeNull();
  });

  it('refetches when a dep changes', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok');
    let id = 1;
    const { result, rerender } = renderHook(() => useApi(fetcher, [id]));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    id = 2;
    rerender();
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(result.current).toBeTruthy();
  });

  it('reload() re-invokes the fetcher', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { result } = renderHook(() => useApi(fetcher, []));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await act(async () => { await result.current.reload(); });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('aborts the in-flight request on unmount', async () => {
    let capturedSignal = null;
    const fetcher = vi.fn((signal) => {
      capturedSignal = signal;
      return new Promise(() => {}); // never resolves
    });
    const { unmount } = renderHook(() => useApi(fetcher, []));
    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    unmount();
    expect(capturedSignal.aborted).toBe(true);
  });
});

describe('usePaginatedQuery', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('loads page 0 on mount', async () => {
    const fetcher = vi.fn().mockResolvedValue({ rows: [{ id: 1 }], total: 1 });
    const { result } = renderHook(() => usePaginatedQuery(fetcher, []));
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(fetcher).toHaveBeenCalledWith({ page: 0, pageSize: 50, search: '' });
    expect(result.current.rows).toEqual([{ id: 1 }]);
    expect(result.current.total).toBe(1);
    expect(result.current.firstLoad).toBe(false);
  });

  it('always uses the latest fetcher without re-triggering the fetch effect', async () => {
    const fetcherA = vi.fn().mockResolvedValue({ rows: ['a'], total: 1 });
    const fetcherB = vi.fn().mockResolvedValue({ rows: ['b'], total: 1 });
    const { result, rerender } = renderHook(
      ({ fetcher }) => usePaginatedQuery(fetcher, []),
      { initialProps: { fetcher: fetcherA } },
    );
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(fetcherA).toHaveBeenCalledTimes(1);

    // Swapping the fetcher identity alone (no page/search/deps change) must
    // not re-run the fetch effect — but a subsequent reload should use the
    // NEW fetcher, not a stale closure over the old one.
    rerender({ fetcher: fetcherB });
    expect(fetcherB).not.toHaveBeenCalled();

    await act(async () => { await result.current.reload(); });
    expect(fetcherB).toHaveBeenCalledTimes(1);
  });

  it('debounces `search` into the fetcher params after 300ms and resets to page 0', async () => {
    const fetcher = vi.fn().mockResolvedValue({ rows: [], total: 0 });
    const { result } = renderHook(() => usePaginatedQuery(fetcher, []));
    await act(async () => { await vi.runAllTimersAsync(); });
    fetcher.mockClear();

    act(() => { result.current.setPage(2); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.page).toBe(2);

    act(() => { result.current.setSearch('foo'); });
    await act(async () => { await vi.advanceTimersByTimeAsync(300); });
    // Page reset to 0 once the debounced search value changes.
    expect(result.current.page).toBe(0);
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(fetcher).toHaveBeenCalledWith({ page: 0, pageSize: 50, search: 'foo' });
  });

  it('resets page to 0 when an external dep changes', async () => {
    const fetcher = vi.fn().mockResolvedValue({ rows: [], total: 0 });
    const { result, rerender } = renderHook(
      ({ deps }) => usePaginatedQuery(fetcher, deps),
      { initialProps: { deps: ['typeA'] } },
    );
    await act(async () => { await vi.runAllTimersAsync(); });
    act(() => { result.current.setPage(3); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.page).toBe(3);

    rerender({ deps: ['typeB'] });
    expect(result.current.page).toBe(0);
  });

  it('does not reset page on an unrelated re-render with the same deps', async () => {
    const fetcher = vi.fn().mockResolvedValue({ rows: [], total: 0 });
    const { result, rerender } = renderHook(
      ({ deps }) => usePaginatedQuery(fetcher, deps),
      { initialProps: { deps: ['typeA'] } },
    );
    await act(async () => { await vi.runAllTimersAsync(); });
    act(() => { result.current.setPage(3); });
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.page).toBe(3);

    // Same deps values (new array instance) — must not clobber the page.
    rerender({ deps: ['typeA'] });
    expect(result.current.page).toBe(3);
  });

  it('captures a fetcher rejection as a string error and still clears firstLoad', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('nope'));
    const { result } = renderHook(() => usePaginatedQuery(fetcher, []));
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.error).toBe('nope');
    expect(result.current.firstLoad).toBe(false);
  });
});
