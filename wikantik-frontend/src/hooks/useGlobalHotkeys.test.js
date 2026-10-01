import { renderHook } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useGlobalHotkeys } from './useGlobalHotkeys';

const press = (init) => {
  const e = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  window.dispatchEvent(e);
  return e;
};

describe('useGlobalHotkeys', () => {
  it.each([
    [{ key: 'k', ctrlKey: true }, 'pages'],
    [{ key: 'k', metaKey: true }, 'pages'],
    [{ key: 'o', ctrlKey: true }, 'pages'],
    [{ key: 'p', metaKey: true }, 'commands'],
  ])('%o opens the overlay in %s mode', (init, mode) => {
    const onOpenOverlay = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    const e = press(init);
    expect(onOpenOverlay).toHaveBeenCalledWith(mode);
    expect(e.defaultPrevented).toBe(true);
  });

  it('ignores a key the editor already handled (Mod-K inserts a link in CodeMirror)', () => {
    const onOpenOverlay = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    const e = new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true, cancelable: true });
    e.preventDefault();
    window.dispatchEvent(e);
    expect(onOpenOverlay).not.toHaveBeenCalled();
  });

  it('ignores unmodified keys and other letters', () => {
    const onOpenOverlay = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    press({ key: 'k' });
    press({ key: 'x', ctrlKey: true });
    expect(onOpenOverlay).not.toHaveBeenCalled();
  });

  it('detaches on unmount', () => {
    const onOpenOverlay = vi.fn();
    const { unmount } = renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    unmount();
    press({ key: 'k', ctrlKey: true });
    expect(onOpenOverlay).not.toHaveBeenCalled();
  });
});
