import { useCallback, useState } from 'react';

const KEY = 'wikantik.editor.railOpen';
const WIDE = '(min-width: 1100px)';

function isWide() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(WIDE).matches
    : true;
}

function initialOpen() {
  // Narrow viewports show the rail as an overlay drawer: it always starts closed there.
  if (!isWide()) return false;
  try {
    const stored = localStorage.getItem(KEY);
    if (stored === 'true' || stored === 'false') return stored === 'true';
  } catch (err) {
    console.warn('[editor-rail] could not read the saved rail state', err?.message || err);
  }
  return true;
}

/** Side-rail open state: remembered per browser (honoured on wide viewports only); defaults open there. */
export function useRailOpen() {
  const [open, setOpen] = useState(initialOpen);
  const toggle = useCallback(() => {
    setOpen((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(KEY, String(next));
      } catch (err) {
        console.warn('[editor-rail] could not save the rail state', err?.message || err);
      }
      return next;
    });
  }, []);
  return [open, toggle];
}
