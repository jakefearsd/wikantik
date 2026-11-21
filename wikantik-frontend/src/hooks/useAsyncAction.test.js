import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import useAsyncAction from './useAsyncAction';

describe('useAsyncAction', () => {
  afterEach(() => vi.restoreAllMocks());

  it('success: passes args, calls onSuccess with the result, clears busy', async () => {
    const fn = vi.fn().mockResolvedValue('ok');
    const onSuccess = vi.fn();
    const { result } = renderHook(() => useAsyncAction(fn, { onSuccess }));
    expect(result.current.busy).toBe(false);
    let ret;
    await act(async () => { ret = await result.current.run(1, 2); });
    expect(fn).toHaveBeenCalledWith(1, 2);
    expect(onSuccess).toHaveBeenCalledWith('ok');
    expect(ret).toBe('ok');
    expect(result.current.busy).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('stays busy until a promise returned by onSuccess settles', async () => {
    let finish;
    const fn = vi.fn().mockResolvedValue(1);
    const onSuccess = vi.fn(() => new Promise((r) => { finish = r; }));
    const { result } = renderHook(() => useAsyncAction(fn, { onSuccess }));
    act(() => { result.current.run(); });
    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    expect(result.current.busy).toBe(true);
    await act(async () => { finish(); });
    await waitFor(() => expect(result.current.busy).toBe(false));
  });

  it('an onSuccess that throws is reported as the error', async () => {
    const fn = vi.fn().mockResolvedValue(1);
    const { result } = renderHook(() => useAsyncAction(fn, { onSuccess: () => { throw new Error('after'); } }));
    await act(async () => { await result.current.run(); });
    expect(result.current.error).toBe('after');
  });

  it('rejection sets the error message and returns undefined', async () => {
    const fn = vi.fn().mockRejectedValue(new Error('boom'));
    const onSuccess = vi.fn();
    const { result } = renderHook(() => useAsyncAction(fn, { fallbackMessage: 'fb', onSuccess }));
    let ret = 'x';
    await act(async () => { ret = await result.current.run(); });
    expect(ret).toBeUndefined();
    expect(result.current.error).toBe('boom');
    expect(result.current.busy).toBe(false);
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('falls back to fallbackMessage when the error has no message', async () => {
    const fn = vi.fn().mockRejectedValue(new Error(''));
    const { result } = renderHook(() => useAsyncAction(fn, { fallbackMessage: 'Failed to save' }));
    await act(async () => { await result.current.run(); });
    expect(result.current.error).toBe('Failed to save');
  });

  it('ignores a second run while busy', async () => {
    let resolve;
    const fn = vi.fn(() => new Promise((r) => { resolve = r; }));
    const { result } = renderHook(() => useAsyncAction(fn));
    act(() => { result.current.run(); });
    expect(result.current.busy).toBe(true);
    act(() => { result.current.run(); });
    expect(fn).toHaveBeenCalledTimes(1);
    await act(async () => { resolve(1); });
    await waitFor(() => expect(result.current.busy).toBe(false));
  });

  it('clears a previous error when run again', async () => {
    const fn = vi.fn().mockRejectedValueOnce(new Error('first')).mockResolvedValueOnce(1);
    const { result } = renderHook(() => useAsyncAction(fn));
    await act(async () => { await result.current.run(); });
    expect(result.current.error).toBe('first');
    await act(async () => { await result.current.run(); });
    expect(result.current.error).toBeNull();
  });

  it('calls no onSuccess after unmount', async () => {
    let resolve;
    const fn = vi.fn(() => new Promise((r) => { resolve = r; }));
    const onSuccess = vi.fn();
    const { result, unmount } = renderHook(() => useAsyncAction(fn, { onSuccess }));
    act(() => { result.current.run(); });
    unmount();
    await act(async () => { resolve(1); });
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('reset clears the error; setError sets it', async () => {
    const fn = vi.fn().mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useAsyncAction(fn));
    await act(async () => { await result.current.run(); });
    act(() => result.current.reset());
    expect(result.current.error).toBeNull();
    act(() => result.current.setError('manual'));
    expect(result.current.error).toBe('manual');
  });
});
