import { useEffect } from 'react';

/**
 * useGlobalHotkeys — attaches global window keydown listeners for app-wide
 * keyboard shortcuts. Currently handles:
 *   - Cmd/Ctrl+K → calls onSearch (opens the search overlay)
 *
 * Cleans up the listener on unmount.
 *
 * @param {{ onSearch?: () => void }} callbacks
 */
export function useGlobalHotkeys({ onSearch } = {}) {
  useEffect(() => {
    const handler = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        onSearch?.();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onSearch]);
}
