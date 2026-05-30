import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useScrollSpy from './useScrollSpy';

// ---------------------------------------------------------------------------
// IntersectionObserver mock
// ---------------------------------------------------------------------------
let observerCallback = null;
const observeSpy = vi.fn();
const disconnectSpy = vi.fn();

class MockIntersectionObserver {
  constructor(cb) {
    observerCallback = cb;
    this.observe = observeSpy;
    this.disconnect = disconnectSpy;
  }
}

beforeEach(() => {
  vi.clearAllMocks();
  observerCallback = null;
  global.IntersectionObserver = MockIntersectionObserver;
});

afterEach(() => {
  delete global.IntersectionObserver;
});

// Helpers: create actual DOM elements so the hook can query them
function createElements(ids) {
  ids.forEach((id) => {
    const el = document.createElement('div');
    el.id = id;
    document.body.appendChild(el);
  });
}

function removeElements(ids) {
  ids.forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.remove();
  });
}

describe('useScrollSpy', () => {
  it('returns empty string initially when no headings intersect', () => {
    createElements(['sec-a', 'sec-b', 'sec-c']);
    const { result } = renderHook(() => useScrollSpy(['sec-a', 'sec-b', 'sec-c']));
    expect(result.current).toBe('');
    removeElements(['sec-a', 'sec-b', 'sec-c']);
  });

  it('returns the id of the intersecting entry when callback fires', () => {
    createElements(['intro', 'body', 'conclusion']);
    const { result } = renderHook(() => useScrollSpy(['intro', 'body', 'conclusion']));

    act(() => {
      observerCallback([
        { target: document.getElementById('body'), isIntersecting: true },
        { target: document.getElementById('intro'), isIntersecting: false },
      ]);
    });

    expect(result.current).toBe('body');
    removeElements(['intro', 'body', 'conclusion']);
  });

  it('observes each element', () => {
    createElements(['h-1', 'h-2', 'h-3']);
    renderHook(() => useScrollSpy(['h-1', 'h-2', 'h-3']));
    expect(observeSpy).toHaveBeenCalledTimes(3);
    removeElements(['h-1', 'h-2', 'h-3']);
  });

  it('disconnects on unmount', () => {
    createElements(['x', 'y']);
    const { unmount } = renderHook(() => useScrollSpy(['x', 'y']));
    unmount();
    expect(disconnectSpy).toHaveBeenCalledTimes(1);
    removeElements(['x', 'y']);
  });

  it('returns empty string when ids array is empty', () => {
    const { result } = renderHook(() => useScrollSpy([]));
    expect(result.current).toBe('');
  });

  it('does not throw when IntersectionObserver is unavailable', () => {
    delete global.IntersectionObserver;
    createElements(['a', 'b']);
    const { result } = renderHook(() => useScrollSpy(['a', 'b']));
    expect(result.current).toBe('');
    removeElements(['a', 'b']);
  });
});
