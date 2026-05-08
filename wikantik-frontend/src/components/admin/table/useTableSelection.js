import { useState, useCallback, useRef } from 'react';

/**
 * Hook managing multi-select state for a table.
 *
 * @returns {{
 *   selected: Set<string>,
 *   toggle: (key: string, options?: { shift?: boolean, allKeys?: string[] }) => void,
 *   toggleAll: (keys: string[]) => void,
 *   isSelected: (key: string) => boolean,
 *   isAllSelected: (keys: string[]) => boolean,
 *   isIndeterminate: (keys: string[]) => boolean,
 *   clear: () => void,
 * }}
 */
export function useTableSelection() {
  const [selected, setSelected] = useState(new Set());
  const lastClickedRef = useRef(null);

  const isSelected = useCallback(
    (key) => selected.has(key),
    [selected]
  );

  const isAllSelected = useCallback(
    (keys) => keys.length > 0 && keys.every((k) => selected.has(k)),
    [selected]
  );

  const isIndeterminate = useCallback(
    (keys) => {
      if (keys.length === 0) return false;
      const count = keys.filter((k) => selected.has(k)).length;
      return count > 0 && count < keys.length;
    },
    [selected]
  );

  /**
   * Toggle a single key. When `options.shift` is true, extend the selection
   * range from the last-clicked key to `key` using `options.allKeys` order.
   *
   * @param {string} key
   * @param {{ shift?: boolean, allKeys?: string[] }} [options]
   */
  const toggle = useCallback((key, options = {}) => {
    const { shift = false, allKeys = [] } = options;

    if (shift && lastClickedRef.current != null && allKeys.length > 0) {
      const anchorIdx = allKeys.indexOf(lastClickedRef.current);
      const targetIdx = allKeys.indexOf(key);

      if (anchorIdx !== -1 && targetIdx !== -1) {
        const lo = Math.min(anchorIdx, targetIdx);
        const hi = Math.max(anchorIdx, targetIdx);
        const rangeKeys = allKeys.slice(lo, hi + 1);
        setSelected((prev) => {
          const next = new Set(prev);
          rangeKeys.forEach((k) => next.add(k));
          return next;
        });
        // Don't update lastClickedRef on shift-click — anchor stays fixed
        return;
      }
      // Fall through to single-toggle if anchor not found in allKeys
    }

    lastClickedRef.current = key;
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }, []);

  /**
   * If all `keys` are selected, deselect all. Otherwise select all.
   *
   * @param {string[]} keys
   */
  const toggleAll = useCallback((keys) => {
    if (keys.length === 0) return;
    setSelected((prev) => {
      const allIn = keys.every((k) => prev.has(k));
      if (allIn) {
        const next = new Set(prev);
        keys.forEach((k) => next.delete(k));
        return next;
      }
      const next = new Set(prev);
      keys.forEach((k) => next.add(k));
      return next;
    });
  }, []);

  const clear = useCallback(() => {
    lastClickedRef.current = null;
    setSelected(new Set());
  }, []);

  return {
    selected,
    toggle,
    toggleAll,
    isSelected,
    isAllSelected,
    isIndeterminate,
    clear,
  };
}
