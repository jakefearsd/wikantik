import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useRecentSearches } from './useRecentSearches';

const LS_KEY = 'wikantik:recent-searches';

beforeEach(() => localStorage.clear());

describe('useRecentSearches', () => {
  it('starts empty', () => {
    const { result } = renderHook(() => useRecentSearches());
    expect(result.current.searches).toEqual([]);
  });

  it('records a search and returns it most-recent-first', () => {
    const { result } = renderHook(() => useRecentSearches());
    act(() => result.current.record('alpha'));
    act(() => result.current.record('beta'));
    expect(result.current.searches).toEqual(['beta', 'alpha']);
  });

  it('deduplicates case-insensitively (keeps most recent)', () => {
    const { result } = renderHook(() => useRecentSearches());
    act(() => result.current.record('Alpha'));
    act(() => result.current.record('ALPHA'));
    expect(result.current.searches).toHaveLength(1);
    expect(result.current.searches[0]).toBe('ALPHA');
  });

  it('ignores empty / whitespace-only strings', () => {
    const { result } = renderHook(() => useRecentSearches());
    act(() => result.current.record(''));
    act(() => result.current.record('   '));
    expect(result.current.searches).toHaveLength(0);
  });

  it('caps at 8 entries', () => {
    const { result } = renderHook(() => useRecentSearches());
    act(() => {
      for (let i = 0; i < 10; i++) result.current.record(`query${i}`);
    });
    expect(result.current.searches).toHaveLength(8);
    expect(result.current.searches[0]).toBe('query9');
  });

  it('persists to localStorage', () => {
    const { result } = renderHook(() => useRecentSearches());
    act(() => result.current.record('saved'));
    const stored = JSON.parse(localStorage.getItem(LS_KEY));
    expect(stored).toContain('saved');
  });

  it('reads from localStorage on mount', () => {
    localStorage.setItem(LS_KEY, JSON.stringify(['existing']));
    const { result } = renderHook(() => useRecentSearches());
    expect(result.current.searches).toContain('existing');
  });

  it('clear() empties the list and localStorage', () => {
    const { result } = renderHook(() => useRecentSearches());
    act(() => result.current.record('to-clear'));
    act(() => result.current.clear());
    expect(result.current.searches).toHaveLength(0);
    expect(localStorage.getItem(LS_KEY)).toBeNull();
  });
});
