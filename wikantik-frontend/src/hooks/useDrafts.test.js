import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDrafts } from './useDrafts';
import { draftKey } from '../utils/draftKeys';

beforeEach(() => localStorage.clear());

describe('useDrafts', () => {
  it('lists only the current login drafts, newest first', () => {
    localStorage.setItem(draftKey('alice', 'A'), JSON.stringify({ content: 'a', title: 'A', savedAt: 100 }));
    localStorage.setItem(draftKey('alice', 'B'), JSON.stringify({ content: 'b', title: 'B', savedAt: 200 }));
    localStorage.setItem(draftKey('bob', 'C'), JSON.stringify({ content: 'c', title: 'C', savedAt: 300 }));
    const { result } = renderHook(() => useDrafts({ login: 'alice', enabled: true }));
    expect(result.current.drafts.map(d => d.pageId)).toEqual(['B', 'A']);
  });

  it('removeDraft deletes the entry and updates the list', () => {
    localStorage.setItem(draftKey('alice', 'A'), JSON.stringify({ content: 'a', title: 'A', savedAt: 100 }));
    const { result } = renderHook(() => useDrafts({ login: 'alice', enabled: true }));
    act(() => result.current.removeDraft('A'));
    expect(result.current.drafts).toHaveLength(0);
    expect(localStorage.getItem(draftKey('alice', 'A'))).toBeNull();
  });

  it('returns empty when disabled', () => {
    localStorage.setItem(draftKey('alice', 'A'), JSON.stringify({ content: 'a', savedAt: 1 }));
    const { result } = renderHook(() => useDrafts({ login: null, enabled: false }));
    expect(result.current.drafts).toHaveLength(0);
  });
});
