import { describe, it, expect, vi, beforeEach } from 'vitest';
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
    expect(warn).toHaveBeenCalledTimes(2);
  });
});
