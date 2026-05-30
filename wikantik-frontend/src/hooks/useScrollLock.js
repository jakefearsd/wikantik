import { useEffect, useRef } from 'react';

// Module-level reference count — prevents nested locks from unlocking early.
let lockCount = 0;
// Saved inline styles, captured before the first lock is applied.
let savedOverflow = '';
let savedPaddingRight = '';

/**
 * Exported only for test isolation; do not call in production code.
 */
export function _resetLockCount() {
  lockCount = 0;
  savedOverflow = '';
  savedPaddingRight = '';
}

/**
 * Prevent body scroll while `active` is true.
 * Uses a module-level reference count so nested activations stay locked
 * until every consumer releases.
 *
 * @param {boolean} active
 */
export function useScrollLock(active) {
  // Track whether THIS hook instance currently holds a lock, to avoid
  // double-counting on re-renders.
  const holdsLock = useRef(false);

  useEffect(() => {
    if (!active) {
      // If we were holding a lock and just became inactive, release.
      if (holdsLock.current) {
        holdsLock.current = false;
        lockCount -= 1;
        if (lockCount === 0) {
          document.body.style.overflow = savedOverflow;
          document.body.style.paddingRight = savedPaddingRight;
        }
      }
      return;
    }

    // active === true
    if (!holdsLock.current) {
      if (lockCount === 0) {
        // First lock: capture current inline styles before we overwrite them.
        savedOverflow = document.body.style.overflow;
        savedPaddingRight = document.body.style.paddingRight;

        const scrollbarWidth =
          window.innerWidth - document.documentElement.clientWidth;

        document.body.style.overflow = 'hidden';
        if (scrollbarWidth > 0) {
          document.body.style.paddingRight = `${scrollbarWidth}px`;
        }
      }
      lockCount += 1;
      holdsLock.current = true;
    }

    return () => {
      // Cleanup on unmount while still active.
      if (holdsLock.current) {
        holdsLock.current = false;
        lockCount -= 1;
        if (lockCount === 0) {
          document.body.style.overflow = savedOverflow;
          document.body.style.paddingRight = savedPaddingRight;
        }
      }
    };
  }, [active]);
}
