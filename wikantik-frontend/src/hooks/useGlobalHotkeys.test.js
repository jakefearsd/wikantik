import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useGlobalHotkeys } from './useGlobalHotkeys';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useGlobalHotkeys', () => {
  it('calls onSearch when Cmd+K is pressed', () => {
    const onSearch = vi.fn();
    renderHook(() => useGlobalHotkeys({ onSearch }));
    const event = new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true });
    const preventDefaultSpy = vi.spyOn(event, 'preventDefault');
    window.dispatchEvent(event);
    expect(onSearch).toHaveBeenCalledTimes(1);
    expect(preventDefaultSpy).toHaveBeenCalled();
  });

  it('calls onSearch when Ctrl+K is pressed', () => {
    const onSearch = vi.fn();
    renderHook(() => useGlobalHotkeys({ onSearch }));
    const event = new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true });
    window.dispatchEvent(event);
    expect(onSearch).toHaveBeenCalledTimes(1);
  });

  it('does not call onSearch for non-K key with modifier', () => {
    const onSearch = vi.fn();
    renderHook(() => useGlobalHotkeys({ onSearch }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'p', metaKey: true, bubbles: true }));
    expect(onSearch).not.toHaveBeenCalled();
  });

  it('does not call onSearch for K without modifier', () => {
    const onSearch = vi.fn();
    renderHook(() => useGlobalHotkeys({ onSearch }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', bubbles: true }));
    expect(onSearch).not.toHaveBeenCalled();
  });

  it('removes listener on unmount', () => {
    const onSearch = vi.fn();
    const { unmount } = renderHook(() => useGlobalHotkeys({ onSearch }));
    unmount();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true }));
    expect(onSearch).not.toHaveBeenCalled();
  });
});
