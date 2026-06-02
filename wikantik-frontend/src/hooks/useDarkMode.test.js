import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDarkMode } from './useDarkMode';

beforeEach(() => {
  localStorage.setItem('wikantik-theme', 'light');
  document.documentElement.setAttribute('data-theme', 'light');
});

describe('useDarkMode', () => {
  it('toggling one consumer flips its own value', () => {
    const { result } = renderHook(() => useDarkMode());
    const initial = result.current[0];
    act(() => result.current[1]());
    expect(result.current[0]).toBe(!initial);
  });

  it('keeps the html data-theme attribute in sync on toggle', () => {
    const { result } = renderHook(() => useDarkMode());
    act(() => result.current[1]());
    const expected = result.current[0] ? 'dark' : 'light';
    expect(document.documentElement.getAttribute('data-theme')).toBe(expected);
  });

  // The bug: a toggle in one consumer (e.g. the Sidebar button) must update
  // every other consumer (e.g. the editor's CodeMirror theme) without a refresh.
  it('syncs all hook instances when any one toggles', () => {
    const a = renderHook(() => useDarkMode());
    const b = renderHook(() => useDarkMode());
    const initial = a.result.current[0];
    expect(b.result.current[0]).toBe(initial);

    act(() => a.result.current[1]()); // toggle via instance A

    expect(a.result.current[0]).toBe(!initial);
    expect(b.result.current[0]).toBe(!initial); // B must follow A — fails before the fix
  });
});
