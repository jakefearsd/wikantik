import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../api/client', () => ({ api: { listPages: vi.fn() } }));
import { api } from '../api/client';
import { useMissingPages } from './useMissingPages';

beforeEach(() => { vi.useFakeTimers(); api.listPages.mockReset(); });
afterEach(() => { vi.useRealTimers(); });

// The empty act flushes the promise chain after the timer fires.
// eslint-disable-next-line testing-library/no-unnecessary-act
const flush = async () => { await act(async () => { vi.advanceTimersByTime(500); }); await act(async () => {}); };

describe('useMissingPages', () => {
  it('checks link targets after 500 ms and reports the missing ones (lowercased)', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'Home' }] });
    const { result } = renderHook(({ md }) => useMissingPages(md), { initialProps: { md: '[a](Home) [b](Ghost)' } });
    expect(api.listPages).not.toHaveBeenCalled();
    await flush();
    expect(api.listPages).toHaveBeenCalledWith({ names: ['Ghost', 'Home'], limit: 50 });
    expect([...result.current]).toEqual(['ghost']);
  });

  it('only checks new targets and drops removed ones', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    const { result, rerender } = renderHook(({ md }) => useMissingPages(md), { initialProps: { md: '[b](Ghost)' } });
    await flush();
    rerender({ md: '[c](Other)' });
    await flush();
    expect(api.listPages).toHaveBeenLastCalledWith({ names: ['Other'], limit: 50 });
    expect([...result.current]).toEqual(['other']);
  });

  it('compares case-insensitively with the canonical names the server returns', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'Home' }] });
    const { result } = renderHook(() => useMissingPages('[a](home)'));
    await flush();
    expect(result.current.size).toBe(0);
  });

  it('keeps the same Set when a later check finds the same missing pages (no consumer re-render)', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    const { result, rerender } = renderHook(({ md }) => useMissingPages(md), { initialProps: { md: '[b](Ghost)' } });
    await flush();
    const first = result.current;
    expect([...first]).toEqual(['ghost']);
    rerender({ md: '[b](Ghost) more text' });
    await flush();
    expect(result.current).toBe(first);
  });

  it('chunks more than 50 targets', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    const md = Array.from({ length: 51 }, (_, i) => `[x](P${i})`).join(' ');
    renderHook(() => useMissingPages(md));
    await flush();
    expect(api.listPages).toHaveBeenCalledTimes(2);
  });

  it('warns and marks nothing when the check fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('503'));
    const { result } = renderHook(() => useMissingPages('[b](Ghost)'));
    await flush();
    expect(result.current.size).toBe(0);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
