import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useRecentlyViewed } from './useRecentlyViewed';

beforeEach(() => localStorage.clear());

describe('useRecentlyViewed', () => {
  it('records newest-first and dedups by slug', () => {
    const { result } = renderHook(() => useRecentlyViewed({ login: 'alice', enabled: true }));
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    act(() => result.current.record({ slug: 'B', title: 'B' }));
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    expect(result.current.items.map(i => i.slug)).toEqual(['A', 'B']);
  });

  it('caps the buffer at 20', () => {
    const { result } = renderHook(() => useRecentlyViewed({ login: 'alice', enabled: true }));
    act(() => {
      for (let i = 0; i < 25; i++) result.current.record({ slug: `P${i}`, title: `P${i}` });
    });
    expect(result.current.items).toHaveLength(20);
    expect(result.current.items[0].slug).toBe('P24');
  });

  it('namespaces by login', () => {
    const { result: alice } = renderHook(() => useRecentlyViewed({ login: 'alice', enabled: true }));
    act(() => alice.current.record({ slug: 'A', title: 'A' }));
    const { result: bob } = renderHook(() => useRecentlyViewed({ login: 'bob', enabled: true }));
    expect(bob.current.items).toHaveLength(0);
  });

  it('is inert when disabled', () => {
    const { result } = renderHook(() => useRecentlyViewed({ login: null, enabled: false }));
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    expect(result.current.items).toHaveLength(0);
    expect(localStorage.length).toBe(0);
  });
});
