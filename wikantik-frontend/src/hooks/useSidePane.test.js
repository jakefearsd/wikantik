import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSidePane } from './useSidePane';

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('useSidePane', () => {
  it('opens on the preview by default', () => {
    expect(renderHook(() => useSidePane()).result.current[0]).toEqual({ pane: 'preview', last: 'preview' });
  });

  it('toggle hides the pane and brings back what it showed', () => {
    const { result } = renderHook(() => useSidePane());
    act(() => result.current[1]('source'));
    act(() => result.current[2]());
    expect(result.current[0]).toEqual({ pane: 'hidden', last: 'source' });
    act(() => result.current[2]());
    expect(result.current[0]).toEqual({ pane: 'source', last: 'source' });
  });

  it('remembers hidden or shown, not which content: a shown pane opens on the preview', () => {
    const first = renderHook(() => useSidePane());
    act(() => first.result.current[2]());
    expect(renderHook(() => useSidePane()).result.current[0].pane).toBe('hidden');
    act(() => first.result.current[1]('source'));
    expect(renderHook(() => useSidePane()).result.current[0].pane).toBe('preview');
  });

  it('works when storage throws, and says so', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const { result } = renderHook(() => useSidePane());
    expect(result.current[0].pane).toBe('preview');
    act(() => result.current[2]());
    expect(result.current[0].pane).toBe('hidden');
    expect(warn).toHaveBeenCalledTimes(2);
  });
});
