import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../api/client', () => ({ api: { scanMentions: vi.fn() } }));
import { api } from '../api/client';
import { useUnlinkedMentions } from './useUnlinkedMentions';

const flush = async () => { await act(async () => { await Promise.resolve(); await Promise.resolve(); }); };

beforeEach(() => {
  vi.useFakeTimers();
  api.scanMentions.mockReset();
  api.scanMentions.mockResolvedValue({ mentions: [{ target: 'T', phrase: 'p' }] });
});
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

describe('useUnlinkedMentions', () => {
  it('makes no request while disabled', () => {
    renderHook(() => useUnlinkedMentions({ page: 'P', text: 'abc', enabled: false }));
    act(() => { vi.advanceTimersByTime(10000); });
    expect(api.scanMentions).not.toHaveBeenCalled();
  });

  it('requests 1500 ms after the last change, not before', async () => {
    const { result, rerender } = renderHook((p) => useUnlinkedMentions(p),
      { initialProps: { page: 'P', text: 'a', enabled: true } });
    act(() => { vi.advanceTimersByTime(1000); });
    rerender({ page: 'P', text: 'ab', enabled: true });
    act(() => { vi.advanceTimersByTime(1499); });
    expect(api.scanMentions).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1); });
    expect(api.scanMentions).toHaveBeenCalledTimes(1);
    expect(api.scanMentions.mock.calls[0][0]).toMatchObject({ page: 'P', text: 'ab' });
    await flush();
    expect(result.current.status).toBe('ok');
    expect(result.current.mentions).toHaveLength(1);
  });

  it('sends LF-normalised text so offsets match the editor document', () => {
    renderHook(() => useUnlinkedMentions({ page: 'P', text: 'a\r\nb\rc\nd', enabled: true }));
    act(() => { vi.advanceTimersByTime(1500); });
    expect(api.scanMentions.mock.calls[0][0].text).toBe('a\nb\nc\nd');
  });

  it('aborts a superseded in-flight scan', () => {
    api.scanMentions.mockReturnValue(new Promise(() => {}));
    const { rerender } = renderHook((p) => useUnlinkedMentions(p),
      { initialProps: { page: 'P', text: 'a', enabled: true } });
    act(() => { vi.advanceTimersByTime(1500); });
    const first = api.scanMentions.mock.calls[0][0].signal;
    expect(first.aborted).toBe(false);
    rerender({ page: 'P', text: 'ab', enabled: true });
    expect(first.aborted).toBe(true);
  });

  it('503 means warming and retries after 5000 ms', async () => {
    api.scanMentions.mockRejectedValueOnce(Object.assign(new Error('warming'), { status: 503 }));
    const { result } = renderHook(() => useUnlinkedMentions({ page: 'P', text: 'a', enabled: true }));
    act(() => { vi.advanceTimersByTime(1500); });
    await flush();
    expect(result.current.status).toBe('warming');
    act(() => { vi.advanceTimersByTime(4999); });
    expect(api.scanMentions).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(1); });
    act(() => { vi.advanceTimersByTime(0); });
    expect(api.scanMentions).toHaveBeenCalledTimes(2);
    await flush();
    expect(result.current.status).toBe('ok');
  });

  it('other errors set error status and warn', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.scanMentions.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    const { result } = renderHook(() => useUnlinkedMentions({ page: 'P', text: 'a', enabled: true }));
    act(() => { vi.advanceTimersByTime(1500); });
    await flush();
    expect(result.current.status).toBe('error');
    expect(warn).toHaveBeenCalled();
  });

  it('rescan() triggers an immediate request', async () => {
    const { result } = renderHook(() => useUnlinkedMentions({ page: 'P', text: 'a', enabled: true }));
    act(() => { vi.advanceTimersByTime(1500); });
    await flush();
    expect(api.scanMentions).toHaveBeenCalledTimes(1);
    act(() => { result.current.rescan(); });
    act(() => { vi.advanceTimersByTime(0); });
    expect(api.scanMentions).toHaveBeenCalledTimes(2);
  });
});
