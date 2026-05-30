import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useRef } from 'react';
import { useFocusTrap } from './useFocusTrap';

// Helper: create a container with focusable children and attach it to document.body
function makeContainer(focusableCount = 3) {
  const container = document.createElement('div');
  for (let i = 0; i < focusableCount; i++) {
    const btn = document.createElement('button');
    btn.textContent = `Button ${i}`;
    btn.setAttribute('data-idx', String(i));
    container.appendChild(btn);
  }
  document.body.appendChild(container);
  return container;
}

describe('useFocusTrap', () => {
  let container;

  beforeEach(() => {
    container = makeContainer(3);
  });

  afterEach(() => {
    document.body.removeChild(container);
  });

  it('does not attach any behavior when inactive', () => {
    const addSpy = vi.spyOn(container, 'addEventListener');
    const { result } = renderHook(() => {
      const ref = useRef(container);
      useFocusTrap(ref, false);
      return ref;
    });
    // No keydown listener on container when inactive
    expect(addSpy).not.toHaveBeenCalled();
    addSpy.mockRestore();
  });

  it('focuses the first focusable descendant on activate', () => {
    const buttons = container.querySelectorAll('button');
    const { rerender } = renderHook(
      ({ active }) => {
        const ref = useRef(container);
        useFocusTrap(ref, active);
      },
      { initialProps: { active: false } },
    );
    act(() => {
      rerender({ active: true });
    });
    expect(document.activeElement).toBe(buttons[0]);
  });

  it('Tab from last focusable wraps to first', () => {
    const buttons = [...container.querySelectorAll('button')];

    renderHook(() => {
      const ref = useRef(container);
      useFocusTrap(ref, true);
    });

    // Move focus to last button
    act(() => { buttons[buttons.length - 1].focus(); });

    // Dispatch Tab key on the container
    act(() => {
      const event = new KeyboardEvent('keydown', {
        key: 'Tab',
        bubbles: true,
        cancelable: true,
        shiftKey: false,
      });
      container.dispatchEvent(event);
    });

    expect(document.activeElement).toBe(buttons[0]);
  });

  it('Shift+Tab from first focusable wraps to last', () => {
    const buttons = [...container.querySelectorAll('button')];

    renderHook(() => {
      const ref = useRef(container);
      useFocusTrap(ref, true);
    });

    // Move focus to first button
    act(() => { buttons[0].focus(); });

    // Dispatch Shift+Tab on the container
    act(() => {
      const event = new KeyboardEvent('keydown', {
        key: 'Tab',
        bubbles: true,
        cancelable: true,
        shiftKey: true,
      });
      container.dispatchEvent(event);
    });

    expect(document.activeElement).toBe(buttons[buttons.length - 1]);
  });

  it('restores focus to the previously focused element on deactivate', () => {
    const outsideBtn = document.createElement('button');
    outsideBtn.textContent = 'Outside';
    document.body.appendChild(outsideBtn);

    // Focus the outside button before activating the trap
    act(() => { outsideBtn.focus(); });
    expect(document.activeElement).toBe(outsideBtn);

    const { rerender } = renderHook(
      ({ active }) => {
        const ref = useRef(container);
        useFocusTrap(ref, active);
      },
      { initialProps: { active: true } },
    );

    // Deactivate
    act(() => { rerender({ active: false }); });

    expect(document.activeElement).toBe(outsideBtn);

    document.body.removeChild(outsideBtn);
  });
});
