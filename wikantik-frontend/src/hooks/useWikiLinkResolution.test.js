import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../api/client', () => ({ api: { listPages: vi.fn() } }));
import { api } from '../api/client';
import { useWikiLinkResolution } from './useWikiLinkResolution';

beforeEach(() => { vi.useFakeTimers(); api.listPages.mockReset(); });
afterEach(() => { vi.useRealTimers(); });

// eslint-disable-next-line testing-library/no-unnecessary-act
const flush = async () => { await act(async () => { vi.advanceTimersByTime(600); }); await act(async () => {}); };

describe('useWikiLinkResolution', () => {
  it('asks the server to resolve native targets and maps them case-insensitively', async () => {
    api.listPages.mockResolvedValue({ pages: [], resolved: { 'foo bar': 'FooBar', Nope: null } });
    const { result } = renderHook(() => useWikiLinkResolution('[[foo bar]] and [[Nope]] and `[[Code]]`'));
    await flush();
    expect(api.listPages).toHaveBeenCalledWith({ names: ['Nope', 'foo bar'], resolve: true, limit: 50, signal: expect.any(AbortSignal) });
    expect(result.current.get('foo bar')).toBe('FooBar');
    expect(result.current.get('nope')).toBeNull();
  });

  it('only asks about new targets', async () => {
    api.listPages.mockResolvedValue({ pages: [], resolved: { A: 'A' } });
    const { rerender } = renderHook(({ md }) => useWikiLinkResolution(md), { initialProps: { md: '[[A]]' } });
    await flush();
    api.listPages.mockResolvedValue({ pages: [], resolved: { B: null } });
    rerender({ md: '[[A]] [[B]]' });
    await flush();
    expect(api.listPages).toHaveBeenLastCalledWith({ names: ['B'], resolve: true, limit: 50, signal: expect.any(AbortSignal) });
  });

  it('warns and keeps going when resolution fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useWikiLinkResolution('[[A]]'));
    await flush();
    expect(warn).toHaveBeenCalled();
    expect(result.current.size).toBe(0);
    warn.mockRestore();
  });

  it('dedupes names case-insensitively before requesting', async () => {
    api.listPages.mockResolvedValue({ pages: [], resolved: {} });
    renderHook(() => useWikiLinkResolution('[[Foo]] [[foo]] [[FOO]]'));
    await flush();
    expect(api.listPages).toHaveBeenCalledTimes(1);
    expect(api.listPages.mock.calls[0][0].names).toHaveLength(1);
  });

  it('does not re-request names that are still in flight', async () => {
    api.listPages.mockReturnValue(new Promise(() => {}));
    const { rerender } = renderHook(({ md }) => useWikiLinkResolution(md), { initialProps: { md: '[[A]]' } });
    await flush();
    rerender({ md: '[[A]] ' });
    await flush();
    expect(api.listPages).toHaveBeenCalledTimes(1);
  });

  it('keeps successful chunks when another chunk fails, warning once', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const md = Array.from({ length: 60 }, (_, i) => `[[P${String(i).padStart(2, '0')}]]`).join(' ');
    api.listPages
      .mockResolvedValueOnce({ pages: [], resolved: { P00: 'P00' } })
      .mockRejectedValueOnce(new Error('boom'));
    const { result } = renderHook(() => useWikiLinkResolution(md));
    await flush();
    expect(result.current.get('p00')).toBe('P00');
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('backs off after a failure instead of re-requesting on every edit', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('boom'));
    const { rerender } = renderHook(({ md }) => useWikiLinkResolution(md), { initialProps: { md: '[[A]]' } });
    await flush();
    rerender({ md: '[[A]] ' });
    await flush();
    expect(api.listPages).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('keeps the same Map instance when nothing changed', async () => {
    api.listPages.mockResolvedValue({ pages: [], resolved: { A: 'A' } });
    const { result, rerender } = renderHook(({ md }) => useWikiLinkResolution(md), { initialProps: { md: '[[A]]' } });
    await flush();
    const first = result.current;
    rerender({ md: '[[A]] more' });
    await flush();
    expect(result.current).toBe(first);
  });

  it('aborts the in-flight request on unmount', async () => {
    api.listPages.mockReturnValue(new Promise(() => {}));
    const { unmount } = renderHook(() => useWikiLinkResolution('[[A]]'));
    await flush();
    const { signal } = api.listPages.mock.calls[0][0];
    unmount();
    expect(signal.aborted).toBe(true);
  });
});
