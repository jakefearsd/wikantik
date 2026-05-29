import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDraft } from './useDraft';
import { draftKey } from '../utils/draftKeys';

beforeEach(() => localStorage.clear());

describe('useDraft', () => {
  it('reads an existing draft on mount', () => {
    localStorage.setItem(
      draftKey('alice', 'Foo'),
      JSON.stringify({ content: 'hi', title: 'Foo', savedAt: 123 }),
    );
    const { result } = renderHook(() =>
      useDraft({ login: 'alice', pageId: 'Foo', enabled: true }));
    expect(result.current.draft.content).toBe('hi');
  });

  it('saveDraft persists a namespaced entry', () => {
    const { result } = renderHook(() =>
      useDraft({ login: 'alice', pageId: 'Foo', enabled: true }));
    act(() => result.current.saveDraft({ content: 'edit', title: 'Foo' }));
    const raw = JSON.parse(localStorage.getItem(draftKey('alice', 'Foo')));
    expect(raw.content).toBe('edit');
    expect(typeof raw.savedAt).toBe('number');
  });

  it('clearDraft removes the entry', () => {
    localStorage.setItem(draftKey('alice', 'Foo'), JSON.stringify({ content: 'x', savedAt: 1 }));
    const { result } = renderHook(() =>
      useDraft({ login: 'alice', pageId: 'Foo', enabled: true }));
    act(() => result.current.clearDraft());
    expect(localStorage.getItem(draftKey('alice', 'Foo'))).toBeNull();
  });

  it('is inert when disabled (no login)', () => {
    const { result } = renderHook(() =>
      useDraft({ login: null, pageId: 'Foo', enabled: false }));
    act(() => result.current.saveDraft({ content: 'edit' }));
    expect(localStorage.length).toBe(0);
    expect(result.current.draft).toBeNull();
  });
});
