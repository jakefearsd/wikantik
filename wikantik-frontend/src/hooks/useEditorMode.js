import { useCallback, useEffect, useRef, useState } from 'react';

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
  // Persist from an effect, not the state updater: StrictMode double-invokes updaters. Only after a toggle,
  // so merely mounting never writes the default.
  const dirty = useRef(false);
  useEffect(() => {
    if (!dirty.current) return;
    try {
      localStorage.setItem(KEY, mode);
    } catch (err) {
      console.warn('[editor-mode] could not save the editor mode', err?.message || err);
    }
  }, [mode]);
  const toggle = useCallback(() => {
    dirty.current = true;
    setMode((prev) => (prev === 'live' ? 'source' : 'live'));
  }, []);
  return [mode, toggle];
}
