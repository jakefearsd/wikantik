import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

const KEY = 'wikantik.editor.mode';
const DEFAULT_MODE = 'live';

function isMode(v) {
  return v === 'live' || v === 'source';
}

/** The browser's remembered choice, or null when there is none (or storage is unavailable). */
function storedMode() {
  try {
    const stored = localStorage.getItem(KEY);
    if (isMode(stored)) return stored;
  } catch (err) {
    console.warn('[editor-mode] could not read the saved editor mode', err?.message || err);
  }
  return null;
}

function saveLocal(mode) {
  try {
    localStorage.setItem(KEY, mode);
  } catch (err) {
    console.warn('[editor-mode] could not save the editor mode', err?.message || err);
  }
}

/**
 * Source vs live-preview editing. Resolution: the signed-in user's server preference, else this
 * browser's remembered choice, else live. {@code persist=false} (legacy-syntax pages) never writes
 * anywhere and never migrates.
 */
export function useEditorMode({ authenticated = false, persist = true } = {}) {
  const [mode, setMode] = useState(() => storedMode() ?? DEFAULT_MODE);
  const modeRef = useRef(mode);
  modeRef.current = mode;
  // Persist from an effect, not the state updater: StrictMode double-invokes updaters. Only after a toggle,
  // so merely mounting never writes the default.
  const dirty = useRef(false);
  const fetched = useRef(false);
  const mounted = useRef(true);
  // A mount flag (not a per-effect flag): StrictMode runs effect cleanup between its two passes.
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    if (!dirty.current || !persist) return;
    saveLocal(mode);
  }, [mode, persist]);

  useEffect(() => {
    if (!authenticated || !persist || fetched.current) return undefined;
    fetched.current = true;
    Promise.resolve().then(() => api.getProfile()).then((profile) => {
      if (!mounted.current || dirty.current) return; // a toggle made meanwhile is newer than the server's answer
      const server = profile?.editorMode;
      if (isMode(server)) {
        setMode(server);
        saveLocal(server);
        return;
      }
      const local = storedMode();
      if (local) {
        // One-time migration of this browser's explicit choice to the account.
        Promise.resolve(api.updateProfile({ editorMode: local })).catch((err) => {
          console.warn('[editor-mode] could not migrate the editor mode to the profile', err?.message || err);
        });
      }
    }).catch((err) => {
      console.warn('[editor-mode] could not load the editor mode preference', err?.message || err);
    });
    return undefined;
  }, [authenticated, persist]);

  const toggle = useCallback(() => {
    dirty.current = true;
    const next = modeRef.current === 'live' ? 'source' : 'live';
    setMode(next);
    if (authenticated && persist) {
      Promise.resolve(api.updateProfile({ editorMode: next })).catch((err) => {
        console.warn('[editor-mode] could not save the editor mode to the profile', err?.message || err);
      });
    }
  }, [authenticated, persist]);

  return [mode, toggle];
}
