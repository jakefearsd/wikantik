import { renderHook } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useGlobalHotkeys } from './useGlobalHotkeys';

// happy-dom reports AltGraph whenever altKey is set; real browsers only do for an actual AltGr, so pin it.
const press = ({ altGraph = false, ...init } = {}) => {
  const e = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  Object.defineProperty(e, 'getModifierState', { value: (k) => k === 'AltGraph' && altGraph });
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

  it('Mod-Alt-N opens the daily note (matched on the physical key, as macOS Option rewrites e.key)', () => {
    const onDailyNote = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay: vi.fn(), onDailyNote }));
    press({ key: '˜', code: 'KeyN', metaKey: true, altKey: true });
    expect(onDailyNote).toHaveBeenCalledTimes(1);
    press({ key: 'n', code: 'KeyN', ctrlKey: true }); // no Alt → not ours
    expect(onDailyNote).toHaveBeenCalledTimes(1);
  });

  it('Mod-Alt-N ignores AltGr+N (Polish layout types a character with ctrl+alt)', () => {
    const onDailyNote = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay: vi.fn(), onDailyNote }));
    const e = press({ ctrlKey: true, altKey: true, code: 'KeyN', key: 'ń', altGraph: true });
    expect(onDailyNote).not.toHaveBeenCalled();
    expect(e.defaultPrevented).toBe(false);
  });

  it('Mod-Alt-N on a non-Mac layout also requires the key to be n', () => {
    const onDailyNote = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay: vi.fn(), onDailyNote }));
    const e = press({ ctrlKey: true, altKey: true, code: 'KeyN', key: 'ń' });
    expect(onDailyNote).not.toHaveBeenCalled();
    expect(e.defaultPrevented).toBe(false);
    press({ ctrlKey: true, altKey: true, code: 'KeyN', key: 'n' });
    expect(onDailyNote).toHaveBeenCalledTimes(1);
  });

  it('Mod-Alt-N is left alone (not preventDefault-ed) when no onDailyNote handler is provided', () => {
    renderHook(() => useGlobalHotkeys({ onOpenOverlay: vi.fn() }));
    const notPrevented = press({ key: 'n', code: 'KeyN', ctrlKey: true, altKey: true }).defaultPrevented === false;
    expect(notPrevented).toBe(true);
  });
});
