import { useEffect, useRef } from 'react';

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), ' +
  'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Trap keyboard focus within a container element while `active` is true.
 *
 * @param {React.RefObject<HTMLElement>} ref - Container whose descendants receive the trap.
 * @param {boolean} active - Enable/disable the trap.
 */
export function useFocusTrap(ref, active) {
  // Capture the element that had focus when we activated, so we can restore it.
  const previousFocusRef = useRef(null);

  useEffect(() => {
    if (!active) return;

    const container = ref.current;
    if (!container) return;

    // Remember the element that currently has focus so we can restore it later.
    previousFocusRef.current = document.activeElement;

    // Focus the first focusable descendant.
    const focusables = () =>
      [...container.querySelectorAll(FOCUSABLE_SELECTOR)].filter(
        (el) => el.offsetParent !== null || el.tabIndex >= 0,
      );

    const first = focusables()[0];
    if (first) first.focus();

    function handleKeyDown(e) {
      if (e.key !== 'Tab') return;

      const items = focusables();
      if (items.length === 0) {
        e.preventDefault();
        return;
      }

      const firstItem = items[0];
      const lastItem = items[items.length - 1];
      const active = document.activeElement;

      if (e.shiftKey) {
        // Shift+Tab: if on first, wrap to last
        if (active === firstItem || !container.contains(active)) {
          e.preventDefault();
          lastItem.focus();
        }
      } else {
        // Tab: if on last, wrap to first
        if (active === lastItem || !container.contains(active)) {
          e.preventDefault();
          firstItem.focus();
        }
      }
    }

    container.addEventListener('keydown', handleKeyDown);

    return () => {
      container.removeEventListener('keydown', handleKeyDown);
      // Restore focus to the element that had it before activation.
      const prev = previousFocusRef.current;
      if (prev && typeof prev.focus === 'function') {
        prev.focus();
      }
      previousFocusRef.current = null;
    };
  }, [active, ref]);
}
