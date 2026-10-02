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
    expect(api.listPages).toHaveBeenCalledWith({ names: ['Nope', 'foo bar'], resolve: true, limit: 50 });
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
    expect(api.listPages).toHaveBeenLastCalledWith({ names: ['B'], resolve: true, limit: 50 });
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
});
