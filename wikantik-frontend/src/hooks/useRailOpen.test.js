import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useRailOpen } from './useRailOpen';

const mq = (matches) => vi.spyOn(window, 'matchMedia').mockImplementation(() => ({ matches }));
afterEach(() => { vi.restoreAllMocks(); try { localStorage.clear(); } catch { /* storage may be unavailable in some tests */ } });

describe('useRailOpen', () => {
  it('defaults open at ≥1100px and closed below', () => {
    mq(true);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(true);
    vi.restoreAllMocks();
    mq(false);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(false);
  });
  it('remembers the choice', () => {
    mq(true);
    const { result } = renderHook(() => useRailOpen());
    act(() => result.current[1]());
    expect(result.current[0]).toBe(false);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(false);
  });
  it('works when storage throws', () => {
    mq(true);
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const { result } = renderHook(() => useRailOpen());
    expect(result.current[0]).toBe(true);
    act(() => result.current[1]());
    expect(result.current[0]).toBe(false);
  });
  it('M7: a stored "open" preference is ignored below 1100px (narrow always starts closed)', () => {
    localStorage.setItem('wikantik.editor.railOpen', 'true');
    mq(false);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(false);
  });
  it('M7: a stored preference still applies on wide viewports', () => {
    localStorage.setItem('wikantik.editor.railOpen', 'false');
    mq(true);
    expect(renderHook(() => useRailOpen()).result.current[0]).toBe(false);
  });
});
