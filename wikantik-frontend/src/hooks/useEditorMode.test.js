import { describe, it, expect, vi, beforeEach } from 'vitest';
import { StrictMode } from 'react';
import { renderHook, act } from '@testing-library/react';
import { useEditorMode } from './useEditorMode';

describe('useEditorMode', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks(); });
  it('defaults to source and persists the toggle', () => {
    const { result } = renderHook(() => useEditorMode());
    expect(result.current[0]).toBe('source');
    act(() => result.current[1]());
    expect(result.current[0]).toBe('live');
    expect(localStorage.getItem('wikantik.editor.mode')).toBe('live');
    expect(renderHook(() => useEditorMode()).result.current[0]).toBe('live');
  });
  it('ignores a garbage stored value', () => {
    localStorage.setItem('wikantik.editor.mode', 'wysiwyg');
    expect(renderHook(() => useEditorMode()).result.current[0]).toBe('source');
  });
  it('still works (and warns) when storage throws', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const { result } = renderHook(() => useEditorMode());
    act(() => result.current[1]());
    expect(result.current[0]).toBe('live');
    const msgs = warn.mock.calls.map((c) => c[0]);
    expect(msgs.some((m) => m.includes('read'))).toBe(true);
    expect(msgs.some((m) => m.includes('save'))).toBe(true);
  });
  it('under StrictMode a toggle writes storage exactly once (no side effect in the updater)', () => {
    const set = vi.spyOn(window.localStorage, 'setItem');
    const { result } = renderHook(() => useEditorMode(), { wrapper: StrictMode });
    act(() => result.current[1]());
    expect(result.current[0]).toBe('live');
    expect(set).toHaveBeenCalledTimes(1);
  });
});
