import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePageTrail } from './usePageTrail';

beforeEach(() => sessionStorage.clear());

describe('usePageTrail', () => {
  it('starts empty', () => {
    const { result } = renderHook(() => usePageTrail());
    expect(result.current.items).toEqual([]);
  });

  it('records with the current page LAST (trail reads oldest → newest)', () => {
    const { result } = renderHook(() => usePageTrail());
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    act(() => result.current.record({ slug: 'B', title: 'B' }));
    expect(result.current.items.map((i) => i.slug)).toEqual(['A', 'B']);
  });

  it('dedups by slug, moving a revisited page to the current (last) position', () => {
    const { result } = renderHook(() => usePageTrail());
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    act(() => result.current.record({ slug: 'B', title: 'B' }));
    act(() => result.current.record({ slug: 'A', title: 'A' }));
    // A → B → A  ⇒  B · A(current)
    expect(result.current.items.map((i) => i.slug)).toEqual(['B', 'A']);
  });

  it('caps the trail at 3, dropping the oldest', () => {
    const { result } = renderHook(() => usePageTrail());
    act(() => {
      for (let i = 0; i < 5; i++) result.current.record({ slug: `P${i}`, title: `P${i}` });
    });
    expect(result.current.items.map((i) => i.slug)).toEqual(['P2', 'P3', 'P4']);
  });

  it('stores the title alongside the slug, falling back to the slug', () => {
    const { result } = renderHook(() => usePageTrail());
    act(() => result.current.record({ slug: 'Foo', title: 'Foo Title' }));
    act(() => result.current.record({ slug: 'Bar' }));
    expect(result.current.items).toEqual([
      { slug: 'Foo', title: 'Foo Title' },
      { slug: 'Bar', title: 'Bar' },
    ]);
  });

  it('ignores a blank slug', () => {
    const { result } = renderHook(() => usePageTrail());
    act(() => result.current.record({ slug: '', title: 'nope' }));
    expect(result.current.items).toEqual([]);
    expect(sessionStorage.length).toBe(0);
  });

  it('persists to sessionStorage so a fresh instance hydrates from it', () => {
    const first = renderHook(() => usePageTrail());
    act(() => first.result.current.record({ slug: 'A', title: 'A' }));
    const second = renderHook(() => usePageTrail());
    expect(second.result.current.items.map((i) => i.slug)).toEqual(['A']);
  });

  it('reflects records made by another live instance (same tab)', () => {
    // PageView records; Breadcrumbs reads. They mount separate instances and
    // must converge without a reload — a same-tab write fires no `storage` event.
    const a = renderHook(() => usePageTrail());
    const b = renderHook(() => usePageTrail());
    act(() => a.result.current.record({ slug: 'Foo', title: 'Foo' }));
    expect(b.result.current.items.map((i) => i.slug)).toEqual(['Foo']);
  });
});
