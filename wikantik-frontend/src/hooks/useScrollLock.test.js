import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useScrollLock, _resetLockCount } from './useScrollLock';

// Reset the module-level reference count between tests so they are independent.
beforeEach(() => {
  _resetLockCount();
  // Restore body styles to a clean state.
  document.body.style.overflow = '';
  document.body.style.paddingRight = '';
});

describe('useScrollLock', () => {
  it('sets overflow hidden on body when active', () => {
    renderHook(() => useScrollLock(true));
    expect(document.body.style.overflow).toBe('hidden');
  });

  it('restores prior overflow and paddingRight on deactivate', () => {
    // Set some pre-existing inline styles
    document.body.style.overflow = 'scroll';
    document.body.style.paddingRight = '5px';

    const { rerender } = renderHook(({ active }) => useScrollLock(active), {
      initialProps: { active: true },
    });

    // While active, overflow should be hidden
    expect(document.body.style.overflow).toBe('hidden');

    // Deactivate
    act(() => { rerender({ active: false }); });

    // Should be restored to the original inline values
    expect(document.body.style.overflow).toBe('scroll');
    expect(document.body.style.paddingRight).toBe('5px');
  });

  it('restores to empty string when no prior inline style', () => {
    // No prior inline styles (both empty)
    const { rerender } = renderHook(({ active }) => useScrollLock(active), {
      initialProps: { active: true },
    });
    act(() => { rerender({ active: false }); });
    expect(document.body.style.overflow).toBe('');
    expect(document.body.style.paddingRight).toBe('');
  });

  it('two locks: still locked after first releases, unlocked after both release', () => {
    const { rerender: rerender1 } = renderHook(({ active }) => useScrollLock(active), {
      initialProps: { active: true },
    });
    const { rerender: rerender2 } = renderHook(({ active }) => useScrollLock(active), {
      initialProps: { active: true },
    });

    // Both active → locked
    expect(document.body.style.overflow).toBe('hidden');

    // Release first lock
    act(() => { rerender1({ active: false }); });
    // Second still active → still locked
    expect(document.body.style.overflow).toBe('hidden');

    // Release second lock
    act(() => { rerender2({ active: false }); });
    // Both released → unlocked
    expect(document.body.style.overflow).toBe('');
  });

  it('does not modify body when inactive', () => {
    renderHook(() => useScrollLock(false));
    expect(document.body.style.overflow).toBe('');
  });
});
