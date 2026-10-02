import { useCallback, useState } from 'react';

const KEY = 'wikantik.editor.mode';

function initialMode() {
  try {
    const stored = localStorage.getItem(KEY);
    if (stored === 'live' || stored === 'source') return stored;
  } catch (err) {
    console.warn('[editor-mode] could not read the saved editor mode', err?.message || err);
  }
  return 'source';
}

/** Source vs live-preview editing, remembered per browser; defaults to source. */
export function useEditorMode() {
  const [mode, setMode] = useState(initialMode);
  const toggle = useCallback(() => {
    setMode((prev) => {
      const next = prev === 'live' ? 'source' : 'live';
      try {
        localStorage.setItem(KEY, next);
      } catch (err) {
        console.warn('[editor-mode] could not save the editor mode', err?.message || err);
      }
      return next;
    });
  }, []);
  return [mode, toggle];
}
